package com.commonbattle.game.agent;

import com.commonbattle.actor.message.AgentDeliveryResult;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 异步业务回调投递边界。
 * 用于 RPC、快照读取、风控等外部异步判定完成后，把判定结果投递回目标 Actor mailbox。
 */
public final class AsyncActorCallback {
    private AsyncActorCallback() {
    }

    public static <T> AgentDeliveryResult deliverToActor(
            T payload,
            Function<T, AgentDeliveryResult> actorDelivery,
            Consumer<AgentDeliveryResult> deliveryFailureRecorder
    ) {
        Objects.requireNonNull(actorDelivery, "actorDelivery");
        Objects.requireNonNull(deliveryFailureRecorder, "deliveryFailureRecorder");
        AgentDeliveryResult result = Objects.requireNonNull(actorDelivery.apply(payload), "deliveryResult");
        if (!result.accepted()) {
            deliveryFailureRecorder.accept(result);
        }
        return result;
    }

    public static <D, R> AgentDeliveryResult deliverDecision(
            D decision,
            Consumer<D> decisionRecorder,
            Function<D, AgentDeliveryResult> actorDelivery,
            Runnable deliveryFailureRecorder,
            Supplier<R> backpressuredResponse,
            Consumer<R> callerCallback
    ) {
        Objects.requireNonNull(decisionRecorder, "decisionRecorder");
        Objects.requireNonNull(actorDelivery, "actorDelivery");
        Objects.requireNonNull(deliveryFailureRecorder, "deliveryFailureRecorder");
        Objects.requireNonNull(backpressuredResponse, "backpressuredResponse");
        Objects.requireNonNull(callerCallback, "callerCallback");
        decisionRecorder.accept(decision);
        AgentDeliveryResult result = deliverToActor(decision, actorDelivery, ignored -> deliveryFailureRecorder.run());
        if (!result.accepted()) {
            callerCallback.accept(backpressuredResponse.get());
        }
        return result;
    }
}
