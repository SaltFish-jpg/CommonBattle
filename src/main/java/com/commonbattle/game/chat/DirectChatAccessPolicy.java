package com.commonbattle.game.chat;

/**
 * 私聊关系访问策略。
 * 生产环境通常用好友快照、黑名单或跨服社交关系快照实现；测试和兼容场景可使用 allowAll。
 */
@FunctionalInterface
public interface DirectChatAccessPolicy {
    ChatSendStatus inspect(DirectChatSendRequest request);

    static DirectChatAccessPolicy allowAll() {
        return request -> ChatSendStatus.SENT;
    }
}
