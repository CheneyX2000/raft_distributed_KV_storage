package com.github.cheneyx2000.raft.storage;

import com.github.cheneyx2000.raft.RaftOptions;
import com.github.cheneyx2000.raft.util.RaftFileUtils;
import com.github.cheneyx2000.raft.proto.RaftProto;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class SegmentedLog {

    private static Logger LOG = LoggerFactory.getLogger(SegmentedLog.class);

    private String logDir;
    private String logDataDir;
    private int maxSegmentFileSize;
    private RaftProto.LogMetaData metaData;
    private TreeMap<Long, Segment> startLogIndexSegmentMap = new TreeMap<>();
    // the ram size taken by the segment log, used to tell if a snapshot should be
    // taken
    private volatile long totalSize;

    public SegmentedLog(String raftDataDir, int maxSegmentFileSize) {
        this.logDir = raftDataDir + File.separator + "log";
        this.logDataDir = logDir + File.separator + "data";
        this.maxSegmentFileSize = maxSegmentFileSize;
        File file = new File(logDataDir);
        if (!file.exists()) {
            file.mkdirs();
        }
        readSegments();
        for (Segment segment : startLogIndexSegmentMap.values()) {
            this.loadSegmentData(segment);
        }

        metaData = this.readMeta();
        if (metaData == null) {
            if (startLogIndexSegmentMap.size() > 0) {
                LOG.error("No readable metadata file but found segments in {}", logDir);
                throw new RuntimeException("No readable metadata file but found segments");
            }
            metaData = RaftProto.LogMetaData.newBuilder().setFirstLogIndex(1).build();
        }
    }

    public RaftProto.LogEntry getEntry(long index) {
        long firstLogIndex = getFirstLogIndex();
        long lastLogIndex = getLastLogIndex();
        if (index == 0 || index < firstLogIndex || index > lastLogIndex) {
            LOG.debug("index out of range, index={}, firstLogIndex={}, lastLogIndex={}",
                    index, firstLogIndex, lastLogIndex);
            return null;
        }
        if (startLogIndexSegmentMap.size() == 0) {
            return null;
        }
        Segment segment = startLogIndexSegmentMap.floorEntry(index).getValue();
        return segment.getEntry(index);
    }

    public long getEntryTerm(long index) {
        RaftProto.LogEntry entry = getEntry(index);
        if (entry == null) {
            return 0;
        }
        return entry.getTerm();
    }

    public long getFirstLogIndex() {
        return metaData.getFirstLogIndex();
    }

    public long getLastLogIndex() {
        // there are 2 scenarios where segment is empty:
        // 1. first time of initialization, firstLogIndex = 1, lastLogIndex = 0
        // 2. just finished a snapshot, the log has just been cleaned, firstLogIndex =
        // snapshotIndex + 1, lastLogIndex = snapshotIndex
        if (startLogIndexSegmentMap.size() == 0) {
            return getFirstLogIndex() - 1;
        }
        Segment lastSegment = startLogIndexSegmentMap.lastEntry().getValue();
        return lastSegment.getEndIndex();
    }

    public long append(List<RaftProto.LogEntry> entries) {
        long newLastLogIndex = this.getLastLogIndex();
        for (RaftProto.LogEntry entry : entries) {
            newLastLogIndex++;
            int entrySize = entry.getSerializedSize();
            int segmentSize = startLogIndexSegmentMap.size();
            boolean isNeedNewSegmentFile = false;
            try {
                if (segmentSize == 0) {
                    isNeedNewSegmentFile = true;
                } else {
                    Segment segment = startLogIndexSegmentMap.lastEntry().getValue();
                    if (!segment.isCanWrite()) {
                        isNeedNewSegmentFile = true;
                    } else if (segment.getFileSize() + entrySize >= maxSegmentFileSize) {
                        isNeedNewSegmentFile = true;
                        // last file of the segment closes and changes name
                        segment.getRandomAccessFile().close();
                        segment.setCanWrite(false);
                        String newFileName = String.format("%020d-%020d",
                                segment.getStartIndex(), segment.getEndIndex());
                        String newFullFileName = logDataDir + File.separator + newFileName;
                        File newFile = new File(newFullFileName);
                        String oldFullFileName = logDataDir + File.separator + segment.getFileName();
                        File oldFile = new File(oldFullFileName);
                        FileUtils.moveFile(oldFile, newFile);
                        segment.setFileName(newFileName);
                        segment.setRandomAccessFile(RaftFileUtils.openFile(logDataDir, newFileName, "r"));
                    }
                }
                Segment newSegment;
                // create new segment file
                if (isNeedNewSegmentFile) {
                    // open new segment file
                    String newSegmentFileName = String.format("open-%d", newLastLogIndex);
                    String newFullFileName = logDataDir + File.separator + newSegmentFileName;
                    File newSegmentFile = new File(newFullFileName);
                    if (!newSegmentFile.exists()) {
                        newSegmentFile.createNewFile();
                    }
                    Segment segment = new Segment();
                    segment.setCanWrite(true);
                    segment.setStartIndex(newLastLogIndex);
                    segment.setEndIndex(0);
                    segment.setFileName(newSegmentFileName);
                    segment.setRandomAccessFile(RaftFileUtils.openFile(logDataDir, newSegmentFileName, "rw"));
                    newSegment = segment;
                } else {
                    newSegment = startLogIndexSegmentMap.lastEntry().getValue();
                }
                // write proto into segment
                if (entry.getIndex() == 0) {
                    entry = RaftProto.LogEntry.newBuilder(entry)
                            .setIndex(newLastLogIndex).build();
                }
                newSegment.setEndIndex(entry.getIndex());
                newSegment.getEntries().add(new Segment.Record(
                        newSegment.getRandomAccessFile().getFilePointer(), entry));
                RaftFileUtils.writeProtoToFile(newSegment.getRandomAccessFile(), entry);
                newSegment.setFileSize(newSegment.getRandomAccessFile().length());
                if (!startLogIndexSegmentMap.containsKey(newSegment.getStartIndex())) {
                    startLogIndexSegmentMap.put(newSegment.getStartIndex(), newSegment);
                }
                totalSize += entrySize;
            } catch (IOException ex) {
                throw new RuntimeException("append raft log exception, msg=" + ex.getMessage());
            }
        }
        return newLastLogIndex;
    }

    public void truncatePrefix(long newFirstIndex) {
        if (newFirstIndex <= getFirstLogIndex()) {
            return;
        }
        long oldFirstIndex = getFirstLogIndex();
        while (!startLogIndexSegmentMap.isEmpty()) {
            Segment segment = startLogIndexSegmentMap.firstEntry().getValue();
            if (segment.isCanWrite()) {
                break;
            }
            if (newFirstIndex > segment.getEndIndex()) {
                File oldFile = new File(logDataDir + File.separator + segment.getFileName());
                try {
                    RaftFileUtils.closeFile(segment.getRandomAccessFile());
                    FileUtils.forceDelete(oldFile);
                    totalSize -= segment.getFileSize();
                    startLogIndexSegmentMap.remove(segment.getStartIndex());
                } catch (Exception ex2) {
                    LOG.warn("delete file exception:", ex2);
                }
            } else {
                break;
            }
        }
        long newActualFirstIndex;
        if (startLogIndexSegmentMap.size() == 0) {
            newActualFirstIndex = newFirstIndex;
        } else {
            newActualFirstIndex = startLogIndexSegmentMap.firstKey();
        }
        updateMeta(null, null, newActualFirstIndex, null);
        LOG.info("Truncating log from old first index {} to new first index {}",
                oldFirstIndex, newActualFirstIndex);
    }

    public void truncateSuffix(long newEndIndex) {
        if (newEndIndex >= getLastLogIndex()) {
            return;
        }
        LOG.info("Truncating log from old end index {} to new end index {}",
                getLastLogIndex(), newEndIndex);
        while (!startLogIndexSegmentMap.isEmpty()) {
            Segment segment = startLogIndexSegmentMap.lastEntry().getValue();
            try {
                if (newEndIndex == segment.getEndIndex()) {
                    break;
                } else if (newEndIndex < segment.getStartIndex()) {
                    totalSize -= segment.getFileSize();
                    // delete file
                    segment.getRandomAccessFile().close();
                    String fullFileName = logDataDir + File.separator + segment.getFileName();
                    FileUtils.forceDelete(new File(fullFileName));
                    startLogIndexSegmentMap.remove(segment.getStartIndex());
                } else if (newEndIndex < segment.getEndIndex()) {
                    int i = (int) (newEndIndex + 1 - segment.getStartIndex());
                    segment.setEndIndex(newEndIndex);
                    long newFileSize = segment.getEntries().get(i).offset;
                    totalSize -= (segment.getFileSize() - newFileSize);
                    segment.setFileSize(newFileSize);
                    segment.getEntries().removeAll(
                            segment.getEntries().subList(i, segment.getEntries().size()));
                    FileChannel fileChannel = segment.getRandomAccessFile().getChannel();
                    fileChannel.truncate(segment.getFileSize());
                    fileChannel.close();
                    segment.getRandomAccessFile().close();
                    String oldFullFileName = logDataDir + File.separator + segment.getFileName();
                    String newFileName = String.format("%020d-%020d",
                            segment.getStartIndex(), segment.getEndIndex());
                    segment.setFileName(newFileName);
                    String newFullFileName = logDataDir + File.separator + segment.getFileName();
                    new File(oldFullFileName).renameTo(new File(newFullFileName));
                    segment.setRandomAccessFile(RaftFileUtils.openFile(logDataDir, segment.getFileName(), "rw"));
                }
            } catch (IOException ex) {
                LOG.warn("io exception, msg={}", ex.getMessage());
            }
        }
    }

    public void loadSegmentData(Segment segment) {
        try {
            RandomAccessFile randomAccessFile = segment.getRandomAccessFile();
            long totalLength = segment.getFileSize();
            long offset = 0;
            while (offset < totalLength) {
                RaftProto.LogEntry entry = RaftFileUtils.readProtoFromFile(
                        randomAccessFile, RaftProto.LogEntry.class);
                if (entry == null) {
                    throw new RuntimeException("read segment log failed");
                }
                Segment.Record record = new Segment.Record(offset, entry);
                segment.getEntries().add(record);
                offset = randomAccessFile.getFilePointer();
            }
            totalSize += totalLength;
        } catch (Exception ex) {
            LOG.error("read segment meet exception, msg={}", ex.getMessage());
            throw new RuntimeException("file not found");
        }

        int entrySize = segment.getEntries().size();
        if (entrySize > 0) {
            segment.setStartIndex(segment.getEntries().get(0).entry.getIndex());
            segment.setEndIndex(segment.getEntries().get(entrySize - 1).entry.getIndex());
        }
    }

    public void readSegments() {
        try {
            List<String> fileNames = RaftFileUtils.getSortedFilesInDir(logDataDir, logDataDir);
            for (String fileName : fileNames) {
                String[] splitArray = fileName.split("-");
                if (splitArray.length != 2) {
                    LOG.warn("segment filename[{}] is not valid", fileName);
                    continue;
                }
                Segment segment = new Segment();
                segment.setFileName(fileName);
                if (splitArray[0].equals("open")) {
                    segment.setCanWrite(true);
                    segment.setStartIndex(Long.valueOf(splitArray[1]));
                    segment.setEndIndex(0);
                } else {
                    try {
                        segment.setCanWrite(false);
                        segment.setStartIndex(Long.parseLong(splitArray[0]));
                        segment.setEndIndex(Long.parseLong(splitArray[1]));
                    } catch (NumberFormatException ex) {
                        LOG.warn("segment filename[{}] is not valid", fileName);
                        continue;
                    }
                }
                segment.setRandomAccessFile(RaftFileUtils.openFile(logDataDir, fileName, "rw"));
                segment.setFileSize(segment.getRandomAccessFile().length());
                startLogIndexSegmentMap.put(segment.getStartIndex(), segment);
            }
        } catch (IOException ioException) {
            LOG.warn("readSegments exception:", ioException);
            throw new RuntimeException("open segment file error");
        }
    }

    public RaftProto.LogMetaData readMeta() {
        String fileName = logDir + File.separator + "metadata";
        File file = new File(fileName);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            RaftProto.LogMetaData metadata = RaftFileUtils.readProtoFromFile(
                    randomAccessFile, RaftProto.LogMetaData.class);
            return metadata;
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", fileName);
            return null;
        }
    }

    /**
     * update raft log meta data，
     * including commitIndex
     * 
     * @param currentTerm
     * @param votedFor
     * @param firstLogIndex
     * @param commitIndex
     */
    public void updateMeta(Long currentTerm, Integer votedFor, Long firstLogIndex, Long commitIndex) {
        RaftProto.LogMetaData.Builder builder = RaftProto.LogMetaData.newBuilder(this.metaData);
        if (currentTerm != null) {
            builder.setCurrentTerm(currentTerm);
        }
        if (votedFor != null) {
            builder.setVotedFor(votedFor);
        }
        if (firstLogIndex != null) {
            builder.setFirstLogIndex(firstLogIndex);
        }
        if (commitIndex != null) {
            builder.setCommitIndex(commitIndex);
        }
        this.metaData = builder.build();

        String fileName = logDir + File.separator + "metadata";
        File file = new File(fileName);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            RaftFileUtils.writeProtoToFile(randomAccessFile, metaData);
            LOG.info("new segment meta info, currentTerm={}, votedFor={}, firstLogIndex={}",
                    metaData.getCurrentTerm(), metaData.getVotedFor(), metaData.getFirstLogIndex());
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", fileName);
        }
    }

    public RaftProto.LogMetaData getMeta() {
        return metaData;
    }

    public long getTotalSize() {
        return totalSize;
    }

}
