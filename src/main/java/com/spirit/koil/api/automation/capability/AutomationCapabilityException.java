package com.spirit.koil.api.automation.capability;

public final class AutomationCapabilityException extends RuntimeException {
    private final String code;

    public AutomationCapabilityException(String code, String detail) {
        super(detail == null ? "" : detail);
        this.code = code == null || code.isBlank() ? "invalid_arguments" : code;
    }

    public String code() {
        return this.code;
    }
}
