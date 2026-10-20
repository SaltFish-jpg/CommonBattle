package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializedPlayerBusinessResponseStoreTest {
    @Test
    void roundTripsResponseEnvelopeAndTypedBusinessPayload() {
        PayloadCodecRegistry codecs = PlayerBusinessCommandPayloadCodecs.registerTo(
                PayloadCodecRegistry.commonDefaults());
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedPlayerBusinessResponseStore first = new SerializedPlayerBusinessResponseStore(
                bytes,
                new PlayerBusinessResponseSnapshotCodec(codecs)
        );
        PlayerCommand command = new PlayerCommand(
                10001L,
                "session-1",
                1,
                3,
                PlayerBusinessOperations.SHOP_BUY,
                new BuyShopItemCommand("order-3", "growth_pack", 1)
        );
        ShopPurchaseResult result = ShopPurchaseResult.rejected(
                ShopPurchaseStatus.NOT_ENOUGH_CURRENCY,
                "growth_pack",
                1,
                0,
                0
        );
        first.save(new PlayerBusinessResponseSnapshot(
                command.playerId(),
                java.util.List.of(PlayerBusinessResponse.success(command, result))
        ));

        SerializedPlayerBusinessResponseStore rebuilt = new SerializedPlayerBusinessResponseStore(
                bytes,
                new PlayerBusinessResponseSnapshotCodec(codecs)
        );
        PlayerBusinessResponse restored = rebuilt.load(command.playerId()).orElseThrow().responses().getFirst();

        assertEquals(result, restored.payload());
        assertEquals(command.sequence(), restored.sequence());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, restored.status());
    }
}
