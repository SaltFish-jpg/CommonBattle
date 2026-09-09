package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentMessagePort;

import java.util.Objects;

/**
 * 商店库存异步回调的 Actor 邮箱适配器。
 * 底层 RPC 回调线程只投递 RPC_CALLBACK 任务，不直接执行业务结算。
 */
public final class ActorMailboxShopStockClient {
    private final ShopStockAsyncClient delegate;
    private final AgentMessagePort messages;
    private final ActorRef owner;

    public ActorMailboxShopStockClient(ShopStockAsyncClient delegate, AgentMessagePort messages, ActorRef owner) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void reserve(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReserveResponse> callback
    ) {
        delegate.reserve(reservationId, sku, count, mailboxCallback(callback));
    }

    public void release(
            String reservationId,
            String sku,
            int count,
            ActorShopStockCallback<ShopStockReleaseResponse> callback
    ) {
        delegate.release(reservationId, sku, count, mailboxCallback(callback));
    }

    public void remaining(String sku, ActorShopStockCallback<ShopStockRemainingResponse> callback) {
        delegate.remaining(sku, mailboxCallback(callback));
    }

    private <T> ShopStockCallback<T> mailboxCallback(ActorShopStockCallback<T> callback) {
        Objects.requireNonNull(callback, "callback");
        return new ShopStockCallback<>() {
            @Override
            public void success(T response) {
                messages.tellLocal(owner, ActorTaskCategory.RPC_CALLBACK,
                        context -> callback.success(context, response));
            }

            @Override
            public void failure(Throwable error) {
                messages.tellLocal(owner, ActorTaskCategory.RPC_CALLBACK,
                        context -> callback.failure(context, error));
            }
        };
    }
}
