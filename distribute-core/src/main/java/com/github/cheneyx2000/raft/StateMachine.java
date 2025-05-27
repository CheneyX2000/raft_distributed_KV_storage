package com.github.cheneyx2000.raft;

public interface StateMachine {
    /**
     * take a snapshot to the data in the state machine
     * regularly called by every node locally
     * 
     * @param snapshotDir           old snapshot dir
     * @param tmpSnapshotDataDir    new snapshot data dir
     * @param raftNode              Raft node
     * @param localLastAppliedIndex the max-index log entry that has been copied to
     *                              the state machine
     */
    void writeSnap(String snapshotDir, String tmpSnapshotDataDir, RaftNode raftNode, long localLastAppliedIndex);

    /**
     * read snapshot to state machine,
     * called when the node is initialized
     * 
     * @param snapshotDir snapshot data dir
     */
    void readSnap(String snapshotDir);

    /**
     * apply data to the state machine
     * 
     * @param dataBytes data bytes
     */
    void applyData(byte[] dataBytes);

    /**
     * read data from state machine
     * 
     * @param dataBytes data bytes of the Key
     * @return data bytes of the Value
     */
    byte[] get(byte[] dataBytes);
}
