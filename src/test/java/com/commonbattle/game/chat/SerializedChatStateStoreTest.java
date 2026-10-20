package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializedChatStateStoreTest {
    private final ProtoChatStateSerializer serializer = new ProtoChatStateSerializer();

    @Test
    void protobufRoundTripPreservesChannelState() {
        VersionedChatState state = new VersionedChatState(7, 19, snapshot(2));

        VersionedChatState decoded = serializer.deserialize(serializer.serialize(state));

        assertEquals(state, decoded);
    }

    @Test
    void appliesOrderedCasAndRejectsVersionConflict() {
        SerializedChatStateStore store = store();
        AgentIdentity channel = ChatActorIds.channelIdentity("world:1");

        ChatStateSaveResult created = store.save(channel, 0, 3, snapshot(1));
        ChatStateSaveResult conflict = store.save(channel, 0, 3, snapshot(2));
        ChatStateSaveResult updated = store.save(channel, 1, 3, snapshot(2));

        assertEquals(ChatStateSaveStatus.APPLIED, created.status());
        assertEquals(1, created.current().orElseThrow().version());
        assertEquals(ChatStateSaveStatus.VERSION_CONFLICT, conflict.status());
        assertEquals(ChatStateSaveStatus.APPLIED, updated.status());
        assertEquals(2, store.load(channel).orElseThrow().version());
        assertEquals(2, store.load(channel).orElseThrow().snapshot().revision());
    }

    @Test
    void newerLeaseFencesDelayedOldOwnerWrite() {
        SerializedChatStateStore store = store();
        AgentIdentity session = ChatActorIds.directIdentity(10001L, 10002L);
        store.save(session, 0, 10, snapshot(1));
        store.save(session, 1, 11, snapshot(2));

        ChatStateSaveResult stale = store.save(session, 2, 10, snapshot(3));

        assertEquals(ChatStateSaveStatus.STALE_OWNER, stale.status());
        assertEquals(11, store.load(session).orElseThrow().ownerToken());
        assertEquals(2, store.load(session).orElseThrow().snapshot().revision());
    }

    private SerializedChatStateStore store() {
        return new SerializedChatStateStore(new InMemoryAtomicBytesStore(), serializer);
    }

    private static ChatChannelSnapshot snapshot(long revision) {
        return new ChatChannelSnapshot(
                "world:1",
                Set.of(10001L, 10002L),
                List.of(new ChatDelivery(
                        "world:1",
                        10001L,
                        "player",
                        "hello",
                        revision,
                        Instant.parse("2026-10-19T00:00:00Z")
                )),
                revision,
                Map.of(10001L, 900L, 10002L, 0L)
        );
    }
}
