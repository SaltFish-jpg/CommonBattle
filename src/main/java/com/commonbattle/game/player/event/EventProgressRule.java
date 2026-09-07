package com.commonbattle.game.player.event;

import java.util.Objects;

/**
 * 玩家业务事件进度规则。
 * eventType 为空表示不监听事件，subject 为空表示监听该事件类型下所有对象。
 */
public record EventProgressRule(String eventType, String subject) {
    private static final EventProgressRule NONE = new EventProgressRule("", "");

    public EventProgressRule {
        eventType = Objects.requireNonNullElse(eventType, "");
        subject = Objects.requireNonNullElse(subject, "");
    }

    public static EventProgressRule none() {
        return NONE;
    }

    public static EventProgressRule of(String eventType, String subject) {
        return new EventProgressRule(eventType, subject);
    }

    public boolean enabled() {
        return !eventType.isBlank();
    }

    public boolean matches(PlayerDomainEvent event) {
        Objects.requireNonNull(event, "event");
        return enabled()
                && eventType.equals(event.type())
                && (subject.isBlank() || subject.equals(event.subject()));
    }
}
