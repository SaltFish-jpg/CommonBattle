package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 异步私聊访问策略。
 * 适合好友、黑名单等关系快照不属于私聊会话 Actor 的场景，避免会话 mailbox 内直接读取其他 Agent 状态。
 */
public interface AsyncDirectChatAccessPolicy {
    void inspect(DirectChatSendRequest request, Consumer<ChatSendStatus> callback);

    static AsyncDirectChatAccessPolicy direct(DirectChatAccessPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return (request, callback) -> callback.accept(policy.inspect(request));
    }
}
