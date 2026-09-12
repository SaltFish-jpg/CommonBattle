package com.commonbattle.game.session;

/**
 * 玩家客户端登录响应。
 */
public record PlayerClientLoginResponse(
        long playerId,
        String sessionId,
        long sessionEpoch,
        boolean created,
        long stateRevision,
        long eventRevision,
        String status,
        String message,
        PlayerClientErrorCode code
) {
    public PlayerClientLoginResponse(
            long playerId,
            String sessionId,
            long sessionEpoch,
            boolean created,
            long stateRevision,
            long eventRevision,
            String status,
            String message
    ) {
        this(playerId, sessionId, sessionEpoch, created, stateRevision, eventRevision, status, message,
                "OK".equals(status) ? PlayerClientErrorCode.OK : PlayerClientErrorCode.LOGIN_FAILED);
    }

    public static PlayerClientLoginResponse success(PlayerLoginResult login) {
        return new PlayerClientLoginResponse(
                login.session().playerId(),
                login.session().sessionId(),
                login.session().epoch(),
                login.created(),
                login.stateRevision(),
                login.eventRevision(),
                "OK",
                "",
                PlayerClientErrorCode.OK
        );
    }

    public static PlayerClientLoginResponse failed(PlayerClientLoginRequest request, String message) {
        return failed(request, PlayerClientErrorCode.LOGIN_FAILED, message);
    }

    public static PlayerClientLoginResponse failed(
            PlayerClientLoginRequest request,
            PlayerClientErrorCode code,
            String message
    ) {
        return new PlayerClientLoginResponse(request.playerId(), request.sessionId(), 0, false, 0, 0, "FAILED", message, code);
    }
}
