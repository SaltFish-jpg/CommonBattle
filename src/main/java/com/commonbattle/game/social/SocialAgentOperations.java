package com.commonbattle.game.social;

/**
 * 社交类业务 Agent 的通用操作名。
 * 这些操作通过 BusinessAgentMessagePort 进入好友或联盟 owner mailbox。
 */
public final class SocialAgentOperations {
    public static final String FRIEND_ADD = "friend.add";
    public static final String FRIEND_REMOVE = "friend.remove";
    public static final String FRIEND_SNAPSHOT = "friend.snapshot";
    public static final String ALLIANCE_JOIN = "alliance.join";
    public static final String ALLIANCE_LEAVE = "alliance.leave";
    public static final String ALLIANCE_SNAPSHOT = "alliance.snapshot";

    private SocialAgentOperations() {
    }
}
