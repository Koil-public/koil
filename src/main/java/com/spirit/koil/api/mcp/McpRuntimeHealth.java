package com.spirit.koil.api.mcp;

/** Protocol-qualified local MCP server health. A live process alone is never ready. */
public record McpRuntimeHealth(State state, String serverId, String detail) {
    public McpRuntimeHealth {
        state = state == null ? State.NOT_INSTALLED : state;
        serverId = serverId == null ? "" : serverId;
        detail = detail == null ? "" : detail;
    }

    public enum State { NOT_INSTALLED, INSTALLING, STARTING, NEGOTIATING, READY, DEGRADED, FAILED, STOPPED }
}
