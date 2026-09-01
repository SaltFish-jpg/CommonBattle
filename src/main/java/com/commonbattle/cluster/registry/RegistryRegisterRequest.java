package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;

/**
 * 服务向中心注册自身描述。
 */
public record RegistryRegisterRequest(ServiceDescriptor service) {
}
