package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientErrorCode;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyPlayerClientCommandHandlerTest {
    @Test
    void handlerForwardsDecodedCommandToIngressWithoutBusinessLogic() {
        List<PlayerClientCommandEnvelope> accepted = new ArrayList<>();
        NettyPlayerClientCommandHandler handler = new NettyPlayerClientCommandHandler(command -> {
            accepted.add(command);
            return PlayerClientCommandAcceptResult.acceptedResult();
        });
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        PlayerClientCommandEnvelope envelope = new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                1,
                "test.echo",
                "payload"
        );

        channel.writeInbound(envelope);

        assertEquals(List.of(envelope), accepted);
        assertEquals(1, handler.stats().acceptedCommands());
        assertEquals(0, handler.stats().failedCommands());
    }

    @Test
    void handlerCountsRejectedIngressResultWithoutRunningBusinessLogic() {
        NettyPlayerClientCommandHandler handler = new NettyPlayerClientCommandHandler(command ->
                PlayerClientCommandAcceptResult.rejected(
                        PlayerClientErrorCode.COMMAND_INGRESS_FAILED,
                        "ingress unavailable"
                ));
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                1,
                "test.echo",
                "payload"
        ));

        assertEquals(0, handler.stats().acceptedCommands());
        assertEquals(1, handler.stats().failedCommands());
        assertEquals(false, channel.isOpen());
    }

    @Test
    void handlerCountsRejectedIngressResultWithoutClosingWhenResponseIsAlreadyDelivered() {
        NettyPlayerClientCommandHandler handler = new NettyPlayerClientCommandHandler(command ->
                PlayerClientCommandAcceptResult.rejectedWithoutClosing(
                        PlayerClientErrorCode.COMMAND_BACKPRESSURED,
                        "mailbox_pressure:target",
                        java.time.Duration.ofMillis(50)
                ));
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                1,
                "test.echo",
                "payload"
        ));

        assertEquals(0, handler.stats().acceptedCommands());
        assertEquals(1, handler.stats().failedCommands());
        assertEquals(true, channel.isOpen());
    }
}
