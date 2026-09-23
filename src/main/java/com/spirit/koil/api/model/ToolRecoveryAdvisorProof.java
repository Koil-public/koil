package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.retrieval.AutomationExecutionExperience;

import java.util.List;

/** Regression proof for deriving a recovery sequence when stored tools are absent. */
public final class ToolRecoveryAdvisorProof {
    private ToolRecoveryAdvisorProof() {}

    public static void main(String[] args) {
        AutomationExecutionExperience experience = new AutomationExecutionExperience(
                1L, "internet.fetch", "completed", "completed", "", true, true, false, "",
                "internet.fetch=failed(failure=insufficient_content) -> internet.scrape=completed", "", "", 1L, 1L, 0.9D);
        ModelToolResult failed = new ModelToolResult("call", "internet.fetch", "failed", new JsonObject(),
                "insufficient_content", "content missing");
        ToolRecoveryTrajectory trajectory = ToolRecoveryAdvisor.advise(List.of(experience),
                new ModelToolCall("call", "internet.fetch", new JsonObject()), failed);
        if (!trajectory.available() || !trajectory.recoveryTools().equals(List.of("internet.scrape"))) {
            throw new IllegalStateException("derived recovery tools were not preserved: " + trajectory);
        }
        System.out.println("Tool recovery advisor proof passed.");
    }
}
