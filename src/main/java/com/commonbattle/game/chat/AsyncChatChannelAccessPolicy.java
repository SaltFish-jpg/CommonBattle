package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 异步频道访问策略。
 * 用于联盟、队伍、战场等需要读取其他 Agent 投影的频道准入；实现方应先读取快照，再由调用方把结果投回频道 Actor。
 */
public interface AsyncChatChannelAccessPolicy {
    void inspectJoin(ChatJoinRequest request, Consumer<ChatJoinStatus> callback);

    void inspectSend(ChatSendRequest request, Consumer<ChatSendStatus> callback);

    static AsyncChatChannelAccessPolicy direct(ChatChannelAccessPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return new AsyncChatChannelAccessPolicy() {
            @Override
            public void inspectJoin(ChatJoinRequest request, Consumer<ChatJoinStatus> callback) {
                callback.accept(policy.inspectJoin(request));
            }

            @Override
            public void inspectSend(ChatSendRequest request, Consumer<ChatSendStatus> callback) {
                callback.accept(policy.inspectSend(request));
            }
        };
    }
}
