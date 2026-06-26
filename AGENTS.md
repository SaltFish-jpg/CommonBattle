# AGENTS.md

## Comment Guidelines

- Prefer clear names and small classes over explanatory comments. Add comments only when they help a reader understand intent, extension boundaries, ordering, or non-obvious tradeoffs.
- Public extension points should have concise Javadoc. This includes core interfaces and framework-facing types such as `BattleContext`, `BattleState`, `Command`, `RuleSet`, `Effect`, `Trigger`, `Event`, `EventBus`, `BattleLog`, and reusable gameplay effects.
- Javadoc should explain what the type is for and when to implement or use it. Avoid restating signatures, field names, or obvious getter behavior.
- For gameplay examples, add short Javadoc to rule sets, factories, commands, and runnable examples when it clarifies how a game type plugs into the common battle kernel.
- Keep implementation comments rare. Use them only before complex settlement logic, ordering-sensitive trigger behavior, deterministic replay concerns, or code that would otherwise require careful reconstruction by the reader.
- Do not add noisy comments such as "sets the value", "gets the name", or "loop through items". If the code says it plainly, leave it alone.
- Update comments when behavior changes. Stale comments are worse than missing comments.
- Keep comments in English for code-level documentation unless a surrounding file already uses Chinese consistently.

## Project Notes

- This repository is a Maven project targeting JDK 21.
- Keep the core battle framework generic. Put game-type-specific examples under `com.commonbattle.example.*`.
- Verify changes with `mvn test` before claiming the work is complete.
