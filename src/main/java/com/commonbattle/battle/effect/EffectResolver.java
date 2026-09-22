package com.commonbattle.battle.effect;



import com.commonbattle.battle.context.BattleContext;
import java.util.List;

/**
 * 按顺序执行效果。
 * 后续可通过自定义解析器加入批处理、回滚、确定性回放检查或优先级队列。
 */
public final class EffectResolver {
    public void resolve(BattleContext context, List<Effect> effects) {
        for (Effect effect : effects) {
            effect.apply(context);
        }
    }
}
