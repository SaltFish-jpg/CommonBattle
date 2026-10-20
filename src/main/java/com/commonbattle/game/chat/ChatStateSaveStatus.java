package com.commonbattle.game.chat;

/**
 * Chat 快照 CAS 写入结果。
 */
public enum ChatStateSaveStatus {
    APPLIED,
    VERSION_CONFLICT,
    STALE_OWNER
}
