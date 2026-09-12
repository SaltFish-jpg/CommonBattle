package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.chat.ChatDelivery;
import com.commonbattle.game.chat.ChatPayloadCodecs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldPrepender;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyPlayerOutboundWriterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void playerOutboundHubWritesClientFrameThroughNettyWriter() {
        PayloadCodecRegistry registry = ChatPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);
        EmbeddedChannel serverSide = new EmbeddedChannel(
                new LengthFieldPrepender(4),
                new NettyPlayerClientFrameEncoder(codec)
        );
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        NettyPlayerOutboundWriter writer = new NettyPlayerOutboundWriter(serverSide);
        ChatDelivery delivery = new ChatDelivery("world:main:0", 10002L, "player-10002", "hello", 1, CLOCK.instant());

        assertTrue(hub.connect(session, writer));
        PlayerOutboundDeliveryResult result = hub.deliver(new PlayerOutboundEnvelope(
                Set.of(10001L),
                "chat.delivery",
                delivery
        ));

        assertEquals(new PlayerOutboundDeliveryResult(1, 0, 0, 0), result);
        ByteBuf lengthHeader = serverSide.readOutbound();
        ByteBuf frameBodyBuffer = serverSide.readOutbound();
        assertNotNull(lengthHeader);
        assertNotNull(frameBodyBuffer);
        int frameLength = lengthHeader.readInt();
        byte[] frameBody = new byte[frameLength];
        frameBodyBuffer.readBytes(frameBody);
        PlayerClientEnvelope envelope = codec.decode(frameBody);
        assertNotNull(envelope);
        assertEquals(10001L, envelope.playerId());
        assertEquals("chat.delivery", envelope.topic());
        assertEquals(1, envelope.sequence());
        ChatDelivery decoded = assertInstanceOf(ChatDelivery.class, envelope.payload());
        assertEquals(delivery, decoded);
        assertEquals(1, writer.stats().acceptedWrites());
    }

    @Test
    void inactiveChannelIsRejectedForOfflineFallback() {
        PayloadCodecRegistry registry = ChatPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        EmbeddedChannel channel = new EmbeddedChannel(
                new LengthFieldPrepender(4),
                new NettyPlayerClientFrameEncoder(new ProtoPlayerClientCodec(registry))
        );
        channel.close();
        NettyPlayerOutboundWriter writer = new NettyPlayerOutboundWriter(channel);
        PlayerOutboundMessage message = new PlayerOutboundMessage(10001L, "chat.delivery", "hello", 1, CLOCK.instant());

        boolean accepted = writer.write(message);

        assertFalse(accepted);
        assertEquals(1, writer.stats().failedWrites());
    }

    @Test
    void clientFrameDecoderRestoresEnvelopePayload() {
        PayloadCodecRegistry registry = ChatPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new NettyPlayerClientFrameDecoder(codec));
        ChatDelivery delivery = new ChatDelivery("world:main:0", 10002L, "player-10002", "hello", 1, CLOCK.instant());
        PlayerClientEnvelope source = new PlayerClientEnvelope(
                10001L,
                "chat.delivery",
                delivery,
                7,
                CLOCK.instant()
        );

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(codec.encode(source))));
        PlayerClientEnvelope decoded = channel.readInbound();

        assertEquals(source.playerId(), decoded.playerId());
        assertEquals(source.topic(), decoded.topic());
        assertEquals(source.sequence(), decoded.sequence());
        assertEquals(source.createdAt(), decoded.createdAt());
        assertEquals(delivery, decoded.payload());
    }
}
