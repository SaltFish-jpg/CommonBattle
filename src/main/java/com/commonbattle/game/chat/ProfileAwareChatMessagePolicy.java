package com.commonbattle.game.chat;

import com.commonbattle.game.profile.ProfileReadPort;
import com.commonbattle.game.profile.ProfileReadResult;

import java.util.Objects;

/**
 * 依赖玩家基础资料快照的聊天策略。
 * 发言前按 minimum revision 读取资料，避免跨服事件乱序时使用旧昵称或旧外观。
 */
public final class ProfileAwareChatMessagePolicy implements ChatMessagePolicy {
    private final ProfileReadPort profiles;

    public ProfileAwareChatMessagePolicy(ProfileReadPort profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public ChatMessageDecision inspect(ChatSendRequest request) {
        Objects.requireNonNull(request, "request");
        String text = request.text().trim();
        if (text.isEmpty()) {
            return ChatMessageDecision.rejected(ChatSendStatus.EMPTY_TEXT);
        }
        ProfileReadResult result = profiles.readAtLeast(request.senderId(), request.requiredProfileRevision());
        if (!result.fresh()) {
            return ChatMessageDecision.rejected(ChatSendStatus.STALE_PROFILE);
        }
        String name = result.profile().orElseThrow().snapshot().name();
        return ChatMessageDecision.sent(name, text);
    }
}
