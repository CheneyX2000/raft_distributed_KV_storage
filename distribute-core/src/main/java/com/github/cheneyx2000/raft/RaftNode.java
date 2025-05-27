package com.github.cheneyx2000.raft;

import com.baidu.brpc.client.RpcCallback;
import com.github.cheneyx2000.raft.proto.RaftProto;
import com.github.cheneyx2000.raft.storage.SegmentedLog;
import com.github.cheneyx2000.raft.util.ConfigurationUtils;
import com.google.protobuf.ByteString;
import com.github.cheneyx2000.raft.storage.Snapshot;
import com.google.protobuf.InvalidProtocolBufferException;
import com.googlecode.protobuf.format.JsonFormat;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * the class is a core class of raft, it has the following functionalities:
 * 1. save the core data of a raft node (e.g. state of the node, log, snapshot,
 * etc.),
 * 2. rpc-relavant functions of a raft node
 * 3. timer for a raft node: leader's heartbeat timer, elections timer (managing
 * election timeout)
 */
public class RaftNode {

    public enum NodeState {
        STATE_FOLLOWER,
        STATE_PRE_CANDIDATE,
        STATE_CANDIDATE,
        STATE_LEADER
    }

    private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);
    private static final JsonFormat jsonFormat = new JsonFormat();

    private RaftOptions RaftOptions;
    private RaftProto.Configuration configuration;
    private ConcurrentMap<Integer, Peer> peerMap = new ConcurrentHashMap<>();
    private RaftProto.Server localServer;
    private StateMachine stateMachine;
    private SegmentedLog raftLog;
    private Snapshot snapshot;

    private NodeState state = NodeState.STATE_FOLLOWER;
    // 服务器最后一次知道的任期号（初始化为 0，持续递增）
    private long currentTerm;
    // 在当前获得选票的候选人的Id
    private int votedFor;
    private int leaderId; // leader节点id
    // 已知的最大的已经被提交的日志条目的索引值
    private long commitIndex;
    // 最后被应用到状态机的日志条目索引值（初始化为 0，持续递增）
    private volatile long lastAppliedIndex;

    private Lock lock = new ReentrantLock();
    private Condition commitIndexCondition = lock.newCondition();
    private Condition catchUpCondition = lock.newCondition();

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture electionScheduledFuture;
    private ScheduledFuture heartbeatScheduledFuture;

    public RaftNode(RaftOptions RaftOptions,
            List<RaftProto.Server> servers,
            RaftProto.Server localServer,
            StateMachine stateMachine) {
        this.RaftOptions = RaftOptions;
        RaftProto.Configuration.Builder confBuilder = RaftProto.Configuration.newBuilder();
        for (RaftProto.Server server : servers) {
            confBuilder.addServers(server);
        }
        configuration = confBuilder.build();

        this.localServer = localServer;
        this.stateMachine = stateMachine;

        // load log and snapshot
        raftLog = new SegmentedLog(RaftOptions.getDataDir(), RaftOptions.getMaxSegmentFileSize());
        snapshot = new Snapshot(RaftOptions.getDataDir());
        snapshot.reload();

        currentTerm = raftLog.getMeta().getCurrentTerm();
        votedFor = raftLog.getMeta().getVotedFor();
        commitIndex = Math.max(snapshot.getMeta().getLastIncludedIndex(), raftLog.getMeta().getCommitIndex());
        // discard old log entries
        if (snapshot.getMeta().getLastIncludedIndex() > 0
                && raftLog.getFirstLogIndex() <= snapshot.getMeta().getLastIncludedIndex()) {
            raftLog.truncatePrefix(snapshot.getMeta().getLastIncludedIndex() + 1);
        }
        // apply state machine
        RaftProto.Configuration snapshotConfiguration = snapshot.getMeta().getConfiguration();
        if (snapshotConfiguration.getServersCount() > 0) {
            configuration = snapshotConfiguration;
        }
        String snapshotDataDir = snapshot.getSnapshotDir() + File.separator + "data";
        stateMachine.readSnap(snapshotDataDir);
        for (long index = snapshot.getMeta().getLastIncludedIndex() + 1; index <= commitIndex; index++) {
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_DATA) {
                stateMachine.applyData(entry.getData().toByteArray());
            } else if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_CONFIGURATION) {
                applyConfig(entry);
            }
        }
        lastAppliedIndex = commitIndex;
    }

    public void init() {
        for (RaftProto.Server server : configuration.getServersList()) {
            if (!peerMap.containsKey(server.getServerId())
                    && server.getServerId() != localServer.getServerId()) {
                Peer peer = new Peer(server);
                peer.setNextIndex(raftLog.getLastLogIndex() + 1);
                peerMap.put(server.getServerId(), peer);
            }
        }

        // init thread pool
        executorService = new ThreadPoolExecutor(
                RaftOptions.getRaftConsensusThreadNum(),
                RaftOptions.getRaftConsensusThreadNum(),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        scheduledExecutorService = Executors.newScheduledThreadPool(2);
        scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                takeSnap();
            }
        }, RaftOptions.getSnapshotPeriodSeconds(), RaftOptions.getSnapshotPeriodSeconds(), TimeUnit.SECONDS);
        // start election
        resetElectionTimer();
    }

    // client set command
    public boolean replicate(byte[] data, RaftProto.EntryType entryType) {
        lock.lock();
        long newLastLogIndex = 0;
        try {
            if (state != NodeState.STATE_LEADER) {
                LOG.debug("I'm not the leader");
                return false;
            }
            RaftProto.LogEntry logEntry = RaftProto.LogEntry.newBuilder()
                    .setTerm(currentTerm)
                    .setType(entryType)
                    .setData(ByteString.copyFrom(data)).build();
            List<RaftProto.LogEntry> entries = new ArrayList<>();
            entries.add(logEntry);
            newLastLogIndex = raftLog.append(entries);
            // raftLog.updateMeta(currentTerm, null, raftLog.getFirstLogIndex());

            for (RaftProto.Server server : configuration.getServersList()) {
                final Peer peer = peerMap.get(server.getServerId());
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        appendEntries(peer);
                    }
                });
            }

            if (RaftOptions.isAsyncWrite()) {
                // return after the leader write successfully
                return true;
            }

            // sync wait commitIndex >= newLastLogIndex
            long startTime = System.currentTimeMillis();
            while (lastAppliedIndex < newLastLogIndex) {
                if (System.currentTimeMillis() - startTime >= RaftOptions.getMaxAwaitTimeout()) {
                    break;
                }
                commitIndexCondition.await(RaftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            lock.unlock();
        }
        LOG.debug("lastAppliedIndex={} newLastLogIndex={}", lastAppliedIndex, newLastLogIndex);
        if (lastAppliedIndex < newLastLogIndex) {
            return false;
        }
        return true;
    }

    public boolean appendEntries(Peer peer) {
        RaftProto.AppendEntriesRequest.Builder requestBuilder = RaftProto.AppendEntriesRequest.newBuilder();
        long prevLogIndex;
        long numEntries;

        boolean isNeedInstallSnapshot = false;
        lock.lock();
        try {
            long firstLogIndex = raftLog.getFirstLogIndex();
            if (peer.getNextIndex() < firstLogIndex) {
                isNeedInstallSnapshot = true;
            }
        } finally {
            lock.unlock();
        }

        LOG.debug("is need snapshot={}, peer={}", isNeedInstallSnapshot, peer.getStorageServer().getServerId());
        if (isNeedInstallSnapshot) {
            if (!installSnap(peer)) {
                return false;
            }
        }

        long lastSnapshotIndex;
        long lastSnapshotTerm;
        snapshot.getLock().lock();
        try {
            lastSnapshotIndex = snapshot.getMeta().getLastIncludedIndex();
            lastSnapshotTerm = snapshot.getMeta().getLastIncludedTerm();
        } finally {
            snapshot.getLock().unlock();
        }

        lock.lock();
        try {
            long firstLogIndex = raftLog.getFirstLogIndex();
            Validate.isTrue(peer.getNextIndex() >= firstLogIndex);
            prevLogIndex = peer.getNextIndex() - 1;
            long prevLogTerm;
            if (prevLogIndex == 0) {
                prevLogTerm = 0;
            } else if (prevLogIndex == lastSnapshotIndex) {
                prevLogTerm = lastSnapshotTerm;
            } else {
                prevLogTerm = raftLog.getEntryTerm(prevLogIndex);
            }
            requestBuilder.setServerId(localServer.getServerId());
            requestBuilder.setTerm(currentTerm);
            requestBuilder.setPrevLogTerm(prevLogTerm);
            requestBuilder.setPrevLogIndex(prevLogIndex);
            numEntries = packEntries(peer.getNextIndex(), requestBuilder);
            requestBuilder.setCommitIndex(Math.min(commitIndex, prevLogIndex + numEntries));
        } finally {
            lock.unlock();
        }

        RaftProto.AppendEntriesRequest request = requestBuilder.build();
        RaftProto.AppendEntriesResponse response = peer.getRaftConsensusServiceAsync().appendEntries(request);

        lock.lock();
        try {
            if (response == null) {
                LOG.warn("appendEntries with peer[{}:{}] failed",
                        peer.getStorageServer().getEndpoint().getHost(),
                        peer.getStorageServer().getEndpoint().getPort());
                if (!ConfigurationUtils.containsStorageServer(configuration, peer.getStorageServer().getServerId())) {
                    peerMap.remove(peer.getStorageServer().getServerId());
                    peer.getRpcClient().stop();
                }
                return false;
            }
            LOG.info("AppendEntries response[{}] from server {} " +
                    "in term {} (my term is {})",
                    response.getResCode(), peer.getStorageServer().getServerId(),
                    response.getTerm(), currentTerm);

            if (response.getTerm() > currentTerm) {
                stepDown(response.getTerm());
            } else {
                if (response.getResCode() == RaftProto.ResCode.RES_CODE_SUCCESS) {
                    peer.setMatchIndex(prevLogIndex + numEntries);
                    peer.setNextIndex(peer.getMatchIndex() + 1);
                    if (ConfigurationUtils.containsStorageServer(configuration,
                            peer.getStorageServer().getServerId())) {
                        advanceCommitIndex();
                    } else {
                        if (raftLog.getLastLogIndex() - peer.getMatchIndex() <= RaftOptions.getCatchupMargin()) {
                            LOG.debug("peer catch up the leader");
                            peer.setCatchUp(true);
                            // signal the caller thread
                            catchUpCondition.signalAll();
                        }
                    }
                } else {
                    peer.setNextIndex(response.getLastLogIndex() + 1);
                }
            }
        } finally {
            lock.unlock();
        }

        return true;
    }

    // in lock
    public void stepDown(long newTerm) {
        if (currentTerm > newTerm) {
            LOG.error("can't be happened");
            return;
        }
        if (currentTerm < newTerm) {
            currentTerm = newTerm;
            leaderId = 0;
            votedFor = 0;
            raftLog.updateMeta(currentTerm, votedFor, null, null);
        }
        state = NodeState.STATE_FOLLOWER;
        // stop heartbeat
        if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
            heartbeatScheduledFuture.cancel(true);
        }
        resetElectionTimer();
    }

    public void takeSnap() {
        if (snapshot.getIsinstallSnap().get()) {
            LOG.info("already in install snapshot, ignore take snapshot");
            return;
        }

        snapshot.getIsTakeSnap().compareAndSet(false, true);
        try {
            long localLastAppliedIndex;
            long lastAppliedTerm = 0;
            RaftProto.Configuration.Builder localConfiguration = RaftProto.Configuration.newBuilder();
            lock.lock();
            try {
                if (raftLog.getTotalSize() < RaftOptions.getSnapshotMinLogSize()) {
                    return;
                }
                if (lastAppliedIndex <= snapshot.getMeta().getLastIncludedIndex()) {
                    return;
                }
                localLastAppliedIndex = lastAppliedIndex;
                if (lastAppliedIndex >= raftLog.getFirstLogIndex()
                        && lastAppliedIndex <= raftLog.getLastLogIndex()) {
                    lastAppliedTerm = raftLog.getEntryTerm(lastAppliedIndex);
                }
                localConfiguration.mergeFrom(configuration);
            } finally {
                lock.unlock();
            }

            boolean success = false;
            snapshot.getLock().lock();
            try {
                LOG.info("start taking snapshot");
                // take snapshot
                String tmpSnapshotDir = snapshot.getSnapshotDir() + ".tmp";
                snapshot.updateMeta(tmpSnapshotDir, localLastAppliedIndex,
                        lastAppliedTerm, localConfiguration.build());
                String tmpSnapshotDataDir = tmpSnapshotDir + File.separator + "data";
                stateMachine.writeSnap(snapshot.getSnapshotDir(), tmpSnapshotDataDir, this, localLastAppliedIndex);
                // rename tmp snapshot dir to snapshot dir
                try {
                    File snapshotDirFile = new File(snapshot.getSnapshotDir());
                    if (snapshotDirFile.exists()) {
                        FileUtils.deleteDirectory(snapshotDirFile);
                    }
                    FileUtils.moveDirectory(new File(tmpSnapshotDir),
                            new File(snapshot.getSnapshotDir()));
                    LOG.info("end taking snapshot, result=success");
                    success = true;
                } catch (IOException ex) {
                    LOG.warn("move direct failed when taking snapshot, msg={}", ex.getMessage());
                }
            } finally {
                snapshot.getLock().unlock();
            }

            if (success) {
                // reload snapshot
                long lastSnapshotIndex = 0;
                snapshot.getLock().lock();
                try {
                    snapshot.reload();
                    lastSnapshotIndex = snapshot.getMeta().getLastIncludedIndex();
                } finally {
                    snapshot.getLock().unlock();
                }

                // discard old log entries
                lock.lock();
                try {
                    if (lastSnapshotIndex > 0 && raftLog.getFirstLogIndex() <= lastSnapshotIndex) {
                        raftLog.truncatePrefix(lastSnapshotIndex + 1);
                    }
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            snapshot.getIsTakeSnap().compareAndSet(true, false);
        }
    }

    // in lock
    public void applyConfig(RaftProto.LogEntry entry) {
        try {
            RaftProto.Configuration newConfiguration = RaftProto.Configuration.parseFrom(entry.getData().toByteArray());
            configuration = newConfiguration;
            // update peerMap
            for (RaftProto.Server server : newConfiguration.getServersList()) {
                if (!peerMap.containsKey(server.getServerId())
                        && server.getServerId() != localServer.getServerId()) {
                    Peer peer = new Peer(server);
                    peer.setNextIndex(raftLog.getLastLogIndex() + 1);
                    peerMap.put(server.getServerId(), peer);
                }
            }
            LOG.info("new conf is {}, leaderId={}", jsonFormat.printToString(newConfiguration), leaderId);
        } catch (InvalidProtocolBufferException ex) {
            ex.printStackTrace();
        }
    }

    public long getLastLogTerm() {
        long lastLogIndex = raftLog.getLastLogIndex();
        if (lastLogIndex >= raftLog.getFirstLogIndex()) {
            return raftLog.getEntryTerm(lastLogIndex);
        } else {
            // if log is empty，lastLogIndex == lastSnapshotIndex
            return snapshot.getMeta().getLastIncludedTerm();
        }
    }

    /**
     * election timer
     */
    private void resetElectionTimer() {
        if (electionScheduledFuture != null && !electionScheduledFuture.isDone()) {
            electionScheduledFuture.cancel(true);
        }
        electionScheduledFuture = scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                startPreVote();
            }
        }, getElectionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private int getElectionTimeoutMs() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int randomElectionTimeout = RaftOptions.getElectionTimeoutMilliseconds()
                + random.nextInt(0, RaftOptions.getElectionTimeoutMilliseconds());
        LOG.debug("new election time is after {} ms", randomElectionTimeout);
        return randomElectionTimeout;
    }

    /**
     * client initiate pre-vote request
     * pre-vote/vote is a typical 2-phase design
     * it is designed in order to prevent a node continously increase term and
     * iniate election when it is offline
     * when the node is back online, it would cause a dramatic increase to the other
     * nodes' term, and cause terbulances in the _.
     */
    private void startPreVote() {
        lock.lock();
        try {
            if (!ConfigurationUtils.containsStorageServer(configuration, localServer.getServerId())) {
                resetElectionTimer();
                return;
            }
            LOG.info("Running pre-vote in term {}", currentTerm);
            state = NodeState.STATE_PRE_CANDIDATE;
        } finally {
            lock.unlock();
        }

        for (RaftProto.Server server : configuration.getServersList()) {
            if (server.getServerId() == localServer.getServerId()) {
                continue;
            }
            final Peer peer = peerMap.get(server.getServerId());
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    preVote(peer);
                }
            });
        }
        resetElectionTimer();
    }

    /**
     * client iniate voting, effective to candidates
     */
    private void startVote() {
        lock.lock();
        try {
            if (!ConfigurationUtils.containsStorageServer(configuration, localServer.getServerId())) {
                resetElectionTimer();
                return;
            }
            currentTerm++;
            LOG.info("Running for election in term {}", currentTerm);
            state = NodeState.STATE_CANDIDATE;
            leaderId = 0;
            votedFor = localServer.getServerId();
        } finally {
            lock.unlock();
        }

        for (RaftProto.Server server : configuration.getServersList()) {
            if (server.getServerId() == localServer.getServerId()) {
                continue;
            }
            final Peer peer = peerMap.get(server.getServerId());
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    requestVote(peer);
                }
            });
        }
    }

    /**
     * client initiate pre-vote request
     * 
     * @param peer nodes info in server
     */
    private void preVote(Peer peer) {
        LOG.info("begin pre vote request");
        RaftProto.VoteRequest.Builder requestBuilder = RaftProto.VoteRequest.newBuilder();
        lock.lock();
        try {
            peer.setVoteGranted(null);
            requestBuilder.setServerId(localServer.getServerId())
                    .setTerm(currentTerm)
                    .setLastLogIndex(raftLog.getLastLogIndex())
                    .setLastLogTerm(getLastLogTerm());
        } finally {
            lock.unlock();
        }

        RaftProto.VoteRequest request = requestBuilder.build();
        peer.getRaftConsensusServiceAsync().preVote(
                request, new PreVoteResponseCallback(peer, request));
    }

    /**
     * client iniate a vote request
     * 
     * @param peer nodes info in server
     */
    private void requestVote(Peer peer) {
        LOG.info("begin vote request");
        RaftProto.VoteRequest.Builder requestBuilder = RaftProto.VoteRequest.newBuilder();
        lock.lock();
        try {
            peer.setVoteGranted(null);
            requestBuilder.setServerId(localServer.getServerId())
                    .setTerm(currentTerm)
                    .setLastLogIndex(raftLog.getLastLogIndex())
                    .setLastLogTerm(getLastLogTerm());
        } finally {
            lock.unlock();
        }

        RaftProto.VoteRequest request = requestBuilder.build();
        peer.getRaftConsensusServiceAsync().requestVote(
                request, new VoteResponseCallback(peer, request));
    }

    private class PreVoteResponseCallback implements RpcCallback<RaftProto.VoteResponse> {
        private Peer peer;
        private RaftProto.VoteRequest request;

        public PreVoteResponseCallback(Peer peer, RaftProto.VoteRequest request) {
            this.peer = peer;
            this.request = request;
        }

        @Override
        public void success(RaftProto.VoteResponse response) {
            lock.lock();
            try {
                peer.setVoteGranted(response.getGranted());
                if (currentTerm != request.getTerm() || state != NodeState.STATE_PRE_CANDIDATE) {
                    LOG.info("ignore preVote RPC result");
                    return;
                }
                if (response.getTerm() > currentTerm) {
                    LOG.info("Received pre vote response from server {} " +
                            "in term {} (this server's term was {})",
                            peer.getStorageServer().getServerId(),
                            response.getTerm(),
                            currentTerm);
                    stepDown(response.getTerm());
                } else {
                    if (response.getGranted()) {
                        LOG.info("get pre vote granted from server {} for term {}",
                                peer.getStorageServer().getServerId(), currentTerm);
                        int voteGrantedNum = 1;
                        for (RaftProto.Server server : configuration.getServersList()) {
                            if (server.getServerId() == localServer.getServerId()) {
                                continue;
                            }
                            Peer peer1 = peerMap.get(server.getServerId());
                            if (peer1.isVoteGranted() != null && peer1.isVoteGranted() == true) {
                                voteGrantedNum += 1;
                            }
                        }
                        LOG.info("preVoteGrantedNum={}", voteGrantedNum);
                        if (voteGrantedNum > configuration.getServersCount() / 2) {
                            LOG.info("get majority pre vote, serverId={} when pre vote, start vote",
                                    localServer.getServerId());
                            startVote();
                        }
                    } else {
                        LOG.info("pre vote denied by server {} with term {}, my term is {}",
                                peer.getStorageServer().getServerId(), response.getTerm(), currentTerm);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void fail(Throwable e) {
            LOG.warn("pre vote with peer[{}:{}] failed",
                    peer.getStorageServer().getEndpoint().getHost(),
                    peer.getStorageServer().getEndpoint().getPort());
            peer.setVoteGranted(false);
        }
    }

    private class VoteResponseCallback implements RpcCallback<RaftProto.VoteResponse> {
        private Peer peer;
        private RaftProto.VoteRequest request;

        public VoteResponseCallback(Peer peer, RaftProto.VoteRequest request) {
            this.peer = peer;
            this.request = request;
        }

        @Override
        public void success(RaftProto.VoteResponse response) {
            lock.lock();
            try {
                peer.setVoteGranted(response.getGranted());
                if (currentTerm != request.getTerm() || state != NodeState.STATE_CANDIDATE) {
                    LOG.info("ignore requestVote RPC result");
                    return;
                }
                if (response.getTerm() > currentTerm) {
                    LOG.info("Received RequestVote response from server {} " +
                            "in term {} (this server's term was {})",
                            peer.getStorageServer().getServerId(),
                            response.getTerm(),
                            currentTerm);
                    stepDown(response.getTerm());
                } else {
                    if (response.getGranted()) {
                        LOG.info("Got vote from server {} for term {}",
                                peer.getStorageServer().getServerId(), currentTerm);
                        int voteGrantedNum = 0;
                        if (votedFor == localServer.getServerId()) {
                            voteGrantedNum += 1;
                        }
                        for (RaftProto.Server server : configuration.getServersList()) {
                            if (server.getServerId() == localServer.getServerId()) {
                                continue;
                            }
                            Peer peer1 = peerMap.get(server.getServerId());
                            if (peer1.isVoteGranted() != null && peer1.isVoteGranted() == true) {
                                voteGrantedNum += 1;
                            }
                        }
                        LOG.info("voteGrantedNum={}", voteGrantedNum);
                        if (voteGrantedNum > configuration.getServersCount() / 2) {
                            LOG.info("Got majority vote, serverId={} become leader", localServer.getServerId());
                            becomeLeader();
                        }
                    } else {
                        LOG.info("Vote denied by server {} with term {}, my term is {}",
                                peer.getStorageServer().getServerId(), response.getTerm(), currentTerm);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void fail(Throwable e) {
            LOG.warn("requestVote with peer[{}:{}] failed",
                    peer.getStorageServer().getEndpoint().getHost(),
                    peer.getStorageServer().getEndpoint().getPort());
            peer.setVoteGranted(false);
        }
    }

    // in lock
    private void becomeLeader() {
        state = NodeState.STATE_LEADER;
        leaderId = localServer.getServerId();
        // stop vote timer
        if (electionScheduledFuture != null && !electionScheduledFuture.isDone()) {
            electionScheduledFuture.cancel(true);
        }
        // start heartbeat timer
        startNewHeartbeat();
    }

    // heartbeat timer, append entries
    // in lock
    private void resetHeartbeatTimer() {
        if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
            heartbeatScheduledFuture.cancel(true);
        }
        heartbeatScheduledFuture = scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                startNewHeartbeat();
            }
        }, RaftOptions.getHeartbeatPeriodMilliseconds(), TimeUnit.MILLISECONDS);
    }

    // in lock, start heartbeat，effective to leader
    private void startNewHeartbeat() {
        LOG.debug("start new heartbeat, peers={}", peerMap.keySet());
        for (final Peer peer : peerMap.values()) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    appendEntries(peer);
                }
            });
        }
        resetHeartbeatTimer();
    }

    // in lock, for leader
    private void advanceCommitIndex() {
        // get quorum matchIndex
        int peerNum = configuration.getServersList().size();
        long[] matchIndexes = new long[peerNum];
        int i = 0;
        for (RaftProto.Server server : configuration.getServersList()) {
            if (server.getServerId() != localServer.getServerId()) {
                Peer peer = peerMap.get(server.getServerId());
                matchIndexes[i++] = peer.getMatchIndex();
            }
        }
        matchIndexes[i] = raftLog.getLastLogIndex();
        Arrays.sort(matchIndexes);
        long newCommitIndex = matchIndexes[peerNum / 2];
        LOG.debug("newCommitIndex={}, oldCommitIndex={}", newCommitIndex, commitIndex);
        if (raftLog.getEntryTerm(newCommitIndex) != currentTerm) {
            LOG.debug("newCommitIndexTerm={}, currentTerm={}",
                    raftLog.getEntryTerm(newCommitIndex), currentTerm);
            return;
        }

        if (commitIndex >= newCommitIndex) {
            return;
        }
        long oldCommitIndex = commitIndex;
        commitIndex = newCommitIndex;
        raftLog.updateMeta(currentTerm, null, raftLog.getFirstLogIndex(), commitIndex);
        // apply to state machine
        for (long index = oldCommitIndex + 1; index <= newCommitIndex; index++) {
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_DATA) {
                stateMachine.applyData(entry.getData().toByteArray());
            } else if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_CONFIGURATION) {
                applyConfig(entry);
            }
        }
        lastAppliedIndex = commitIndex;
        LOG.debug("commitIndex={} lastAppliedIndex={}", commitIndex, lastAppliedIndex);
        commitIndexCondition.signalAll();
    }

    // in lock
    private long packEntries(long nextIndex, RaftProto.AppendEntriesRequest.Builder requestBuilder) {
        long lastIndex = Math.min(raftLog.getLastLogIndex(),
                nextIndex + RaftOptions.getMaxLogEntriesPerRequest() - 1);
        for (long index = nextIndex; index <= lastIndex; index++) {
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            requestBuilder.addEntries(entry);
        }
        return lastIndex - nextIndex + 1;
    }

    private boolean installSnap(Peer peer) {
        if (snapshot.getIsTakeSnap().get()) {
            LOG.info("already in take snapshot, please send install snapshot request later");
            return false;
        }
        if (!snapshot.getIsinstallSnap().compareAndSet(false, true)) {
            LOG.info("already in install snapshot");
            return false;
        }

        LOG.info("begin send install snapshot request to server={}", peer.getStorageServer().getServerId());
        boolean isSuccess = true;
        TreeMap<String, Snapshot.SnapshotDataFile> snapshotDataFileMap = snapshot.openSnapshotFiles();
        LOG.info("total snapshot files={}", snapshotDataFileMap.keySet());
        try {
            boolean isLastRequest = false;
            String lastFileName = null;
            long lastOffset = 0;
            long lastLength = 0;
            while (!isLastRequest) {
                RaftProto.InstallSnapshotRequest request = buildInstallSnapshotRequest(snapshotDataFileMap,
                        lastFileName, lastOffset, lastLength);
                if (request == null) {
                    LOG.warn("snapshot request == null");
                    isSuccess = false;
                    break;
                }
                if (request.getIsLast()) {
                    isLastRequest = true;
                }
                LOG.info("install snapshot request, fileName={}, offset={}, size={}, isFirst={}, isLast={}",
                        request.getFileName(), request.getOffset(), request.getData().toByteArray().length,
                        request.getIsFirst(), request.getIsLast());
                RaftProto.InstallSnapshotResponse response = peer.getRaftConsensusServiceAsync().installSnap(request);
                if (response != null && response.getResCode() == RaftProto.ResCode.RES_CODE_SUCCESS) {
                    lastFileName = request.getFileName();
                    lastOffset = request.getOffset();
                    lastLength = request.getData().size();
                } else {
                    isSuccess = false;
                    break;
                }
            }

            if (isSuccess) {
                long lastIncludedIndexInSnapshot;
                snapshot.getLock().lock();
                try {
                    lastIncludedIndexInSnapshot = snapshot.getMeta().getLastIncludedIndex();
                } finally {
                    snapshot.getLock().unlock();
                }

                lock.lock();
                try {
                    peer.setNextIndex(lastIncludedIndexInSnapshot + 1);
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            snapshot.closeSnapshotFiles(snapshotDataFileMap);
            snapshot.getIsinstallSnap().compareAndSet(true, false);
        }
        LOG.info("end send install snapshot request to server={}, success={}",
                peer.getStorageServer().getServerId(), isSuccess);
        return isSuccess;
    }

    private RaftProto.InstallSnapshotRequest buildInstallSnapshotRequest(
            TreeMap<String, Snapshot.SnapshotDataFile> snapshotDataFileMap,
            String lastFileName, long lastOffset, long lastLength) {
        RaftProto.InstallSnapshotRequest.Builder requestBuilder = RaftProto.InstallSnapshotRequest.newBuilder();

        snapshot.getLock().lock();
        try {
            if (lastFileName == null) {
                lastFileName = snapshotDataFileMap.firstKey();
                lastOffset = 0;
                lastLength = 0;
            }
            Snapshot.SnapshotDataFile lastFile = snapshotDataFileMap.get(lastFileName);
            long lastFileLength = lastFile.randomAccessFile.length();
            String currentFileName = lastFileName;
            long currentOffset = lastOffset + lastLength;
            int currentDataSize = RaftOptions.getMaxSnapshotBytesPerRequest();
            Snapshot.SnapshotDataFile currentDataFile = lastFile;
            if (lastOffset + lastLength < lastFileLength) {
                if (lastOffset + lastLength + RaftOptions.getMaxSnapshotBytesPerRequest() > lastFileLength) {
                    currentDataSize = (int) (lastFileLength - (lastOffset + lastLength));
                }
            } else {
                Map.Entry<String, Snapshot.SnapshotDataFile> currentEntry = snapshotDataFileMap
                        .higherEntry(lastFileName);
                if (currentEntry == null) {
                    LOG.warn("reach the last file={}", lastFileName);
                    return null;
                }
                currentDataFile = currentEntry.getValue();
                currentFileName = currentEntry.getKey();
                currentOffset = 0;
                int currentFileLenght = (int) currentEntry.getValue().randomAccessFile.length();
                if (currentFileLenght < RaftOptions.getMaxSnapshotBytesPerRequest()) {
                    currentDataSize = currentFileLenght;
                }
            }
            byte[] currentData = new byte[currentDataSize];
            currentDataFile.randomAccessFile.seek(currentOffset);
            currentDataFile.randomAccessFile.read(currentData);
            requestBuilder.setData(ByteString.copyFrom(currentData));
            requestBuilder.setFileName(currentFileName);
            requestBuilder.setOffset(currentOffset);
            requestBuilder.setIsFirst(false);
            if (currentFileName.equals(snapshotDataFileMap.lastKey())
                    && currentOffset + currentDataSize >= currentDataFile.randomAccessFile.length()) {
                requestBuilder.setIsLast(true);
            } else {
                requestBuilder.setIsLast(false);
            }
            if (currentFileName.equals(snapshotDataFileMap.firstKey()) && currentOffset == 0) {
                requestBuilder.setIsFirst(true);
                requestBuilder.setSnapshotMetaData(snapshot.getMeta());
            } else {
                requestBuilder.setIsFirst(false);
            }
        } catch (Exception ex) {
            LOG.warn("meet exception:", ex);
            return null;
        } finally {
            snapshot.getLock().unlock();
        }

        lock.lock();
        try {
            requestBuilder.setTerm(currentTerm);
            requestBuilder.setServerId(localServer.getServerId());
        } finally {
            lock.unlock();
        }

        return requestBuilder.build();
    }

    public boolean waitUntilApplied() {
        final CountDownLatch cdl;
        long readIndex;
        lock.lock();
        try {
            // record the current commitIndex as readIndex
            // create CountDownLatch, its value to be the half of the number of Peers (take
            // ceiling, add Leader node itself will surpass quorum)
            readIndex = commitIndex;
            int peerNum = configuration.getServersList().size();
            cdl = new CountDownLatch((peerNum + 1) >> 1);

            // send heartbeat to all Followers, if get response, minus one from
            // CountDownLatch.
            LOG.debug("ensure leader, peers={}", peerMap.keySet());
            for (final Peer peer : peerMap.values()) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (appendEntries(peer)) {
                            cdl.countDown();
                        }
                    }
                });
            }
        } finally {
            lock.unlock();
        }

        // wait for CountDownLatch to be 0 or timeout
        try {
            if (cdl.await(RaftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS)) {
                lock.lock();
                try {
                    // if CountDownLatch to be 0 within the timeout, then we successfully confirm
                    // the current node to be Leader,
                    // wait for the log entries before readIndex to be applied to copy state machine
                    long startTime = System.currentTimeMillis();
                    while (lastAppliedIndex < readIndex
                            && System.currentTimeMillis() - startTime < RaftOptions.getMaxAwaitTimeout()) {
                        commitIndexCondition.await(RaftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS);
                    }
                    return lastAppliedIndex >= readIndex;
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException ignore) {
        }
        return false;
    }

    public boolean waitForLeaderCommitIndex() {
        long readIndex = -1;
        boolean callLeader = false;
        Peer leader = null;

        lock.lock();
        try {
            // record commitIndex as readIndex
            // if the current node is the Leader node, then directly get current
            // commitIndex, otherwise get commitIndex through RPC from Leader
            if (leaderId == localServer.getServerId()) {
                readIndex = commitIndex;
            } else {
                callLeader = true;
                leader = peerMap.get(leaderId);
            }
        } finally {
            lock.unlock();
        }

        if (callLeader && leader != null) {
            RaftProto.GetLeaderCommitIndexRequest request = RaftProto.GetLeaderCommitIndexRequest.newBuilder().build();
            RaftProto.GetLeaderCommitIndexResponse response = leader.getRaftConsensusServiceAsync()
                    .getLeaderCommitIndex(request);
            if (response == null) {
                LOG.warn("acquire commit index from leader[{}:{}] failed",
                        leader.getStorageServer().getEndpoint().getHost(),
                        leader.getStorageServer().getEndpoint().getPort());
                return false;
            }
            readIndex = response.getCommitIndex();
        }

        if (readIndex == -1) {
            return false;
        }

        lock.lock();
        try {
            // wait for the log entries before readIndex to be applied to copy state machine
            long startTime = System.currentTimeMillis();
            while (lastAppliedIndex < readIndex
                    && System.currentTimeMillis() - startTime < RaftOptions.getMaxAwaitTimeout()) {
                commitIndexCondition.await(RaftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS);
            }
            return lastAppliedIndex >= readIndex;
        } catch (InterruptedException ignore) {
        } finally {
            lock.unlock();
        }
        return false;
    }

    public Lock getLock() {
        return lock;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public int getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(int votedFor) {
        this.votedFor = votedFor;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public long getLastAppliedIndex() {
        return lastAppliedIndex;
    }

    public void setLastAppliedIndex(long lastAppliedIndex) {
        this.lastAppliedIndex = lastAppliedIndex;
    }

    public SegmentedLog getRaftLog() {
        return raftLog;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(int leaderId) {
        this.leaderId = leaderId;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public RaftProto.Configuration getConfig() {
        return configuration;
    }

    public void setConfiguration(RaftProto.Configuration configuration) {
        this.configuration = configuration;
    }

    public RaftProto.Server getLocalServer() {
        return localServer;
    }

    public NodeState getState() {
        return state;
    }

    public ConcurrentMap<Integer, Peer> getPeerMap() {
        return peerMap;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public Condition getCatchUpCondition() {
        return catchUpCondition;
    }

    public Condition getCommitIndexCondition() {
        return commitIndexCondition;
    }
}
