package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;

/**
 * 服务从中心注销自身描述。
 */
public record RegistryUnregisterRequest(ServiceId serviceId) {
}
