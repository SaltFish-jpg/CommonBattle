package com.commonbattle.game.event;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 把版本事件订阅回调转换为 Actor 邮箱任务。
 * 它用于 Scene、Chat、排行榜等非 owner 服务，保证事件中心或 Netty 回调线程不直接执行业务逻辑。
 */
public final class ActorMailboxEventSubscriber implements VersionedEventSubscriber, ActorEventSubscriberView {
    private final AgentMessagePort messages;
    private final ActorRef target;
    private final ActorTaskCategory category;
    private final ActorEventHandler handler;
    private volatile Consumer<VersionedEvent> rejectedEventHandler = ignored -> {
    };
    private final AtomicLong receivedEvents = new AtomicLong();
    private final AtomicLong enqueuedEvents = new AtomicLong();
    private final AtomicLong rejectedEvents = new AtomicLong();
    private final AtomicLong handledEvents = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();

    public ActorMailboxEventSubscriber(AgentMessagePort messages, ActorRef target, ActorEventHandler handler) {
        this(messages, target, ActorTaskCategory.EVENT, handler);
    }

    public ActorMailboxEventSubscriber(
            AgentMessagePort messages,
            ActorRef target,
            ActorTaskCategory category,
            ActorEventHandler handler
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.target = Objects.requireNonNull(target, "target");
        this.category = Objects.requireNonNull(category, "category");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * 绑定邮箱拒绝后的修复入口。
     * 常见用途是把 ownerKey 交给快照修复调度器，避免事件被 mailbox 背压丢弃后投影长期停在旧版本。
     */
    public void onRejectedEvent(Consumer<VersionedEvent> handler) {
        this.rejectedEventHandler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void onEvent(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        receivedEvents.incrementAndGet();
        AgentDeliveryResult result = messages.tryTellLocal(target, category, context -> {
            try {
                handler.handle(context, event);
                handledEvents.incrementAndGet();
            } catch (RuntimeException | Error e) {
                failedEvents.incrementAndGet();
                throw e;
            }
        });
        if (result.accepted()) {
            enqueuedEvents.incrementAndGet();
        } else {
            rejectedEvents.incrementAndGet();
            try {
                rejectedEventHandler.accept(event);
            } catch (RuntimeException | Error e) {
                failedEvents.incrementAndGet();
                throw e;
            }
        }
    }

    @Override
    public ActorEventSubscriberStats stats() {
        return new ActorEventSubscriberStats(
                receivedEvents.get(),
                enqueuedEvents.get(),
                rejectedEvents.get(),
                handledEvents.get(),
                failedEvents.get()
        );
    }
}
