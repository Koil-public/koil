package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.model.ModelExperimentalFeatures;

public final class AutomationModeController {
    private static volatile boolean automationMode;
    private static volatile ModeState modeState = ModeState.OFF;
    private static volatile ApprovalPolicy approvalPolicy = ApprovalPolicy.STANDARD;
    private static volatile boolean deepThinkingEnabled;
    private static volatile boolean deepThinkingActive;
    private static volatile boolean planningModeEnabled;
    private static volatile boolean planningActive;
    private static volatile boolean experimentalCompactAgentEnabled;
    private static volatile boolean verificationEnabled;
    private static volatile String detail = "";

    private AutomationModeController() {
    }

    public static boolean isAutomationMode() {
        return automationMode;
    }

    public static void setAutomationMode(boolean enabled) {
        boolean wasEnabled = automationMode;
        automationMode = enabled;
        if (!enabled || !wasEnabled) {
            approvalPolicy = ApprovalPolicy.STANDARD;
            deepThinkingEnabled = false;
            deepThinkingActive = false;
            planningModeEnabled = false;
            planningActive = false;
            verificationEnabled = false;
            if (!enabled) {
                experimentalCompactAgentEnabled = false;
            }
        }
        modeState = enabled ? ModeState.CONNECTING : ModeState.OFF;
        detail = enabled ? "connecting to local model" : "";
        AutomationRuntimeStatus.idle(detail);
        AutomationPresenceState.updateLocalMode(enabled);
        AutomationPresenceState.updateLocal(enabled ? "starting" : "idle", detail);
        if (!enabled) {
            AutomationChatHudState.hide();
        }
    }

    /**
     * Enables approval-free execution for registered model capabilities during
     * this Automation Mode session. This does not expand the capability
     * registry or bypass Minecraft's player permissions.
     */
    public static void enableUnrestrictedMode() {
        setUnrestrictedModeEnabled(true);
    }

    public static void setUnrestrictedModeEnabled(boolean enabled) {
        if (enabled && !automationMode) {
            setAutomationMode(true);
        }
        approvalPolicy = enabled && automationMode ? ApprovalPolicy.UNRESTRICTED : ApprovalPolicy.STANDARD;
        detail = approvalPolicy == ApprovalPolicy.UNRESTRICTED
                ? "unrestricted: registered capabilities require no per-action Koil approval"
                : automationMode ? "standard approvals enabled" : "";
        AutomationPresenceState.updateLocal(
                approvalPolicy == ApprovalPolicy.UNRESTRICTED ? "warning" : automationMode ? "idle" : "off",
                detail
        );
    }

    public static boolean isUnrestrictedMode() {
        return automationMode && approvalPolicy == ApprovalPolicy.UNRESTRICTED;
    }

    public static ApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    public static void setDeepThinkingEnabled(boolean enabled) {
        deepThinkingEnabled = automationMode && enabled;
        if (!deepThinkingEnabled) {
            deepThinkingActive = false;
        }
    }

    public static boolean isDeepThinkingEnabled() {
        return automationMode && deepThinkingEnabled;
    }

    public static void setDeepThinkingActive(boolean active) {
        deepThinkingActive = automationMode && deepThinkingEnabled && active;
    }

    public static boolean isDeepThinkingActive() {
        return automationMode && deepThinkingActive;
    }

    public static void setPlanningModeEnabled(boolean enabled) {
        planningModeEnabled = automationMode && enabled;
        if (!planningModeEnabled) {
            planningActive = false;
        }
    }

    public static boolean isPlanningModeEnabled() {
        return automationMode && planningModeEnabled;
    }

    public static void setPlanningActive(boolean active) {
        planningActive = automationMode && active;
    }

    public static boolean isPlanningActive() {
        return automationMode && planningActive;
    }

    public static void setExperimentalCompactAgentEnabled(boolean enabled) {
        experimentalCompactAgentEnabled = enabled;
    }

    public static boolean isExperimentalCompactAgentEnabled() {
        return experimentalCompactAgentEnabled;
    }

    public static void setVerificationEnabled(boolean enabled) {
        verificationEnabled = enabled;
    }

