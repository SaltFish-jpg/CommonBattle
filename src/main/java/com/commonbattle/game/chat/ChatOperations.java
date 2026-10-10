package com.commonbattle.game.chat;

/**
 * Chat 服务 RPC 操作名。
 */
public final class ChatOperations {
    public static final String JOIN_CHANNEL = "chat.channel.join";
    public static final String LEAVE_CHANNEL = "chat.channel.leave";
    public static final String SEND_CHANNEL = "chat.channel.send";
    public static final String JOIN_WORLD = "chat.world.join";
    public static final String LEAVE_WORLD = "chat.world.leave";
    public static final String SEND_WORLD = "chat.world.send";
    public static final String JOIN_ALLIANCE = "chat.alliance.join";
    public static final String LEAVE_ALLIANCE = "chat.alliance.leave";
    public static final String SEND_ALLIANCE = "chat.alliance.send";
    public static final String SEND_DIRECT = "chat.direct.send";
    public static final String JOIN_ALLIANCE_AFTER_EVENT = "chat.alliance.join.after_event";
    public static final String SEND_ALLIANCE_AFTER_EVENT = "chat.alliance.send.after_event";
    public static final String SEND_DIRECT_AFTER_FRIEND_EVENT = "chat.direct.send.after_friend_event";

    private ChatOperations() {
    }
}
