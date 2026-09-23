package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-light smoke proof for Linux process-memory parsing on the current JVM. */
public final class LlamaCppProcessMemoryAttributionProof {
    private LlamaCppProcessMemoryAttributionProof() {}
    public static void main(String[] args) throws Exception {
        var method = LlamaCppProcessMemoryAttribution.class.getDeclaredMethod("processMemory", long.class);
        method.setAccessible(true);
        var memory = (LlamaCppProcessMemoryAttribution.ProcessMemory) method.invoke(null, ProcessHandle.current().pid());
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux") && !memory.known()) {
            throw new AssertionError("expected Linux JVM process memory to be observable");
        }
        System.out.println("llama.cpp process memory attribution proof passed: " + memory.preferredBytes());
    }
}
