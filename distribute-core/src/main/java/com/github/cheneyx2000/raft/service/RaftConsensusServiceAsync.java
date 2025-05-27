package com.github.cheneyx2000.raft.service;

import com.baidu.brpc.client.RpcCallback;
import com.github.cheneyx2000.raft.proto.RaftProto;

import java.util.concurrent.Future;

public interface RaftConsensusServiceAsync extends RaftConsensusService {

        Future<RaftProto.VoteResponse> preVote(
                        RaftProto.VoteRequest request,
                        RpcCallback<RaftProto.VoteResponse> callback);

        Future<RaftProto.VoteResponse> requestVote(
                        RaftProto.VoteRequest request,
                        RpcCallback<RaftProto.VoteResponse> callback);

        Future<RaftProto.AppendEntriesResponse> appendEntries(
                        RaftProto.AppendEntriesRequest request,
                        RpcCallback<RaftProto.AppendEntriesResponse> callback);

        Future<RaftProto.InstallSnapshotResponse> installSnap(
                        RaftProto.InstallSnapshotRequest request,
                        RpcCallback<RaftProto.InstallSnapshotResponse> callback);
}
