package com.spirit.koil.api.automation.runtime;

import com.spirit.koil.api.automation.ktl.KtlCompilerService;

import java.util.Map;

public record ExecutionPlan(KtlCompilerService.CompiledTaskTemplate template, Map<String, Object> params) {
}
