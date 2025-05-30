package com.github.cheneyx2000.raft.storage;

import com.github.cheneyx2000.raft.proto.RaftProto;
import com.github.cheneyx2000.raft.util.RaftFileUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Snapshot {

    public class SnapshotDataFile {
        public String fileName;
        public RandomAccessFile randomAccessFile;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class);
    private String snapshotDir;
    private RaftProto.SnapshotMetaData metaData;
    // to indicate if or not is the node installing a snapshot.
    // a snapshot is installed from leader to follower, both leader and follower
    // would be in 'installSnapshot' state
    private AtomicBoolean isInstallSnapshot = new AtomicBoolean(false);
    // to indicate if the node itself is installing snapshot to its state machine
    private AtomicBoolean isTakeSnapshot = new AtomicBoolean(false);
    private Lock lock = new ReentrantLock();

    public Snapshot(String raftDataDir) {
        this.snapshotDir = raftDataDir + File.separator + "snapshot";
        String snapshotDataDir = snapshotDir + File.separator + "data";
        File file = new File(snapshotDataDir);
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public void reload() {
        metaData = this.readMeta();
        if (metaData == null) {
            metaData = RaftProto.SnapshotMetaData.newBuilder().build();
        }
    }

    /**
     * Open the files under the dir snapshot data
     * if is soft link, need to open the actual file handle
     * 
     * @return file name and file handle map
     */
    public TreeMap<String, SnapshotDataFile> openSnapshotFiles() {
        TreeMap<String, SnapshotDataFile> snapshotDataFileMap = new TreeMap<>();
        String snapshotDataDir = snapshotDir + File.separator + "data";
        try {
            Path snapshotDataPath = FileSystems.getDefault().getPath(snapshotDataDir);
            snapshotDataPath = snapshotDataPath.toRealPath();
            snapshotDataDir = snapshotDataPath.toString();
            List<String> fileNames = RaftFileUtils.getSortedFilesInDir(snapshotDataDir, snapshotDataDir);
            for (String fileName : fileNames) {
                RandomAccessFile randomAccessFile = RaftFileUtils.openFile(snapshotDataDir, fileName, "r");
                SnapshotDataFile snapshotFile = new SnapshotDataFile();
                snapshotFile.fileName = fileName;
                snapshotFile.randomAccessFile = randomAccessFile;
                snapshotDataFileMap.put(fileName, snapshotFile);
            }
        } catch (IOException ex) {
            LOG.warn("readSnapshotDataFiles exception:", ex);
            throw new RuntimeException(ex);
        }
        return snapshotDataFileMap;
    }

    public void closeSnapshotFiles(TreeMap<String, SnapshotDataFile> snapshotDataFileMap) {
        for (Map.Entry<String, SnapshotDataFile> entry : snapshotDataFileMap.entrySet()) {
            try {
                entry.getValue().randomAccessFile.close();
            } catch (IOException ex) {
                LOG.warn("close snapshot files exception:", ex);
            }
        }
    }

    public RaftProto.SnapshotMetaData readMeta() {
        String fileName = snapshotDir + File.separator + "metadata";
        File file = new File(fileName);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            RaftProto.SnapshotMetaData metadata = RaftFileUtils.readProtoFromFile(
                    randomAccessFile, RaftProto.SnapshotMetaData.class);
            return metadata;
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", fileName);
            return null;
        }
    }

    public void updateMeta(String dir,
            Long lastIncludedIndex,
            Long lastIncludedTerm,
            RaftProto.Configuration configuration) {
        RaftProto.SnapshotMetaData snapshotMetaData = RaftProto.SnapshotMetaData.newBuilder()
                .setLastIncludedIndex(lastIncludedIndex)
                .setLastIncludedTerm(lastIncludedTerm)
                .setConfiguration(configuration).build();
        String snapshotMetaFile = dir + File.separator + "metadata";
        RandomAccessFile randomAccessFile = null;
        try {
            File dirFile = new File(dir);
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }

            File file = new File(snapshotMetaFile);
            if (file.exists()) {
                FileUtils.forceDelete(file);
            }
            file.createNewFile();
            randomAccessFile = new RandomAccessFile(file, "rw");
            RaftFileUtils.writeProtoToFile(randomAccessFile, snapshotMetaData);
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", snapshotMetaFile);
        } finally {
            RaftFileUtils.closeFile(randomAccessFile);
        }
    }

    public RaftProto.SnapshotMetaData getMeta() {
        return metaData;
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    public AtomicBoolean getIsinstallSnap() {
        return isInstallSnapshot;
    }

    public AtomicBoolean getIsTakeSnap() {
        return isTakeSnapshot;
    }

    public Lock getLock() {
        return lock;
    }
}