    public static boolean isVerificationEnabled() {
        return verificationEnabled;
    }

    public static boolean hasExperimentalFeaturesEnabled() {
        return experimentalCompactAgentEnabled || verificationEnabled || !persistentExperimentalFeatureNames().isEmpty();
    }

    public static java.util.List<String> enabledExperimentalFeatures() {
        java.util.ArrayList<String> enabled = new java.util.ArrayList<>(8);
        if (experimentalCompactAgentEnabled) {
            enabled.add("Compact context agent");
        }
        if (verificationEnabled) {
            enabled.add("Verification");
        }
        enabled.addAll(persistentExperimentalFeatureNames());
        return java.util.List.copyOf(enabled);
    }

    private static java.util.List<String> persistentExperimentalFeatureNames() {
        var settings = ModelExperimentalFeatures.snapshot();
        java.util.ArrayList<String> names = new java.util.ArrayList<>(6);
        if (settings.persistentConversationHistory()) names.add("Persistent Conversation History");
        if (settings.persistentAssociativeMemory()) names.add("Persistent Associative Memory");
        if (settings.gigatokenEnabled()) names.add("gigaToken");
        if (settings.expertPrefetchEnabled()) names.add("Expert Prefetch");
        if (settings.completionModeEnabled()) names.add("Completion Mode");
        if (settings.noFailEnabled()) names.add("No-Fail");
        return java.util.List.copyOf(names);
    }

    public static void ready(String value) {
        if (automationMode) {
            modeState = ModeState.READY;
            detail = value == null ? "" : value;
            AutomationPresenceState.updateLocal("idle", detail);
        }
    }

    public static void executing(String value) {
        if (automationMode) {
            modeState = ModeState.EXECUTING;
            detail = value == null ? "" : value;
            AutomationPresenceState.updateLocal(AutomationStateColors.normalizeState(detail), detail);
        }
    }

    public static void paused(String value) {
        if (automationMode) {
            modeState = ModeState.PAUSED;
            detail = value == null ? "" : value;
            AutomationPresenceState.updateLocal("idle", detail);
        }
    }

    public static void unavailable(String value) {
        modeState = ModeState.UNAVAILABLE;
        detail = value == null ? "local model unavailable" : value;
        automationMode = false;
        approvalPolicy = ApprovalPolicy.STANDARD;
        deepThinkingEnabled = false;
        deepThinkingActive = false;
        planningModeEnabled = false;
        planningActive = false;
        experimentalCompactAgentEnabled = false;
        verificationEnabled = false;
        AutomationPresenceState.updateLocalMode(false);
        AutomationPresenceState.updateLocal("failed", detail);
        AutomationChatHudState.hide();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                automationMode,
                modeState,
                approvalPolicy,
                deepThinkingEnabled,
                deepThinkingActive,
                planningModeEnabled,
                planningActive,
                experimentalCompactAgentEnabled,
                verificationEnabled,
                detail
        );
    }

    public enum ModeState {
        OFF,
        CONNECTING,
        READY,
        EXECUTING,
        PAUSED,
        UNAVAILABLE
    }

    public enum ApprovalPolicy {
        STANDARD,
        UNRESTRICTED
    }

    public record Snapshot(
            boolean enabled,
            ModeState state,
            ApprovalPolicy approvalPolicy,
            boolean deepThinkingEnabled,
            boolean deepThinkingActive,
            boolean planningModeEnabled,
            boolean planningActive,
            boolean experimentalCompactAgentEnabled,
            boolean verificationEnabled,
            String detail
    ) {
        public boolean experimentalFeaturesEnabled() {
            return AutomationModeController.hasExperimentalFeaturesEnabled();
        }

        public java.util.List<String> enabledExperimentalFeatures() {
            java.util.ArrayList<String> enabled = new java.util.ArrayList<>(8);
            if (experimentalCompactAgentEnabled) enabled.add("Compact context agent");
            if (verificationEnabled) enabled.add("Verification");
            enabled.addAll(AutomationModeController.persistentExperimentalFeatureNames());
            return java.util.List.copyOf(enabled);
        }
    }
}
