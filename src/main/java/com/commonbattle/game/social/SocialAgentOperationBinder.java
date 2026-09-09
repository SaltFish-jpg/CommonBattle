package com.commonbattle.game.social;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.game.agent.BusinessAgentHandlerRegistry;
import com.commonbattle.game.agent.BusinessAgentRequest;
import com.commonbattle.game.agent.BusinessAgentRequestException;

import java.util.Objects;
import java.util.function.LongFunction;

/**
 * 社交业务 Agent 操作绑定器。
 * 绑定后，Game、Scene、Chat 等服务可通过统一 Agent 调用接口访问好友和联盟 owner。
 */
public final class SocialAgentOperationBinder {
    private SocialAgentOperationBinder() {
    }

    public static void register(
            BusinessAgentHandlerRegistry registry,
            LongFunction<FriendAgent> friends,
            LongFunction<AllianceAgent> alliances
    ) {
        registerFriends(registry, friends);
        registerAlliances(registry, alliances);
    }

    public static void registerFriends(BusinessAgentHandlerRegistry registry, LongFunction<FriendAgent> friends) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(friends, "friends");
        registry.handle(SocialAgentOperations.FRIEND_ADD, (context, request) ->
                friend(request, friends).addNow(friendPayload(request).friendId()));
        registry.handle(SocialAgentOperations.FRIEND_REMOVE, (context, request) ->
                friend(request, friends).removeNow(friendPayload(request).friendId()));
        registry.handle(SocialAgentOperations.FRIEND_SNAPSHOT, (context, request) ->
                friend(request, friends).snapshotNow());
    }

    public static void registerAlliances(BusinessAgentHandlerRegistry registry, LongFunction<AllianceAgent> alliances) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(alliances, "alliances");
        registry.handle(SocialAgentOperations.ALLIANCE_JOIN, (context, request) ->
                alliance(request, alliances).joinNow(alliancePayload(request).playerId()));
        registry.handle(SocialAgentOperations.ALLIANCE_LEAVE, (context, request) ->
                alliance(request, alliances).leaveNow(alliancePayload(request).playerId()));
        registry.handle(SocialAgentOperations.ALLIANCE_SNAPSHOT, (context, request) ->
                alliance(request, alliances).snapshotNow());
    }

    private static FriendAgent friend(BusinessAgentRequest request, LongFunction<FriendAgent> friends) {
        if (!AgentIdentity.FRIEND.equals(request.target().type())) {
            throw new BusinessAgentRequestException(request.target(), request.operation(), "target_not_friend");
        }
        return friends.apply(parsePositiveLong(request));
    }

    private static AllianceAgent alliance(BusinessAgentRequest request, LongFunction<AllianceAgent> alliances) {
        if (!AgentIdentity.ALLIANCE.equals(request.target().type())) {
            throw new BusinessAgentRequestException(request.target(), request.operation(), "target_not_alliance");
        }
        return alliances.apply(parsePositiveLong(request));
    }

    private static FriendRelationRequest friendPayload(BusinessAgentRequest request) {
        if (request.payload() instanceof FriendRelationRequest payload) {
            return payload;
        }
        throw new BusinessAgentRequestException(request.target(), request.operation(), "invalid_payload");
    }

    private static AllianceMemberRequest alliancePayload(BusinessAgentRequest request) {
        if (request.payload() instanceof AllianceMemberRequest payload) {
            return payload;
        }
        throw new BusinessAgentRequestException(request.target(), request.operation(), "invalid_payload");
    }

    private static long parsePositiveLong(BusinessAgentRequest request) {
        try {
            long value = Long.parseLong(request.target().key());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 目标 key 来自跨服请求，必须在入口处转成稳定错误，不能让解析细节泄露给调用方。
        }
        throw new BusinessAgentRequestException(request.target(), request.operation(), "invalid_owner_key");
    }
}
