package com.spirit.koil.api.model.catalog;

public enum LocalModelCapabilityTag {
    CHAT("Chat"),
    CODE("Code"),
    TECHNICAL("Technical"),
    AUTOMATION_TOOLS("Tools");

    private final String label;

    LocalModelCapabilityTag(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}
