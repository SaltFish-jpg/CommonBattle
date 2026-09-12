package com.commonbattle.game.session;

/**
 * 单个玩家 Netty 出站写出器统计。
 */
public record NettyPlayerOutboundWriterStats(
        boolean active,
        boolean writable,
        long acceptedWrites,
        long failedWrites
) {
}
