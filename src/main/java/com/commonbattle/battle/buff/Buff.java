package com.commonbattle.battle.buff;




import java.util.Objects;
import java.util.Optional;

/**
 * 运行时 Buff 定义。
 * 一个 Buff 可以携带属性修正、控制状态，或两者同时存在。
 */
public record Buff(String id, String attribute, int attributeDelta, StatusEffect status, int remainingTurns) {
    public Buff {
        Objects.requireNonNull(id, "id");
    }

    public static Buff attribute(String id, String attribute, int delta, int remainingTurns) {
        return new Buff(id, Objects.requireNonNull(attribute, "attribute"), delta, null, remainingTurns);
    }

    public static Buff status(String id, StatusEffect status, int remainingTurns) {
        return new Buff(id, null, 0, Objects.requireNonNull(status, "status"), remainingTurns);
    }

    public Optional<String> attributeName() {
        return Optional.ofNullable(attribute);
    }

    public Optional<StatusEffect> statusEffect() {
        return Optional.ofNullable(status);
    }
}
