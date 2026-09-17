package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.profile.ProfileReadPort;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Chat 服频道管理器。
 * Manager 只负责定位频道 Actor，具体业务状态不放在 Manager，避免绕过 mailbox 串行边界。
 */
public final class ChatChannelManager implements ChatRuntimeView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatChannelAccessPolicy accessPolicy;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final InboundAdmissionController admissions;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final ConcurrentMap<String, ChatChannelAgent> channels = new ConcurrentHashMap<>();
    private final AtomicLong joinRequests = new AtomicLong();
    private final AtomicLong leaveRequests = new AtomicLong();
    private final AtomicLong sendRequests = new AtomicLong();

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ProfileReadPort profiles,
            Clock clock
    ) {
        this(actors, messages, interests, new ProfileAwareChatMessagePolicy(profiles), ChatDeliverySink.noop(), clock,
                ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(actors, messages, interests, messagePolicy, ChatDeliverySink.noop(), clock, ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy, deliverySink, clock,
                maxHistoryMessages,
                (target, operation) -> AdmissionDecision.accept());
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(actors, messages, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy, deliverySink, clock,
                maxHistoryMessages, admissions);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, interests, messagePolicy, ChatDeliverySink.noop(), clock, maxHistoryMessages);
    }

    public void join(ChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        joinRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.JOIN_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0));
            return;
        }
        channel(request.channelId()).join(request, callback);
    }

    public void leave(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        leaveRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.LEAVE_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(new ChatLeaveResult(ChatLeaveStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0));
            return;
        }
        channel(request.channelId()).leave(request, callback);
    }

    public void send(ChatSendRequest request, Consumer<ChatSendResult> callback) {
        sendRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.SEND_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
            return;
        }
        channel(request.channelId()).send(request, callback);
    }

    public void removeAllianceMember(long allianceId, long playerId) {
        ChatChannelAgent channel = channels.get(ChatChannelIds.alliance(allianceId));
        if (channel != null) {
            channel.removeMemberDueToAllianceChange(playerId);
        }
    }

    public void retainAllianceMembers(long allianceId, Set<Long> memberIds) {
        Objects.requireNonNull(memberIds, "memberIds");
        ChatChannelAgent channel = channels.get(ChatChannelIds.alliance(allianceId));
        if (channel != null) {
            channel.retainMembersDueToAllianceSnapshot(memberIds);
        }
    }

    public ChatServiceStats stats() {
        long retainedMessages = 0;
        long droppedHistoryMessages = 0;
        long allianceEventRemovedMembers = 0;
        long allianceSnapshotRemovedMembers = 0;
        ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
        for (ChatChannelAgent channel : channels.values()) {
            retainedMessages += channel.retainedMessages();
            droppedHistoryMessages += channel.droppedHistoryMessages();
            allianceEventRemovedMembers += channel.allianceEventRemovedMembers();
            allianceSnapshotRemovedMembers += channel.allianceSnapshotRemovedMembers();
            deliveryStats = deliveryStats.plus(channel.deliveryStats());
        }
        return new ChatServiceStats(
                channels.size(),
                0,
                joinRequests.get(),
                leaveRequests.get(),
                sendRequests.get(),
                0,
                0,
                retainedMessages,
                droppedHistoryMessages,
                deliveryStats.acceptedRecipients(),
                deliveryStats.droppedRecipients(),
                deliveryStats.failedRecipients(),
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers
        );
    }

    ChatChannelAgent channel(String channelId) {
        String normalized = ChatJoinRequest.normalizeChannelId(channelId);
        return channels.computeIfAbsent(normalized, this::createChannel);
    }

    private ChatChannelAgent createChannel(String channelId) {
        return new ChatChannelAgent(
                messages,
                actors.actor(ChatActorIds.channelActorId(channelId)),
                channelId,
                interests,
                accessPolicy,
                messagePolicy,
                deliverySink,
                clock,
                maxHistoryMessages
        );
    }
}
