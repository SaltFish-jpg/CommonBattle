package com.commonbattle.game.chat;

import com.commonbattle.game.scene.SceneFriendAwarenessAgent;

import java.util.Objects;

/**
 * 基于本地好友快照的私聊访问策略。
 * 快照缺失或已标记 stale 时拒绝私聊，避免用不可信关系放行业务消息。
 */
public final class FriendAwareDirectChatAccessPolicy implements DirectChatAccessPolicy {
    private final SceneFriendAwarenessAgent friends;

    public FriendAwareDirectChatAccessPolicy(SceneFriendAwarenessAgent friends) {
        this.friends = Objects.requireNonNull(friends, "friends");
    }

    @Override
    public ChatSendStatus inspect(DirectChatSendRequest request) {
        return friends.friendsOf(request.senderId())
                .map(view -> {
                    if (view.stale()) {
                        return ChatSendStatus.STALE_FRIENDS;
                    }
                    return view.friends().contains(request.receiverId())
                            ? ChatSendStatus.SENT
                            : ChatSendStatus.NOT_FRIEND;
                })
                .orElse(ChatSendStatus.STALE_FRIENDS);
    }
}
