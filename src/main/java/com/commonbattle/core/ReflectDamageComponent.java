package com.commonbattle.core;

/**
 * 实体受到伤害后反弹伤害的配置。
 * 反伤值由固定值和实际承受伤害比例相加得到。
 */
public record ReflectDamageComponent(int flatAmount, double ratio) implements Component {
    public ReflectDamageComponent {
        if (flatAmount < 0) {
            throw new IllegalArgumentException("flat reflect amount must be non-negative");
        }
        if (ratio < 0) {
            throw new IllegalArgumentException("reflect ratio must be non-negative");
        }
    }
}
