package com.commonbattle.core;

/**
 * 根据基础属性和当前 Buff 修正值计算最终属性。
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
