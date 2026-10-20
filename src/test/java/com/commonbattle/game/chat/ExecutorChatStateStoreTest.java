package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorChatStateStoreTest {
    @Test
    void blockingLoadRunsOutsideCallerThread() throws InterruptedException {
        String caller = Thread.currentThread().getName();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        ChatStateStore delegate = new ChatStateStore() {
            @Override
            public Optional<VersionedChatState> load(AgentIdentity identity) {
                callbackThread.set(Thread.currentThread().getName());
                return Optional.empty();
            }

            @Override
            public ChatStateSaveResult save(
                    AgentIdentity identity,
                    long expectedVersion,
                    long ownerToken,
                    ChatChannelSnapshot snapshot
            ) {
                throw new UnsupportedOperationException();
            }
        };
        try (ExecutorChatStateStore store = new ExecutorChatStateStore(delegate, 1, 8, "chat-state-test")) {
            store.load(
                    ChatActorIds.channelIdentity("world:1"),
                    ignored -> completed.countDown(),
                    error -> completed.countDown()
            );

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertNotEquals(caller, callbackThread.get());
            assertTrue(callbackThread.get().startsWith("chat-state-test-"));
        }
    }
}
