package com.commonbattle.game.session;

/**
 * 单个客户端连接的非阻塞出站写出器。
 * Netty 实现应只把消息写入 Channel，不应在这里执行业务逻辑。
 */
@FunctionalInterface
public interface PlayerOutboundWriter {
    boolean write(PlayerOutboundMessage message);
}
