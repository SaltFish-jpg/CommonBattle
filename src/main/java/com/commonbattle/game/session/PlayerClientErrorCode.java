package com.commonbattle.game.session;

/**
 * 玩家客户端网关错误码。
 */
public enum PlayerClientErrorCode {
    OK,
    AUTH_REJECTED,
    DUPLICATE_LOGIN,
    LOGIN_FAILED,
    NOT_LOGGED_IN,
    SESSION_EXPIRED,
    COMMAND_RATE_LIMITED,
    HEARTBEAT_RATE_LIMITED,
    INVALID_PAYLOAD,
    INVALID_FRAME,
    IDLE_TIMEOUT,
    SLOW_CLIENT
}
