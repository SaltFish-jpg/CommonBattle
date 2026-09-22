package com.commonbattle.battle.state;



import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 可变的战斗快照。
 * 保存所有实体，以及回合顺序、波次状态、全局资源等战斗级组件。
 */
public final class BattleState {
    private final Map<EntityId, Entity> entities = new LinkedHashMap<>();
    private final Map<Class<? extends Component>, Component> globals = new LinkedHashMap<>();
    private long tick;

    public Entity createEntity(String type) {
        Entity entity = new Entity(EntityId.random(), type);
        entities.put(entity.id(), entity);
        return entity;
    }

    public Entity requireEntity(EntityId id) {
        Entity entity = entities.get(Objects.requireNonNull(id, "id"));
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity: " + id.value());
        }
        return entity;
    }

    public Collection<Entity> entities() {
        return entities.values();
    }

    public <T extends Component> void putGlobal(T component) {
        globals.put(component.getClass(), component);
    }

    /**
     * 读取必需的全局组件。属于整场战斗而不是单个实体的状态应放在这里。
     */
    public <T extends Component> T requireGlobal(Class<T> componentType) {
        Component component = globals.get(Objects.requireNonNull(componentType, "componentType"));
        if (component == null) {
            throw new IllegalStateException("Battle state missing global component " + componentType.getSimpleName());
        }
        return componentType.cast(component);
    }

    public long tick() {
        return tick;
    }

    public void advanceTick() {
        tick++;
    }
}
