package com.commonbattle.core;

import java.util.Objects;
import java.util.UUID;

public record EntityId(String value) {
    public EntityId {
        Objects.requireNonNull(value, "value");
    }

    public static EntityId random() {
        return new EntityId(UUID.randomUUID().toString());
    }
}
