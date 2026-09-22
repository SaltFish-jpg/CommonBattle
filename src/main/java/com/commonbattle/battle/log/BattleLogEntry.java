package com.commonbattle.battle.log;



import java.time.Instant;

public record BattleLogEntry(long sequence, Instant occurredAt, String type, String message) {
}
