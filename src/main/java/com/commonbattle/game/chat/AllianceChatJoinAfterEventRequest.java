package com.commonbattle.game.chat;

import com.commonbattle.game.event.EventGatedBusinessCommand;
import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.util.Objects;

/**
 * 联盟成员事件之后的频道加入请求。
 * 携带触发事件版本，Chat 服会先追到该版本，再把加入命令投递到频道 Actor。
 */
public record AllianceChatJoinAfterEventRequest(
        AllianceMemberChangedEvent event,
        AllianceChatJoinRequest request
) implements EventGatedBusinessCommand<AllianceMemberChangedEvent, AllianceChatJoinRequest> {
    public AllianceChatJoinAfterEventRequest {
        event = Objects.requireNonNull(event, "event");
        request = Objects.requireNonNull(request, "request");
    }

    @Override
    public AllianceChatJoinRequest command() {
        return request;
    }
}
