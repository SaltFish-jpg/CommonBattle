package com.commonbattle.observability;

/**
 * Actor 运行时异常事件类型。
 */
public enum ActorIncidentKind {
    DEAD_LETTER,
    POISON_MESSAGE
}
