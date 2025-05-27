package com.github.cheneyx2000.raft;

import com.baidu.brpc.client.BrpcProxy;
import com.baidu.brpc.client.RpcClient;
import com.baidu.brpc.client.instance.Endpoint;
import com.github.cheneyx2000.raft.proto.RaftProto;
import com.github.cheneyx2000.raft.service.RaftConsensusService;
import com.github.cheneyx2000.raft.service.RaftConsensusServiceAsync;

public class Peer {
    private RaftProto.Server server;
    private RpcClient rpcClient;
    private RaftConsensusServiceAsync raftConsensusServiceAsync;
    // the index value of the next log entry to send to followers,
    // only effective to a leader
    private long nextIndex;
    // the highest index of the copied log
    private long matchIndex;
    private volatile Boolean voteGranted;
    private volatile boolean isCatchUp;

    public Peer(RaftProto.Server server) {
        this.server = server;
        this.rpcClient = new RpcClient(new Endpoint(
                server.getEndpoint().getHost(),
                server.getEndpoint().getPort()));
        raftConsensusServiceAsync = BrpcProxy.getProxy(rpcClient, RaftConsensusServiceAsync.class);
        isCatchUp = false;
    }

    public RaftProto.Server getStorageServer() {
        return server;
    }

    public RpcClient getRpcClient() {
        return rpcClient;
    }

    public RaftConsensusServiceAsync getRaftConsensusServiceAsync() {
        return raftConsensusServiceAsync;
    }

    public long getNextIndex() {
        return nextIndex;
    }

    public void setNextIndex(long nextIndex) {
        this.nextIndex = nextIndex;
    }

    public long getMatchIndex() {
        return matchIndex;
    }

    public void setMatchIndex(long matchIndex) {
        this.matchIndex = matchIndex;
    }

    public Boolean isVoteGranted() {
        return voteGranted;
    }

    public void setVoteGranted(Boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    public boolean isCatchUp() {
        return isCatchUp;
    }

    public void setCatchUp(boolean catchUp) {
        isCatchUp = catchUp;
    }
}
