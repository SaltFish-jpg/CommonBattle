package com.commonbattle.example.auto;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Effect;
import com.commonbattle.core.Entity;
import com.commonbattle.core.EntityId;

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
