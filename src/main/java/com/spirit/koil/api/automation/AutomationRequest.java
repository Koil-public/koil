package com.spirit.koil.api.automation;

public record AutomationRequest(String rawInput, boolean runCommand, boolean directTemplate) {
}
