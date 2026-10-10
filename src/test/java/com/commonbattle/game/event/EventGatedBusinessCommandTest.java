package com.commonbattle.game.event;

import com.commonbattle.game.chat.DirectChatSendAfterFriendEventRequest;
import com.commonbattle.game.chat.DirectChatSendRequest;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventGatedBusinessCommandTest {
    @Test
    void exposesEventBoundaryAndBusinessCommandUniformly() {
        FriendChangedEvent event = new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 7);
        DirectChatSendRequest request = new DirectChatSendRequest(10001L, 10002L, "direct", 3);
        EventGatedBusinessCommand<FriendChangedEvent, DirectChatSendRequest> command =
                new DirectChatSendAfterFriendEventRequest(event, request);

        assertEquals(request, command.command());
        assertEquals(new EventGatedCommandBoundary(
                FriendChangedEvent.TOPIC,
                FriendOwnerKeyParser.ownerKey(10001L),
                7
        ), command.boundary());
    }
}
