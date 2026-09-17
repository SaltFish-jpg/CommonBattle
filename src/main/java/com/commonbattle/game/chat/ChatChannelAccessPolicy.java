package com.commonbattle.game.chat;

/**
 * 频道成员访问策略。
 * 用于联盟、队伍、战场等带外部成员关系的频道，在频道 Actor 内完成最终准入判断。
 */
public interface ChatChannelAccessPolicy {
    ChatJoinStatus inspectJoin(ChatJoinRequest request);

    ChatSendStatus inspectSend(ChatSendRequest request);

    static ChatChannelAccessPolicy allowAll() {
        return AllowAll.INSTANCE;
    }

    enum AllowAll implements ChatChannelAccessPolicy {
        INSTANCE;

        @Override
        public ChatJoinStatus inspectJoin(ChatJoinRequest request) {
            return ChatJoinStatus.JOINED;
        }

        @Override
        public ChatSendStatus inspectSend(ChatSendRequest request) {
            return ChatSendStatus.SENT;
        }
    }
}
