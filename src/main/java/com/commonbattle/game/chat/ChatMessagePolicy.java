package com.commonbattle.game.chat;

/**
 * 聊天发送策略。
 * 可在这里替换为敏感词、禁言、跨服黑名单、资料快照一致性等工业级策略组合。
 */
public interface ChatMessagePolicy {
    ChatMessageDecision inspect(ChatSendRequest request);
}
