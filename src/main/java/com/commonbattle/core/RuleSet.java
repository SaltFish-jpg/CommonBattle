package com.commonbattle.core;

/**
 * 可插拔的玩法规则。
 * 实现类负责判断命令在当前战斗模式、阶段、资源状态或时机下是否合法。
 */
public interface RuleSet {
    void validate(BattleContext context, Command command);
}
