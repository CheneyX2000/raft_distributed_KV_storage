package com.github.cheneyx2000.raft.service;

import com.github.cheneyx2000.raft.proto.RaftProto;

public interface RaftClientService {

    /**
     * get the node info of the leader of the raft cluster
     * 
     * @param request request
     * @return leader node
     */
    RaftProto.GetLeaderResponse getNowLeader(RaftProto.GetLeaderRequest request);

    /**
     * get all node's info of the raft cluster
     * 
     * @param request request
     * @return the addresses of the each nodes of the raft cluster, and the
     *         leader-follower relationship
     */
    RaftProto.GetConfigurationResponse getConfig(RaftProto.GetConfigurationRequest request);

    /**
     * add new node to the raft cluster
     * 
     * @param request the info of the new node
     * @return operation successful or not
     */
    RaftProto.AddPeersResponse addStoragePeers(RaftProto.AddPeersRequest request);

    /**
     * delete node from raft cluster
     * 
     * @param request request
     * @return operation successful or not
     */
    RaftProto.RemovePeersResponse removeStoragePeers(RaftProto.RemovePeersRequest request);
}
