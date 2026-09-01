package com.commonbattle.cluster;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 注册中心保存的服务描述。
 * metadata 用于声明 Scene 服模式、容量、分片数量等路由侧需要读取但核心框架不固定的属性。
 */
public record ServiceDescriptor(
        ServiceId id,
        ServiceEndpoint endpoint,
        Set<String> topics,
        Map<String, String> metadata
) implements Serializable {
    public ServiceDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpoint, "endpoint");
        topics = Set.copyOf(Objects.requireNonNull(topics, "topics"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public boolean supports(String topic) {
        return topics.contains(topic);
    }

    public String metadata(String key) {
        return metadata.get(key);
    }
}
