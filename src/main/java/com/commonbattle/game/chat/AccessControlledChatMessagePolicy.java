package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 带访问控制的聊天策略。
 * 禁言、频道封禁等快速判断先执行，通过后再进入资料快照、敏感词等后续策略。
 */
public final class AccessControlledChatMessagePolicy implements ChatMessagePolicy {
    private final ChatAccessControl accessControl;
    private final ChatMessagePolicy delegate;

    public AccessControlledChatMessagePolicy(ChatAccessControl accessControl, ChatMessagePolicy delegate) {
        this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ChatMessageDecision inspect(ChatSendRequest request) {
        ChatSendStatus status = accessControl.inspect(request);
        if (status != ChatSendStatus.SENT) {
            return ChatMessageDecision.rejected(status);
        }
        return delegate.inspect(request);
    }
}
