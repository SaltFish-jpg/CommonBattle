package com.commonbattle.game.chat;

import com.commonbattle.game.social.FriendChangedEvent;

import java.util.function.Consumer;

/**
 * 可感知好友 owner 事件的私聊访问策略。
 * Manager 在跨服事件到达后先调用这里完成最新快照校验，再把结果投递回私聊会话 Actor 结算。
 */
public interface EventAwareDirectChatAccessPolicy extends DirectChatAccessPolicy {
    void inspectAfterFriendChanged(
            FriendChangedEvent event,
            DirectChatSendRequest request,
            Consumer<ChatSendStatus> callback
    );
}
