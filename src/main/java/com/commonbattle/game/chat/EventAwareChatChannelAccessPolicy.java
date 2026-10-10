package com.commonbattle.game.chat;

import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.util.function.Consumer;

/**
 * 可感知联盟 owner 事件的频道访问策略。
 * 用于把事件追新、远端快照补读和频道 Actor 最终结算串成明确的异步命令链。
 */
public interface EventAwareChatChannelAccessPolicy extends ChatChannelAccessPolicy {
    void inspectJoinAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatJoinRequest request,
            Consumer<ChatJoinStatus> callback
    );

    void inspectSendAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatSendRequest request,
            Consumer<ChatSendStatus> callback
    );
}
