package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.automation.runtime.AutomationExecutor;
import com.spirit.koil.api.automation.runtime.ExecutionPlan;
import com.spirit.koil.api.automation.runtime.InterpretationResult;

public final class AutomationInterpreter {
    private final KtlCompilerService compilerService;
    private final AutomationExecutor executor;

    public AutomationInterpreter(KtlCompilerService compilerService) {
        this.compilerService = compilerService;
        this.executor = new AutomationExecutor(compilerService);
    }

    public void execute(AutomationRequest request) {
        InterpretationResult result = this.compilerService.interpret(request);
        ExecutionPlan plan = result.plan();
        this.executor.execute(plan, result);
    }

    public void executePrepared(InterpretationResult result) {
        ExecutionPlan plan = result.plan();
        this.executor.execute(plan, result);
    }

    public void tick() {
        this.executor.tick();
    }

    public void cancel(String reason) {
        this.executor.cancel(reason);
    }

    public boolean isActive() {
        return this.executor.hasActiveExecutions();
    }
}
