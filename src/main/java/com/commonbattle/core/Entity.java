package com.commonbattle.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Generic battlefield object.
 * A unit, card, summon, building, projectile, or other gameplay object can all be modeled as an entity.
 */
public final class Entity {
    private final EntityId id;
    private final String type;
    private final Map<Class<? extends Component>, Component> components = new LinkedHashMap<>();

    Entity(EntityId id, String type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    public EntityId id() {
        return id;
    }

    public String type() {
        return type;
    }

    public <T extends Component> Entity add(T component) {
        components.put(component.getClass(), component);
        return this;
    }

    public <T extends Component> Optional<T> find(Class<T> componentType) {
        return Optional.ofNullable(componentType.cast(components.get(componentType)));
    }

    public <T extends Component> T require(Class<T> componentType) {
        return find(componentType)
                .orElseThrow(() -> new IllegalStateException("Entity %s missing component %s"
                        .formatted(id.value(), componentType.getSimpleName())));
    }
}
