package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentMessagePort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 私聊会话 Actor。
 * 两个玩家之间的私聊拥有独立 mailbox，后续可在这里接离线盒子、屏蔽关系和已读水位。
 */
public final class DirectChatSessionAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final String sessionId;
    private final long firstPlayerId;
    private final long secondPlayerId;
    private final ChatMessagePolicy messagePolicy;
    private final Clock clock;
    private final List<ChatDelivery> history = new ArrayList<>();
    private long revision;

    public DirectChatSessionAgent(
            AgentMessagePort messages,
            ActorRef self,
            long firstPlayerId,
            long secondPlayerId,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.firstPlayerId = Math.min(firstPlayerId, secondPlayerId);
        this.secondPlayerId = Math.max(firstPlayerId, secondPlayerId);
        this.sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void send(DirectChatSendRequest request, Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureParticipant(request.senderId());
        ensureParticipant(request.receiverId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> callback.accept(sendNow(request)));
    }

    public ChatChannelSnapshot snapshotNow() {
        return new ChatChannelSnapshot(sessionId, java.util.Set.of(firstPlayerId, secondPlayerId), history, revision);
    }

    private ChatSendResult sendNow(DirectChatSendRequest request) {
        ChatSendRequest policyRequest = new ChatSendRequest(
                sessionId,
                request.senderId(),
                request.text(),
                request.requiredProfileRevision()
        );
        ChatMessageDecision decision = messagePolicy.inspect(policyRequest);
        if (decision.status() != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(decision.status());
        }
        // 私聊会话结算边界：双方会话 Actor 独占递增 revision，后续离线投递也应从这里排队。
        ChatDelivery delivery = new ChatDelivery(
                sessionId,
                request.senderId(),
                decision.displayName(),
                decision.normalizedText(),
                ++revision,
                clock.instant()
        );
        history.add(delivery);
        return ChatSendResult.sent(delivery);
    }

    private void ensureParticipant(long playerId) {
        if (playerId != firstPlayerId && playerId != secondPlayerId) {
            throw new IllegalArgumentException("player is not a direct chat participant");
        }
    }
}
