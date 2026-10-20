package com.commonbattle.game.agent;

import com.commonbattle.actor.message.AgentDeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncActorCallbackTest {
    @Test
    void deliverToActorRecordsOnlyRejectedDeliveries() {
        AtomicReference<AgentDeliveryResult> failure = new AtomicReference<>();

        AgentDeliveryResult accepted = AsyncActorCallback.deliverToActor(
                "payload",
                ignored -> AgentDeliveryResult.acceptedResult(),
                failure::set
        );
        AgentDeliveryResult rejected = AsyncActorCallback.deliverToActor(
                "payload",
                ignored -> AgentDeliveryResult.mailboxFull(),
                failure::set
        );

        assertEquals(AgentDeliveryResult.acceptedResult(), accepted);
        assertEquals(AgentDeliveryResult.mailboxFull(), rejected);
        assertEquals(AgentDeliveryResult.mailboxFull(), failure.get());
    }

    @Test
    void acceptedDeliveryRecordsDecisionWithoutFallback() {
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> callback = new AtomicReference<>();

        AgentDeliveryResult result = AsyncActorCallback.deliverDecision(
                "ok",
                ignored -> decisions.incrementAndGet(),
                ignored -> AgentDeliveryResult.acceptedResult(),
                failures::incrementAndGet,
                () -> "busy",
                callback::set
        );

        assertEquals(AgentDeliveryResult.acceptedResult(), result);
        assertEquals(1, decisions.get());
        assertEquals(0, failures.get());
        assertEquals(null, callback.get());
    }

    @Test
    void rejectedDeliveryRecordsFailureAndReturnsBackpressureResponse() {
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> callback = new AtomicReference<>();

        AgentDeliveryResult result = AsyncActorCallback.deliverDecision(
                "ok",
                ignored -> decisions.incrementAndGet(),
                ignored -> AgentDeliveryResult.mailboxFull(),
                failures::incrementAndGet,
                () -> "busy",
                callback::set
        );

        assertEquals(AgentDeliveryResult.mailboxFull(), result);
        assertEquals(1, decisions.get());
        assertEquals(1, failures.get());
        assertEquals("busy", callback.get());
    }
}
