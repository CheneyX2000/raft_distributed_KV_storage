package com.github.cheneyx2000.raft.service;

import com.baidu.brpc.client.RpcCallback;
import com.github.cheneyx2000.raft.proto.RaftProto;

import java.util.concurrent.Future;

public interface RaftClientServiceAsync extends RaftClientService {

        Future<RaftProto.GetLeaderResponse> getNowLeader(
                        RaftProto.GetLeaderRequest request,
                        RpcCallback<RaftProto.GetLeaderResponse> callback);

        Future<RaftProto.GetConfigurationResponse> getConfig(
                        RaftProto.GetConfigurationRequest request,
                        RpcCallback<RaftProto.GetConfigurationResponse> callback);

        Future<RaftProto.AddPeersResponse> addStoragePeers(
                        RaftProto.AddPeersRequest request,
                        RpcCallback<RaftProto.AddPeersResponse> callback);

        Future<RaftProto.RemovePeersResponse> removeStoragePeers(
                        RaftProto.RemovePeersRequest request,
                        RpcCallback<RaftProto.RemovePeersResponse> callback);
}
