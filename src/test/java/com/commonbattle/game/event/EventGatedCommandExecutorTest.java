package com.commonbattle.game.event;

import com.commonbattle.game.chat.ChatOperations;
import com.commonbattle.game.chat.ChatSendStatus;
import com.commonbattle.game.chat.DirectChatSendAfterFriendEventRequest;
import com.commonbattle.game.chat.DirectChatSendRequest;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendRelationAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventGatedCommandExecutorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsSubmittedBeforeAsyncCompletionAndCompletedAfterCallback() {
        InMemoryEventGatedCommandAuditLog audits = new InMemoryEventGatedCommandAuditLog();
        EventGatedCommandExecutor executor = new EventGatedCommandExecutor(audits, CLOCK);
        DirectChatSendAfterFriendEventRequest command = command();
        AtomicReference<Consumer<ChatSendStatus>> delayed = new AtomicReference<>();
        AtomicReference<ChatSendStatus> result = new AtomicReference<>();

        executor.execute(
                ChatOperations.SEND_DIRECT_AFTER_FRIEND_EVENT,
                command,
                (payload, callback) -> delayed.set(callback),
                ChatSendStatus::name,
                result::set
        );

        assertEquals(null, result.get());
        assertEquals(new EventGatedCommandAuditStats(1, 1, 1, 0, 0, 0), audits.stats());
        assertEquals("submitted", audits.recent().getFirst().outcome());

        delayed.get().accept(ChatSendStatus.SENT);

        assertEquals(ChatSendStatus.SENT, result.get());
        assertEquals(new EventGatedCommandAuditStats(2, 2, 1, 1, 0, 0), audits.stats());
        assertEquals("completed", audits.recent().get(1).outcome());
        assertEquals("SENT", audits.recent().get(1).result());
    }

    @Test
    void recordsFailedWhenSubmitThrowsSynchronously() {
        InMemoryEventGatedCommandAuditLog audits = new InMemoryEventGatedCommandAuditLog();
        EventGatedCommandExecutor executor = new EventGatedCommandExecutor(audits, CLOCK);

        assertThrows(IllegalStateException.class, () -> executor.execute(
                ChatOperations.SEND_DIRECT_AFTER_FRIEND_EVENT,
                command(),
                (payload, callback) -> {
                    throw new IllegalStateException("boom");
                },
                ChatSendStatus::name,
                ignored -> {
                }
        ));

        assertEquals(new EventGatedCommandAuditStats(2, 2, 1, 0, 1, 0), audits.stats());
        assertEquals("failed", audits.recent().get(1).outcome());
        assertEquals(IllegalStateException.class.getName(), audits.recent().get(1).result());
    }

    private static DirectChatSendAfterFriendEventRequest command() {
        return new DirectChatSendAfterFriendEventRequest(
                new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 3),
                new DirectChatSendRequest(10001L, 10002L, "direct", 0)
        );
    }
}
