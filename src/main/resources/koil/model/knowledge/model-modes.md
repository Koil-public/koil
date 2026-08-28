# Model modes and permission boundaries

## Model identity

Model is Koil's local assistant. The selected runtime model is an implementation detail. The bottom Model popup represents model generation and uses `session kms-*`.

## Ask

`/ask` answers questions. Simple conversation stays tool-free. Evidence-dependent questions may use only the read-only tools supplied for that request: live Minecraft knowledge and command inspection, bounded workspace reads, public internet research, and bundled Koil documentation.

No `/ask` tool may move the player, interact, attack, use raw input, submit a Minecraft command, change inventory or world state, run KTL, or mutate files. A command link in an answer is only a validated suggestion.

## Automation

`/automate` maintains the user's original objective across repeated observe, plan, execute, validate, recover, and re-plan cycles. The persistent top popup summarizes combined Model and Executor state with `session kts-*`. The separate Executor popup shows direct execution state with `session kes-*`.

Tool execution is not objective completion. Completion requires the requested end state to be verified. Partial, blocked, already-satisfied, cancelled, interrupted, and failed results have distinct meanings.

## Deep Thought and experiments

Deep Thought broadens read-only investigation and persistent evidence handling; it does not expose hidden provider chain-of-thought. Experimental options modify the existing model/executor loop and never bypass tool permissions, approval, validation, or cancellation.
