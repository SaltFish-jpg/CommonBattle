package com.commonbattle.game.chat;

import com.commonbattle.game.event.EventGatedBusinessCommand;
import com.commonbattle.game.social.FriendChangedEvent;

import java.util.Objects;

/**
 * 好友关系事件之后的私聊请求。
 * 事件版本是读最新好友快照的最小版本，最终发言仍回到私聊会话 Actor。
 */
public record DirectChatSendAfterFriendEventRequest(
        FriendChangedEvent event,
        DirectChatSendRequest request
) implements EventGatedBusinessCommand<FriendChangedEvent, DirectChatSendRequest> {
    public DirectChatSendAfterFriendEventRequest {
        event = Objects.requireNonNull(event, "event");
        request = Objects.requireNonNull(request, "request");
    }

    @Override
    public DirectChatSendRequest command() {
        return request;
    }
}
