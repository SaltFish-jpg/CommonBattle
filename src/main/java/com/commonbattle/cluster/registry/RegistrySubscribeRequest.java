package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

/**
 * 服务向中心订阅某类服务变化。
 */
public record RegistrySubscribeRequest(ServiceId subscriber, ServiceKind kind) {
}
