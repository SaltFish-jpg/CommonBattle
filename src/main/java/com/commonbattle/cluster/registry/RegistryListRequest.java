package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceKind;

/**
 * 查询某类服务的当前快照。
 */
public record RegistryListRequest(ServiceKind kind) {
}
