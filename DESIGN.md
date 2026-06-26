# CommonBattle 设计总结

## 目标

CommonBattle 的目标不是提供一套固定战斗流程去适配所有游戏，而是提供一套通用结算内核，让不同玩法通过规则集、命令、效果、触发器和事件组合出自己的战斗流程。

核心模型可以概括为：

```text
Battle = State + Command + RuleSet + Effect + Trigger + Event + Log
```

当前工程使用 Maven 构建，目标 JDK 为 21。核心代码位于 `com.commonbattle.core`，不同玩法示例位于 `com.commonbattle.example.*`。

## 架构原则

- 核心层只保留通用结算能力，不写死回合制、卡牌、自动战斗、塔防等具体玩法。
- 玩法差异通过 `RuleSet`、`Command`、`Effect`、`Component`、`Trigger`、`Event` 扩展。
- 技能、卡牌、Buff、AI 行为都应组合原子 `Effect`，避免把业务流程硬编码进核心。
- `Trigger` 用来控制关键结算时机，适合被动、Buff、装备、光环等有顺序要求的逻辑。
- `Event` 用来广播已发生的事实，适合表现、统计、任务、成就、回放收集等解耦模块。
- 所有关键结算都写入 `BattleLog`，用于客户端表现、回放、调试、断线恢复和同步校验。

## 核心模块

### BattleContext

`BattleContext` 是一场战斗的运行时容器，持有：

- `BattleState`
- `RuleSet`
- `CommandQueue`
- `EventBus`
- `TriggerSystem`
- `EffectResolver`
- `RandomService`
- `BattleLog`

外部输入或 AI 决策通过 `submit(Command)` 进入命令队列，再由 `runUntilIdle()` 按 FIFO 顺序执行。Effect 可以继续提交新命令，因此自动战斗、连锁技能、召唤后追加行动等流程可以自然串起来。

### BattleState

`BattleState` 保存战场快照：

- 实体集合
- 实体组件
- 全局组件
- 时间或 tick

`Entity` 是通用战斗对象，可以代表单位、卡牌、召唤物、建筑、子弹、陷阱等。`Component` 只保存数据，例如：

- `HealthComponent`
- `AttributeComponent`
- `FactionComponent`
- `BuffComponent`

战局级数据可以通过全局组件保存，例如回合顺序、波次状态、公共资源池等。

### Command

`Command` 表达外部输入或 AI 意图，例如：

- `AttackCommand`
- `CastSkillCommand`
- `ApplyBuffCommand`
- 示例里的 `PlayCardCommand`
- 示例里的 `TickCommand`
- 示例里的 `EndTurnCommand`

命令本身不直接修改状态，而是在规则校验通过后展开为一组 `Effect`。

### RuleSet

`RuleSet` 是玩法规则插件，负责判断命令是否合法。

当前已有：

- `BasicRuleSet`：基础攻击、施法、存活和控制状态校验。
- `TurnRuleSet`：回合制当前行动者校验。
- `CardRuleSet`：卡牌是否在手牌、法力是否足够、目标是否合法。
- `AutoBattleRuleSet`：自动战斗 tick 是否还能找到双方存活单位。

扩展新玩法时，优先新增或组合 `RuleSet`，不要把玩法阶段判断写进核心。

### Effect

`Effect` 是原子结算能力。当前已有：

- `DealDamageEffect`
- `AddBuffEffect`
- `ModifyAttributeEffect`
- `AreaDamageEffect`
- 示例里的 `MoveCardEffect`
- 示例里的 `SpendManaEffect`
- 示例里的 `AdvanceTurnEffect`
- 示例里的 `SubmitCommandEffect`

技能、卡牌、Buff 都应组合 Effect。例如一个群体冰冻火球可以组合：

```text
AreaDamageEffect + AddBuffEffect(FROZEN)
```

### Buff 和状态

Buff 由 `Buff` 和 `BuffComponent` 表达。当前支持：

- 属性加成，例如增加 `attack`
- 控制状态，例如 `STUNNED`、`FROZEN`

属性读取通过 `AttributeValueResolver` 合并基础属性和 Buff 加成。行动限制由 `BasicRuleSet` 解释控制状态；不同游戏也可以在自己的 `RuleSet` 中给状态定义不同含义。

### Trigger

`TriggerSystem` 管理结算时机。当前基础时机包括：

- `BEFORE_COMMAND`
- `AFTER_COMMAND`
- `BEFORE_DAMAGE`
- `AFTER_DAMAGE`
- `BEFORE_DEATH`
- `AFTER_DEATH`
- `TURN_START`
- `TURN_END`

Trigger 适合处理有顺序要求的逻辑。例如：

