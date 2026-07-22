package com.commonbattle.core;

import java.util.List;

/**
 * 怒气满时自动释放的技能配置。
 * 具体游戏可以把技能效果写成数据或脚本，再挂到拥有怒气条的实体上。
 */
public record RageSkillComponent(String skillId, List<Effect> effects) implements Component {
    public RageSkillComponent {
        effects = List.copyOf(effects);
    }
}
