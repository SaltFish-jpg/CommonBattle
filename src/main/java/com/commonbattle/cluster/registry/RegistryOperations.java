package com.commonbattle.cluster.registry;

/**
 * 中心注册服务的 RPC 操作名。
 */
public final class RegistryOperations {
    public static final String REGISTER = "registry.register";
    public static final String UNREGISTER = "registry.unregister";
    public static final String LIST = "registry.list";
    public static final String SUBSCRIBE = "registry.subscribe";
    public static final String EVENT = "registry.event";
    public static final String ACK = "registry.ack";

    private RegistryOperations() {
    }
}
