package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * 入站消息准入控制器。
 * Netty 入站、RPC 回包和本服业务入口都可在投递邮箱前调用它，避免邮箱容量成为唯一保护。
 */
public interface InboundAdmissionController {
    AdmissionDecision admit(AgentIdentity target, String operation);
}
