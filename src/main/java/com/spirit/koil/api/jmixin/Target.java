package com.spirit.koil.api.jmixin;

public record Target(
    String kind,
    String owner,
    String name,
    String desc
) {

    public String effectiveOwner(String defaultOwner) {
        return owner == null ? defaultOwner : owner;
    }

    public boolean isField() {
        return "field".equals(kind);
    }

    public boolean isMethod() {
        return "method".equals(kind);
    }

    public boolean isConstructor() {
        return "constructor".equals(kind);
    }

    public boolean isInstruction() {
        return "instruction".equals(kind);
    }

    public boolean isMemberReference() {
        return isField() || isMethod() || isConstructor();
    }
}
