package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelToolCall;

import java.util.ArrayList;
import java.util.List;

/** Deterministic proof for native text tool-call recovery and visibility isolation. */
public final class LlamaCppTextToolCallBridgeProof {
    private LlamaCppTextToolCallBridgeProof() {}

    public static void run() {
        LlamaCppTextToolCallBridge bridge = new LlamaCppTextToolCallBridge(name -> switch (name) {
            case "koil_documentation" -> "koil.documentation";
            default -> name;
        });
        StringBuilder visible = new StringBuilder();
        List<ModelToolCall> calls = new ArrayList<>();

        bridge.accept("before <|tool_call_st", visible::append, calls::add);
        bridge.accept("art|>[koil_documentation(document='read',\n operation='catalog', query='ruby_block')]<|tool_call_end|> after", visible::append, calls::add);
        bridge.finish(visible::append, calls::add);

        require("before  after".equals(visible.toString()), "native tool protocol leaked into visible assistant text");
        require(calls.size() == 1, "native textual tool call was not recovered");
        ModelToolCall call = calls.get(0);
        require("koil.documentation".equals(call.toolId()), "wire tool name was not canonicalized");
        require("read".equals(call.arguments().get("document").getAsString()), "string argument was not parsed");
        require("catalog".equals(call.arguments().get("operation").getAsString()), "second argument was not parsed");
        require("ruby_block".equals(call.arguments().get("query").getAsString()), "query argument was not parsed");

        LlamaCppTextToolCallBridge structured = new LlamaCppTextToolCallBridge(name -> name);
        List<ModelToolCall> structuredCalls = new ArrayList<>();
        structured.accept(
                "<|tool_call_start|>[example_tool(flag=True, count=4, values=['a', 'b'], options={'mode':'safe'})]<|tool_call_end|>",
                ignored -> { throw new IllegalStateException("tool envelope was rendered as visible text"); },
                structuredCalls::add
        );
        structured.finish(ignored -> {}, structuredCalls::add);
        require(structuredCalls.size() == 1, "structured Python-style tool arguments were not recovered");
        require(structuredCalls.get(0).arguments().get("flag").getAsBoolean(), "boolean literal was not parsed");
        require(structuredCalls.get(0).arguments().get("count").getAsInt() == 4, "numeric literal was not parsed");
        require(structuredCalls.get(0).arguments().getAsJsonArray("values").size() == 2, "array literal was not parsed");
        require("safe".equals(structuredCalls.get(0).arguments().getAsJsonObject("options").get("mode").getAsString()),
                "object literal was not parsed");
    }

    public static void main(String[] args) {
        run();
        System.out.println("llama.cpp text tool-call bridge proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
