package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.scene.ScenePlayerInterest;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 频道聊天 Actor。
 * 每个频道一个 mailbox，成员变更、发言序号和历史追加都只在该 Actor 内串行执行。
 */
public final class ChatChannelAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final String channelId;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatMessagePolicy messagePolicy;
    private final Clock clock;
    private final Set<Long> members = new HashSet<>();
    private final List<ChatDelivery> history = new ArrayList<>();
    private long revision;

    public ChatChannelAgent(
            AgentMessagePort messages,
            ActorRef self,
            String channelId,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.channelId = ChatJoinRequest.normalizeChannelId(channelId);
        this.interests = Objects.requireNonNull(interests, "interests");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void join(ChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> callback.accept(joinNow(request)));
    }

    public void leave(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> callback.accept(leaveNow(request)));
    }

    public void send(ChatSendRequest request, Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> callback.accept(sendNow(request)));
    }

    public void snapshot(Consumer<ChatChannelSnapshot> callback) {
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ActorTaskCategory.OBSERVABILITY, ignored -> callback.accept(snapshotNow()));
    }

    public ChatChannelSnapshot snapshotNow() {
        return new ChatChannelSnapshot(channelId, members, history, revision);
    }

    private ChatJoinResult joinNow(ChatJoinRequest request) {
        boolean added = members.add(request.playerId());
        if (added) {
            interests.enter(ScenePlayerInterest.withAlliance(
                    request.playerId(),
                    interestKey(),
                    request.allianceId()
            ));
        }
        return new ChatJoinResult(
                added ? ChatJoinStatus.JOINED : ChatJoinStatus.ALREADY_JOINED,
                channelId,
                request.playerId(),
                members.size()
        );
    }

    private ChatLeaveResult leaveNow(ChatLeaveRequest request) {
        boolean removed = members.remove(request.playerId());
        if (removed) {
            interests.leave(request.playerId(), interestKey());
        }
        return new ChatLeaveResult(
                removed ? ChatLeaveStatus.LEFT : ChatLeaveStatus.NOT_IN_CHANNEL,
                channelId,
                request.playerId(),
                members.size()
        );
    }

    private ChatSendResult sendNow(ChatSendRequest request) {
        if (!members.contains(request.senderId())) {
            return ChatSendResult.rejected(ChatSendStatus.NOT_IN_CHANNEL);
        }
        ChatMessageDecision decision = messagePolicy.inspect(request);
        if (decision.status() != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(decision.status());
        }
        // 发言结算边界：只有频道 Actor 确认成员和资料快照后才递增 revision 并追加历史。
        ChatDelivery delivery = new ChatDelivery(
                channelId,
                request.senderId(),
                decision.displayName(),
                decision.normalizedText(),
                ++revision,
                clock.instant()
        );
        history.add(delivery);
        return ChatSendResult.sent(delivery);
    }

    private String interestKey() {
        return "chat:" + channelId;
    }

    private void ensureSameChannel(String requestChannelId) {
        if (!channelId.equals(requestChannelId)) {
            throw new IllegalArgumentException("request channel does not match agent channel");
        }
    }
}
