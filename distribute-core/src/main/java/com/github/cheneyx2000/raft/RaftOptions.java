package com.github.cheneyx2000.raft;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RaftOptions {

    // A follower would become a candidate if it doesn't receive any message
    // from the leader in electionTimeoutMs milliseconds
    private int electionTimeoutMilliseconds = 5000;

    // A leader sends RPCs at least this often, even if there is no data to send
    private int heartbeatPeriodMilliseconds = 500;

    // the time period (in seconds) for taking each snapshot
    private int snapshotPeriodSeconds = 3600;
    // only when log entry size reaches snapshotMinLogSize, then take a snapshot
    private int snapshotMinLogSize = 100 * 1024 * 1024;
    private int maxSnapshotBytesPerRequest = 500 * 1024; // 500k

    private int maxLogEntriesPerRequest = 5000;

    // size of a single segment file, default to be 100m
    private int maxSegmentFileSize = 100 * 1000 * 1000;

    // when follower and leader differs within catchupMargin, then can a follower
    // become candidate and provide services
    private long catchupMargin = 500;

    // the max await timeout for a replicate(in millisecond)
    private long maxAwaitTimeout = 1000;

    // the threadpool size for a node to synchronize, elect and etc.
    private int raftConsensusThreadNum = 20;

    // whether write async;
    // true to indicate that the leader save and return, send async message to the
    // followers;
    // false to indicate that the leader synchronize to a qurum of followers before
    // return
    private boolean asyncWrite = false;

    // the parent dir of logs and snapshots in absolute path
    private String dataDir = System.getProperty("com.github.raftimpl.raft.data.dir");
}
