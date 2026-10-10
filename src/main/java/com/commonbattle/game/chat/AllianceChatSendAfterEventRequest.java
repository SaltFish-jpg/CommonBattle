package com.commonbattle.game.chat;

import com.commonbattle.game.event.EventGatedBusinessCommand;
import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.util.Objects;

/**
 * 联盟成员事件之后的频道发言请求。
 * 用于跨服事件回调先完成最新成员快照校验，再进入频道 Actor 串行发言。
 */
public record AllianceChatSendAfterEventRequest(
        AllianceMemberChangedEvent event,
        AllianceChatSendRequest request
) implements EventGatedBusinessCommand<AllianceMemberChangedEvent, AllianceChatSendRequest> {
    public AllianceChatSendAfterEventRequest {
        event = Objects.requireNonNull(event, "event");
        request = Objects.requireNonNull(request, "request");
    }

    @Override
    public AllianceChatSendRequest command() {
        return request;
    }
}
