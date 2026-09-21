package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;

/** Provider-neutral backend for Koil Browser Intelligence. */
public interface BrowserProvider {
    String id();
    JsonObject health() throws Exception;
    JsonObject navigate(String url, String tabId, boolean newTab, int timeoutMillis) throws Exception;
    JsonObject snapshot(String tabId, String filter, String format, int depth, boolean diff) throws Exception;
    JsonObject capture(String tabId, boolean requirePair, double scale, boolean beyondViewport) throws Exception;
    JsonObject text(String tabId, boolean raw, int maxChars) throws Exception;
    JsonObject find(String tabId, String query) throws Exception;
    JsonObject interact(String tabId, JsonObject action) throws Exception;
    JsonObject waitFor(String tabId, JsonObject condition) throws Exception;
    JsonObject screenshot(String tabId, String format, double scale, boolean beyondViewport, boolean annotate) throws Exception;
    JsonObject pdf(String tabId, boolean landscape, double scale) throws Exception;
    JsonObject tabs(String operation, String tabId, String url) throws Exception;
    JsonObject network(String tabId, String operation, JsonObject arguments) throws Exception;
    JsonObject dialog(String tabId, String action, String text) throws Exception;
    JsonObject cookies(String tabId, String operation, JsonObject arguments) throws Exception;
    JsonObject audit(String url) throws Exception;
    JsonObject compare(String leftUrl, String rightUrl) throws Exception;
}
