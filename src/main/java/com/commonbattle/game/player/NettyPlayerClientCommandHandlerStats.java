package com.commonbattle.game.player;

/**
 * 玩家客户端命令 Netty handler 统计。
 */
public record NettyPlayerClientCommandHandlerStats(long acceptedCommands, long failedCommands) {
}
