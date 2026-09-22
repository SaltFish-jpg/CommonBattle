package com.commonbattle.battle.example.auto;




import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 自动战斗心跳命令。
 * 每次心跳推进时间，并让第一个存活玩家单位攻击第一个存活敌方单位。
 */
public record TickCommand() implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        context.state().advanceTick();
        EntityId attacker = AutoBattleRuleSet.firstAliveInFaction(context, "player").id();
        EntityId target = AutoBattleRuleSet.firstAliveInFaction(context, "enemy").id();
        return List.of(new SubmitCommandEffect(new AttackCommand(attacker, target)));
    }
}
