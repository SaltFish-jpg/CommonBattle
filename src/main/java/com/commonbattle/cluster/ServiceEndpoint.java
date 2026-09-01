package com.commonbattle.cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * 服务对外暴露的网络地址。
 * Netty 传输层用它建立连接，注册中心只负责保存和广播。
 */
public record ServiceEndpoint(String host, int port) implements Serializable {
    public ServiceEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Service host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Service port must be in 1-65535");
        }
    }
}
