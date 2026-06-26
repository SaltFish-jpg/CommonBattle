package com.commonbattle.core;

/**
 * Adds a buff to an entity, creating its buff component if needed.
 */
public record AddBuffEffect(EntityId target, Buff buff) implements Effect {
    @Override
    public void apply(BattleContext context) {
        Entity entity = context.state().requireEntity(target);
        BuffComponent buffs = entity.find(BuffComponent.class).orElseGet(() -> {
            BuffComponent created = new BuffComponent();
            entity.add(created);
            return created;
        });
        buffs.add(buff);
        context.log().add("buff.added", "%s gained buff %s".formatted(target.value(), buff.id()));
        context.eventBus().publish(new BuffAddedEvent(target, buff));
    }
}
