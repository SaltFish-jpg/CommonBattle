package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.agent.AsyncActorCallback;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 商店库存异步回调的 Actor 邮箱适配器。
 * 底层 RPC 回调线程只投递 RPC_CALLBACK 任务，不直接执行业务结算。
 */
public final class ActorMailboxShopStockClient {
    private final ShopStockAsyncClient delegate;
    private final AgentMessagePort messages;
    private final ActorRef owner;
    private final Consumer<AgentDeliveryResult> deliveryFailures;

    public ActorMailboxShopStockClient(ShopStockAsyncClient delegate, AgentMessagePort messages, ActorRef owner) {
        this(delegate, messages, owner, ignored -> {
        });
    }

    public ActorMailboxShopStockClient(
            ShopStockAsyncClient delegate,
            AgentMessagePort messages,
            ActorRef owner,
            Consumer<AgentDeliveryResult> deliveryFailures
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.deliveryFailures = Objects.requireNonNull(deliveryFailures, "deliveryFailures");
    }

    public void reserve(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReserveResponse> callback
    ) {
        reserve(reservationId, sku, count, callback, ignored -> {
        });
    }

    public void reserve(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReserveResponse> callback,
            Consumer<AgentDeliveryResult> callDeliveryFailures
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(callDeliveryFailures, "callDeliveryFailures");
        delegate.reserve(reservationId, sku, count, new ShopStockCallback<>() {
            @Override
            public void success(ShopStockReserveResponse response) {
                AgentDeliveryResult delivery = enqueue(
                        context -> callback.success(context, response),
                        callDeliveryFailures
                );
                if (!delivery.accepted() && response.reserved()) {
                    releaseDroppedReservation(reservationId, sku, count);
                }
            }

            @Override
            public void failure(Throwable error) {
                enqueue(context -> callback.failure(context, error), callDeliveryFailures);
            }
        });
    }

    public void release(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReleaseResponse> callback
    ) {
        release(reservationId, sku, count, callback, ignored -> {
        });
    }

    public void release(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReleaseResponse> callback,
            Consumer<AgentDeliveryResult> callDeliveryFailures
    ) {
        delegate.release(reservationId, sku, count, mailboxCallback(callback, callDeliveryFailures));
    }

    public void remaining(String sku, ActorShopStockCallback<ShopStockRemainingResponse> callback) {
        delegate.remaining(sku, mailboxCallback(callback, ignored -> {
        }));
    }

    private <T> ShopStockCallback<T> mailboxCallback(
            ActorShopStockCallback<T> callback,
            Consumer<AgentDeliveryResult> callDeliveryFailures
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(callDeliveryFailures, "callDeliveryFailures");
        return new ShopStockCallback<>() {
            @Override
            public void success(T response) {
                enqueue(context -> callback.success(context, response), callDeliveryFailures);
            }

            @Override
            public void failure(Throwable error) {
                enqueue(context -> callback.failure(context, error), callDeliveryFailures);
            }
        };
    }

    private AgentDeliveryResult enqueue(
            com.commonbattle.actor.ActorTask task,
            Consumer<AgentDeliveryResult> callDeliveryFailures
    ) {
        return AsyncActorCallback.deliverToActor(
                task,
                payload -> messages.tryTellLocal(owner, ActorTaskCategory.RPC_CALLBACK, payload),
                delivery -> {
                    deliveryFailures.accept(delivery);
                    callDeliveryFailures.accept(delivery);
                }
        );
    }

    private void releaseDroppedReservation(String reservationId, String sku, int count) {
        delegate.release(reservationId, sku, count, new ShopStockCallback<>() {
            @Override
            public void success(ShopStockReleaseResponse response) {
            }

            @Override
            public void failure(Throwable error) {
            }
        });
    }
}