- 伤害前增伤或减伤
- 伤害后吸血
- 回合开始结算毒伤
- 回合结束减少 Buff 持续时间
- 死亡前保命
- 死亡后召唤

### Event

`EventBus` 用于广播事实。当前已有：

- `UnitDamagedEvent`
- `BuffAddedEvent`
- `SkillCastEvent`

Event 不应该承担核心结算顺序控制。需要顺序控制时使用 Trigger，需要事实通知时使用 Event。

### BattleLog

`BattleLog` 是追加式日志。当前关键结算都会写日志，例如：

- `command.completed`
- `effect.damage`
- `effect.area_damage`
- `buff.added`
- `skill.cast`
- `card.mana_spent`
- `card.zone_changed`
- `turn.advance`

日志面向客户端表现、回放、调试、断线重连和同步校验。

## 推荐执行链

```text
外部输入 / AI 决策
-> Command
-> RuleSet 校验是否可执行
-> Trigger: BEFORE_COMMAND
-> Command 展开 Effect
-> EffectResolver 执行效果
-> Trigger: BEFORE_DAMAGE / AFTER_DAMAGE 等局部时机
-> 修改 BattleState
-> 发布 Event
-> Effect 可追加 Command
-> Trigger: AFTER_COMMAND
-> 写 BattleLog
-> 输出给客户端表现
```

## 已有示例

### 基础战斗示例

`SimpleBattleExample` 演示两个单位之间的基础攻击：

- 创建两个实体
- 攻击者拥有 `attack` 属性
- 注册 `BEFORE_DAMAGE` 触发器加伤
- 提交 `AttackCommand`
- 产生伤害、事件和日志

### 回合制示例

包路径：`com.commonbattle.example.turn`

演示内容：

- `TurnComponent` 保存行动顺序
- `TurnRuleSet` 限制只有当前行动者能攻击或结束回合
- `EndTurnCommand` 推进回合
- 示例中还演示属性 Buff 和眩晕状态

适合扩展成 RPG、战棋、SLG、回合制卡牌战斗。

### 卡牌示例

包路径：`com.commonbattle.example.card`

演示内容：

- `ZoneComponent` 表达卡牌区域
- `ManaComponent` 表达资源
- `PlayCardCommand` 组合花费法力、移动卡牌和造成伤害
- 示例中还演示冰冻状态

适合扩展成 TCG、DBG、肉鸽卡牌、卡牌 RPG。

### 自动战斗示例

包路径：`com.commonbattle.example.auto`

演示内容：

- `TickCommand` 推进自动战斗
- AI 在 tick 中选择目标并提交攻击命令
- `CastSkillCommand` 组合群伤技能
- `AreaDamageEffect` 对多个敌人造成伤害

适合扩展成挂机战斗、自动棋、数值 RPG、轻量实时战斗。

## 如何扩展一种新游戏

推荐步骤：

1. 定义该游戏需要的数据组件。
   例如位置、法力、手牌区、冷却、波次、路径、怒气、护盾。

2. 定义外部输入或 AI 行为命令。
   例如移动、施法、出牌、召唤、结束回合、tick。

3. 定义玩法规则集。
   在 `RuleSet` 中校验阶段、资源、目标、冷却、控制状态和行动权限。

4. 组合或新增原子效果。
   优先复用 `DealDamageEffect`、`AddBuffEffect`、`AreaDamageEffect` 等通用效果。

5. 在关键时机注册 Trigger。
   用于处理被动、Buff、装备、光环、死亡前后等顺序敏感逻辑。

6. 发布 Event 并写 BattleLog。
   Event 面向解耦模块，BattleLog 面向表现、回放和校验。

7. 增加一个最小测试和一个示例入口。
   测试固定规则行为，示例展示完整日志链路。

## 后续可演进方向

- Buff 持续时间统一 tick/turn 衰减。
- 死亡流程补齐 `BEFORE_DEATH`、`AFTER_DEATH`。
- 效果优先级和结算栈。
- 可序列化 BattleLog 和回放驱动。
- 随机数日志化，支持确定性重放。
- 目标选择器数据化，例如最近目标、最低血量、随机敌人、范围内敌人。
- 技能配置外部化，例如 JSON/YAML/表格驱动。
- 客户端表现事件和战斗结算日志分层。
- 快照和断线重连恢复。
- 多 RuleSet 组合，例如 `TurnRuleSet + CardRuleSet + BuffRuleSet`。

## 验证

当前项目使用 Maven 测试：

```powershell
mvn test
```

现有测试覆盖：

- 基础攻击链路
- 触发器和事件
- 属性 Buff
- 眩晕/冰冻行动限制
- 群体伤害
- 回合制示例
- 卡牌示例
- 自动战斗示例
