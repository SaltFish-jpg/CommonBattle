package com.commonbattle.game.agent;

/**
 * 可被健康探针读取的业务 Agent 通信视图。
 */
@FunctionalInterface
public interface BusinessAgentMessageView {
    BusinessAgentMessageStats stats();
}
