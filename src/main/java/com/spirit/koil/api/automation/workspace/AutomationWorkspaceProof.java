package com.spirit.koil.api.automation.workspace;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.cli.AutomationCliRow;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.screen.ExternalUiWindow;

import java.util.List;

/** Headless contract proof for trace preservation and external presentation parsing. */
public final class AutomationWorkspaceProof {
    private AutomationWorkspaceProof() {
    }

    public static void main(String[] args) {
        AutomationCliRow row = new AutomationCliRow(
                "move:1", "execution", "navigate", 1, "[run ]", "move_to", "42 / 100 blocks",
                true, true, "target position", "walkable route", "start=0,64,0", "end=42,64,0",
                "", "replan if obstructed", "movement/core/walk_to.ktl:18"
        );
        AutomationCliSnapshot executor = new AutomationCliSnapshot(
                "kes-00042", "AUTOMATION", "player", "DEBUG",
                List.of(new AutomationCliRow("header:input", "header", "header", 0, "[info]", "raw_input", "walk forward", true, true,
                        "", "", "", "", "", "", ""), row)
        );
        AutomationWorkspaceTrace active = new AutomationWorkspaceTrace(
                "executor-kes-00042", "executor", "", "navigating", 10L, 20L, 0L, null, executor
        );
        require(active.active(), "navigating trace should remain active");
        require(AutomationWorkspaceTrace.titleFor(executor, "fallback").equals("walk forward"), "objective title should come from typed input");
        AutomationWorkspaceTrace complete = active.withExecutor(executor, "complete", 30L);
        require(!complete.active() && complete.completedAtMillis() == 30L, "terminal executor trace should freeze completion time");

        JsonObject data = new JsonObject();
        data.addProperty("toolId", "movement.walk_to");
        data.addProperty("source", "movement/core/walk_to.ktl:18");
        ModelGenerationHudState.ActivityEvent event = new ModelGenerationHudState.ActivityEvent(
                ModelGenerationHudState.ActivityEventType.TOOL_START,
                ModelActivityState.NAVIGATING,
                "Following the validated route.",
                15L,
                "tool-1",
                data
        );
        ModelActivityPresentation.TraceSnapshot model = new ModelActivityPresentation.TraceSnapshot(
                "Reach the marker", List.of(event), null, null, false,
                new ModelUsage(100, 20, 40, 2L, 8L, 12.5D), 10L, 30L
        );
        require(ModelActivityPresentation.render(model).contains("movement.walk_to"), "model trace should retain structured tool evidence");
        require(ExternalUiWindow.Presentation.from("external") == ExternalUiWindow.Presentation.EXTERNAL,
                "external presentation token should remain explicit");
        require(ExternalUiWindow.Presentation.from("popup") == ExternalUiWindow.Presentation.POPUP,
                "popup presentation token should remain explicit");
        System.out.println("Automation Workspace proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
