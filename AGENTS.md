# AGENTS.md

## Comment Guidelines

- Prefer clear names and small classes over explanatory comments. Add comments only when they help a reader understand intent, extension boundaries, ordering, or non-obvious tradeoffs.
- 关键结算逻辑必须补充简洁注释。包括伤害结算顺序、触发器递归保护、命令/效果入队边界、回放确定性，以及仅靠命名难以还原意图的玩法机制。
- Public extension points should have concise Javadoc. This includes core interfaces and framework-facing types such as `BattleContext`, `BattleState`, `Command`, `RuleSet`, `Effect`, `Trigger`, `Event`, `EventBus`, `BattleLog`, and reusable gameplay effects.
- Javadoc should explain what the type is for and when to implement or use it. Avoid restating signatures, field names, or obvious getter behavior.
- For gameplay examples, add short Javadoc to rule sets, factories, commands, and runnable examples when it clarifies how a game type plugs into the common battle kernel.
- Keep implementation comments rare. Use them only before complex settlement logic, ordering-sensitive trigger behavior, deterministic replay concerns, or code that would otherwise require careful reconstruction by the reader.
- Do not add noisy comments such as "sets the value", "gets the name", or "loop through items". If the code says it plainly, leave it alone.
- Update comments when behavior changes. Stale comments are worse than missing comments.
- Keep code-level comments and Javadoc in Chinese unless a surrounding file already uses another language consistently.

## Project Notes

- This repository is a Maven project targeting JDK 21.
- Keep the battle framework generic. Put battle code under `com.commonbattle.battle.*` and game-type-specific battle examples under `com.commonbattle.battle.example.*`.
- Verify changes with `mvn test` before claiming the work is complete.
