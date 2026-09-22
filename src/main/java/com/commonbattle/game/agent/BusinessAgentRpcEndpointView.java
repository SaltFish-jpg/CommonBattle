package com.commonbattle.game.agent;

/**
 * 可被健康探针读取的业务 Agent RPC 端点视图。
 */
@FunctionalInterface
public interface BusinessAgentRpcEndpointView {
    BusinessAgentRpcEndpointStats stats();
}
