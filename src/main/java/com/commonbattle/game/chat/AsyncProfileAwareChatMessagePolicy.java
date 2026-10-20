package com.commonbattle.game.chat;

import com.commonbattle.game.profile.ProfileReadPort;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 异步玩家资料聊天策略。
 * Profile 强读可能触发远程 RPC，本类把读取放到独立执行器，完成后由调用方回投频道 Actor。
 */
public final class AsyncProfileAwareChatMessagePolicy implements AsyncChatMessagePolicy {
    private final ChatMessagePolicy delegate;
    private final Executor executor;

    public AsyncProfileAwareChatMessagePolicy(ProfileReadPort profiles, Executor executor) {
        this(new ProfileAwareChatMessagePolicy(profiles), executor);
    }

    public AsyncProfileAwareChatMessagePolicy(ChatMessagePolicy delegate, Executor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void inspect(ChatSendRequest request, Consumer<ChatMessageDecision> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        try {
            executor.execute(() -> callback.accept(inspectSafely(request)));
        } catch (RejectedExecutionException e) {
            callback.accept(ChatMessageDecision.rejected(ChatSendStatus.BACKPRESSURED));
        }
    }

    private ChatMessageDecision inspectSafely(ChatSendRequest request) {
        try {
            return delegate.inspect(request);
        } catch (RuntimeException e) {
            return ChatMessageDecision.rejected(ChatSendStatus.STALE_PROFILE);
        }
    }
}
