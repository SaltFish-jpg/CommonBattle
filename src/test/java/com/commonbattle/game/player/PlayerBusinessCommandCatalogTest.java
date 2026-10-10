package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBusinessCommandCatalogTest {
    @Test
    void catalogProvidesStableOperationsForDispatcherBinding() {
        assertEquals(List.of(
                PlayerBusinessOperations.BAG_CONSUME_ITEM,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                PlayerBusinessOperations.ACTIVITY_CLAIM,
                PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
                PlayerBusinessOperations.SHOP_BUY,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                PlayerBusinessOperations.BATTLE_SWEEP_STAGE,
                PlayerBusinessOperations.TASK_CLAIM,
                PlayerBusinessOperations.ACHIEVEMENT_CLAIM
        ), PlayerBusinessCommandCatalog.operations());

        assertEquals(2, PlayerBusinessCommandCatalog.descriptorsByOperation(PlayerBusinessOperations.SHOP_BUY).size());
        assertTrue(PlayerBusinessCommandCatalog.descriptors().stream()
                .anyMatch(descriptor -> descriptor.domain().equals("battle")));

        PlayerBusinessCommandCatalogStats stats = PlayerBusinessCommandCatalog.view().commandCatalogStats();
        assertEquals(10, stats.descriptorCount());
        assertEquals(9, stats.operationCount());
        assertEquals(10, stats.commandTypeCount());
        assertEquals(2, stats.descriptorsByDomain().get("battle"));
        assertEquals(2, stats.descriptorsByDomain().get("shop"));
    }

    @Test
    void everyCatalogCommandHasProtobufPayloadCodec() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(
                PayloadCodecRegistry.commonDefaults()
        );

        for (PlayerBusinessCommandDescriptor descriptor : PlayerBusinessCommandCatalog.descriptors()) {
            PlayerBusinessCommand<?> command = sampleCommands().get(descriptor.commandType());

            EncodedPayload encoded = registry.encode(command);
            Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

            assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
            assertInstanceOf(descriptor.commandType(), decoded);
            assertEquals(descriptor.operation(), ((PlayerBusinessCommand<?>) decoded).operation());
            assertTrue(registry.hasProtobufCodec(descriptor.responseType()));
        }
        PlayerBusinessCommandProtocolValidator.validate(registry);
    }

    @Test
    void bindingValidatorRejectsMissingAndUnexpectedOperations() {
        IllegalStateException missing = assertThrows(IllegalStateException.class, () ->
                PlayerBusinessCommandBindingValidator.validate(
                        PlayerBusinessCommandCatalog.operations(),
                        Set.of(PlayerBusinessOperations.BAG_CONSUME_ITEM)
                ));
        IllegalStateException unexpected = assertThrows(IllegalStateException.class, () ->
                PlayerBusinessCommandBindingValidator.validate(
                        List.of(PlayerBusinessOperations.BAG_CONSUME_ITEM),
                        Set.of(PlayerBusinessOperations.BAG_CONSUME_ITEM, "test.debug")
                ));

        assertTrue(missing.getMessage().contains("missing="));
        assertTrue(unexpected.getMessage().contains("unexpected=[test.debug]"));
    }

    @Test
    void protocolValidatorRejectsRegistryMissingBusinessCodecs() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PlayerBusinessCommandProtocolValidator.validate(PayloadCodecRegistry.commonDefaults()));

        assertTrue(error.getMessage().contains("Missing player business protobuf codecs"));
        assertTrue(error.getMessage().contains(ConsumeBagItemCommand.class.getName()));
    }

    private static Map<Class<? extends PlayerBusinessCommand<?>>, PlayerBusinessCommand<?>> sampleCommands() {
        return Map.ofEntries(
                Map.entry(ConsumeBagItemCommand.class, new ConsumeBagItemCommand("stamina_potion", 2)),
                Map.entry(ActivityProgressCommand.class, new ActivityProgressCommand("kill-3", 3)),
                Map.entry(ClaimActivityCommand.class, new ClaimActivityCommand("daily-login")),
                Map.entry(UseExpItemsCommand.class, new UseExpItemsCommand(2)),
                Map.entry(BuyShopItemCommand.class, new BuyShopItemCommand("order-10001-1", "growth_pack", 1)),
                Map.entry(BuyShopItemAsyncCommand.class,
                        new BuyShopItemAsyncCommand("order-10001-2", "limited_pack", 1)),
                Map.entry(BattleStageClearCommand.class,
                        new BattleStageClearCommand("settle-10001-1", "forest-1")),
                Map.entry(BattleStageSweepCommand.class,
                        new BattleStageSweepCommand("sweep-10001-1", "forest-1")),
                Map.entry(ClaimTaskCommand.class, new ClaimTaskCommand("task-clear-forest")),
                Map.entry(ClaimAchievementCommand.class, new ClaimAchievementCommand("achievement-clear-forest"))
        );
    }
}
