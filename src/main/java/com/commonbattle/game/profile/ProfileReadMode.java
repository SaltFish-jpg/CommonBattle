package com.commonbattle.game.profile;

/**
 * 玩家基础资料读取模式。
 * 展示类逻辑通常用 LOCAL_FAST，关键逻辑在发现缓存缺口时用 REFRESH_IF_STALE，强一致入口用 FORCE_REFRESH。
 */
public enum ProfileReadMode {
    LOCAL_FAST,
    REFRESH_IF_STALE,
    FORCE_REFRESH
}
