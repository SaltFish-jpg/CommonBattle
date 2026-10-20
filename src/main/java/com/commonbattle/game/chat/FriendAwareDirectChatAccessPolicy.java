package com.commonbattle.game.chat;

import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerFriendView;
import com.commonbattle.game.social.FriendChangedEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 基于本地好友快照的私聊访问策略。
 * 快照缺失或已标记 stale 时拒绝私聊，避免用不可信关系放行业务消息。
 */
public final class FriendAwareDirectChatAccessPolicy implements EventAwareDirectChatAccessPolicy, AsyncDirectChatAccessPolicy {
    private final SceneFriendAwarenessAgent friends;

    public FriendAwareDirectChatAccessPolicy(SceneFriendAwarenessAgent friends) {
        this.friends = Objects.requireNonNull(friends, "friends");
    }

    @Override
    public ChatSendStatus inspect(DirectChatSendRequest request) {
        return statusOf(friends.friendsOf(request.senderId()), request.receiverId());
    }

    @Override
    public void inspect(DirectChatSendRequest request, Consumer<ChatSendStatus> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        friends.readFriendsOf(request.senderId(), view ->
                callback.accept(statusOf(view, request.receiverId())));
    }

    @Override
    public void inspectAfterFriendChanged(
            FriendChangedEvent event,
            DirectChatSendRequest request,
            Consumer<ChatSendStatus> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        if (event.playerId() != request.senderId()) {
            callback.accept(ChatSendStatus.STALE_FRIENDS);
            return;
        }
        friends.onFriendChangedAndReadFreshView(event, view ->
                callback.accept(statusOf(view, request.receiverId())));
    }

    private static ChatSendStatus statusOf(Optional<ScenePlayerFriendView> view, long receiverId) {
        return view.map(friendView -> {
            if (friendView.stale()) {
                return ChatSendStatus.STALE_FRIENDS;
            }
            return friendView.friends().contains(receiverId)
                    ? ChatSendStatus.SENT
                    : ChatSendStatus.NOT_FRIEND;
        }).orElse(ChatSendStatus.STALE_FRIENDS);
    }
}
