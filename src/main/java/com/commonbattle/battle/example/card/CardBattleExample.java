package com.commonbattle.battle.example.card;




import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.buff.StatusEffect;
import com.commonbattle.battle.command.ApplyBuffCommand;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
/**
 * 可运行的卡牌示例：玩家对目标打出带冻结效果的火球牌。
 */
public final class CardBattleExample {
    private CardBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        Entity player = CardExampleFactory.createPlayer(state, 5);
        Entity fireball = CardExampleFactory.createDamageCard(state, "fireball", 3, 7);
        Entity target = CardExampleFactory.createTarget(state, 30);
        BattleContext battle = CardExampleFactory.createBattle(state);

        battle.submit(new PlayCardCommand(player.id(), fireball.id(), target.id()));
        battle.submit(new ApplyBuffCommand(target.id(), Buff.status("frozen_by_card", StatusEffect.FROZEN, 1)));
        battle.runUntilIdle();

        battle.log().entries().forEach(CardBattleExample::print);
    }

    private static void print(BattleLogEntry entry) {
        System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
    }
}
