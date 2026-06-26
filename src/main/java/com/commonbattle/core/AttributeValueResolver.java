package com.commonbattle.core;

/**
 * Resolves effective attributes from base component values plus active buff modifiers.
 */
public final class AttributeValueResolver {
    private AttributeValueResolver() {
    }

    public static int resolve(Entity entity, String attribute) {
        int base = entity.require(AttributeComponent.class).base(attribute);
        int bonus = entity.find(BuffComponent.class)
                .map(buffs -> buffs.attributeBonus(attribute))
                .orElse(0);
        return base + bonus;
    }
}
