package com.commonbattle.cluster;

/**
 * 跨服网络中允许建立的服务类型链路。
 */
public record ClusterLink(ServiceKind from, ServiceKind to) {
}
