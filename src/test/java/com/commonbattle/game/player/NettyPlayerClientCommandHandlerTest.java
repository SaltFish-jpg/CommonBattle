package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyPlayerClientCommandHandlerTest {
    @Test
    void handlerForwardsDecodedCommandToIngressWithoutBusinessLogic() {
        List<PlayerClientCommandEnvelope> accepted = new ArrayList<>();
        NettyPlayerClientCommandHandler handler = new NettyPlayerClientCommandHandler(accepted::add);
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
}
