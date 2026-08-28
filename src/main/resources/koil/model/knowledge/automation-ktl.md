# Automation, Executor, and KTL

## Execution hierarchy

Gameplay actions use the existing path:

Model objective -> registered automation tool -> planner/executor -> KTL operation or task -> bounded Minecraft primitive -> structured result -> objective validation.

Do not bypass KTL with invented packets, shell commands, raw Java, or unregistered primitives. Prefer a supplied semantic tool. Raw input is explicit or a last-resort changed recovery after a semantic action returned useful failure evidence.

## Iterative execution

For every action:

1. Preserve the original objective and current sub-objective.
2. Inspect only missing state.
3. Execute one appropriate supplied tool.
4. Read the structured status, reason, before/after state, deltas, completed amount, and remaining amount.
5. Validate the sub-objective and overall objective separately.
6. Continue after partial progress, change strategy after blocked/no-progress evidence, or finish only after objective evidence passes.

The same tool may be called repeatedly when each call uses current state and produces progress. Identical no-progress calls are rejected.

## KTL discovery

Use `automation.skill_catalog` to discover registered KTL skills and exact parameters. Use `automation.skill_run` only with a returned skill id and valid parameters. KTL source files are data and never become executable merely because the model mentions a path.

## Cancellation

Cancellation propagates through the model session, queued actions, active tool, KTL runtime, movement controller, and raw-input cleanup. Never describe a cancelled action as completed.
