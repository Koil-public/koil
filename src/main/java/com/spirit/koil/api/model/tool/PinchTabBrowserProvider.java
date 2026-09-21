package com.spirit.koil.api.model.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Native Java HTTP adapter for the local PinchTab browser control plane. */
public final class PinchTabBrowserProvider implements BrowserProvider {
    private static final int MAX_JSON_BYTES = 2_000_000;
    private static final int MAX_BINARY_BYTES = 12_000_000;
    private static final Object MANAGED_SERVER_LOCK = new Object();
    private static final long MANAGED_SERVER_READY_TIMEOUT_MILLIS = 8_000L;
    private static volatile Process managedServerProcess;
    private static volatile long managedServerStartedAt;
    private static volatile String managedServerFailure = "";
    /** Bearer credential owned by a Koil-launched PinchTab server. Never logged or persisted. */
    private static volatile String managedServerAuthToken = "";
    private static final SecureRandom MANAGED_TOKEN_RANDOM = new SecureRandom();
    private final URI base;

    public PinchTabBrowserProvider() {
        this(System.getProperty("koil.browser.baseUrl", "http://127.0.0.1:9867"));
    }

    public PinchTabBrowserProvider(String baseUrl) {
        URI candidate = URI.create(baseUrl == null ? "" : baseUrl.strip());
        if (!("http".equalsIgnoreCase(candidate.getScheme()) || "https".equalsIgnoreCase(candidate.getScheme()))
                || candidate.getHost() == null || !loopback(candidate.getHost())) {
            throw new IllegalArgumentException("Browser control plane must remain bound to loopback.");
        }
        this.base = URI.create(candidate.getScheme() + "://" + candidate.getAuthority());
    }

    @Override public String id() { return "browser_local_http"; }
    @Override public JsonObject health() throws Exception { return requestJson("GET", "/health", null); }

    @Override public JsonObject navigate(String url, String tabId, boolean newTab, int timeoutMillis) throws Exception {
        URI target = PublicNetworkPolicy.validate(URI.create(url));
        JsonObject body = new JsonObject();
        body.addProperty("url", target.toString());
        body.addProperty("timeout", Math.max(1_000, Math.min(60_000, timeoutMillis)));
        if (newTab) body.addProperty("newTab", true);
        String path = tabId == null || tabId.isBlank() ? "/navigate" : "/tabs/" + segment(tabId) + "/navigate";
        return untrusted(requestJson("POST", path, body));
    }

    @Override public JsonObject snapshot(String tabId, String filter, String format, int depth, boolean diff) throws Exception {
        Map<String,String> query = new LinkedHashMap<>();
        if (!filter.isBlank()) query.put("filter", filter);
        if (!format.isBlank()) query.put("format", format);
        if (depth > 0) query.put("depth", Integer.toString(Math.min(20, depth)));
        if (diff) query.put("diff", "true");
        return untrusted(requestFlexible("GET", scoped(tabId,"snapshot") + qs(query), null));
    }

    @Override public JsonObject capture(String tabId, boolean requirePair, double scale, boolean beyondViewport) throws Exception {
        Map<String,String> query = new LinkedHashMap<>();
        query.put("requirePair", Boolean.toString(requirePair));
        query.put("scale", Double.toString(boundScale(scale)));
        query.put("beyondViewport", Boolean.toString(beyondViewport));
        ProviderResponse response = requestBytes("GET", scoped(tabId,"capture") + qs(query), null, MAX_BINARY_BYTES);
        return binaryOrJson(response, "capture", "image/jpeg");
    }

    @Override public JsonObject text(String tabId, boolean raw, int maxChars) throws Exception {
        JsonObject out = untrusted(requestFlexible("GET", scoped(tabId,"text") + (raw ? "?mode=raw" : ""), null));
        trimTextFields(out, Math.max(256, Math.min(32_000, maxChars)));
        return out;
    }

    @Override public JsonObject find(String tabId, String query) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("query", query);
        return untrusted(requestJson("POST", scoped(tabId,"find"), body));
    }

    @Override public JsonObject interact(String tabId, JsonObject action) throws Exception {
        JsonObject body = action == null ? new JsonObject() : action.deepCopy();
        body.remove("tabId");
        return untrusted(requestJson("POST", scoped(tabId,"action"), body));
    }

    @Override public JsonObject waitFor(String tabId, JsonObject condition) throws Exception {
        JsonObject body = condition == null ? new JsonObject() : condition.deepCopy();
        body.remove("tabId");
        if (body.has("timeout")) body.addProperty("timeout", Math.max(1, Math.min(30_000, body.get("timeout").getAsInt())));
        return untrusted(requestJson("POST", scoped(tabId,"wait"), body));
    }

    @Override public JsonObject screenshot(String tabId, String format, double scale, boolean beyondViewport, boolean annotate) throws Exception {
        String safeFormat = "png".equalsIgnoreCase(format) ? "png" : "jpeg";
        Map<String,String> query = new LinkedHashMap<>();
        query.put("raw", "true"); query.put("format", safeFormat); query.put("scale", Double.toString(boundScale(scale)));
        query.put("beyondViewport", Boolean.toString(beyondViewport)); query.put("annotate", Boolean.toString(annotate));
        ProviderResponse response = requestBytes("GET", scoped(tabId,"screenshot") + qs(query), null, MAX_BINARY_BYTES);
        return persistBinary(response.body(), "screenshot", safeFormat, "image/" + ("jpeg".equals(safeFormat)?"jpeg":"png"));
    }

    @Override public JsonObject pdf(String tabId, boolean landscape, double scale) throws Exception {
        Map<String,String> query = new LinkedHashMap<>();
        query.put("raw", "true"); query.put("landscape", Boolean.toString(landscape)); query.put("scale", Double.toString(boundScale(scale)));
        ProviderResponse response = requestBytes("GET", scoped(tabId,"pdf") + qs(query), null, MAX_BINARY_BYTES);
        return persistBinary(response.body(), "page", "pdf", "application/pdf");
    }

    @Override public JsonObject tabs(String operation, String tabId, String url) throws Exception {
        return switch (operation) {
            case "list" -> requestJson("GET", "/tabs", null);
            case "new" -> {
                JsonObject body = new JsonObject(); body.addProperty("action", "new");
                if (url != null && !url.isBlank()) body.addProperty("url", PublicNetworkPolicy.validate(URI.create(url)).toString());
                yield untrusted(requestJson("POST", "/tab", body));
            }
            case "focus" -> {
                JsonObject body = new JsonObject(); body.addProperty("action", "focus"); body.addProperty("tabId", requiredTab(tabId));
                yield requestJson("POST", "/tab", body);
            }
            case "close" -> requestJson("POST", "/tabs/" + segment(requiredTab(tabId)) + "/close", new JsonObject());
            default -> throw new IllegalArgumentException("Unsupported tab operation: " + operation);
        };
    }

    @Override public JsonObject network(String tabId, String operation, JsonObject arguments) throws Exception {
        String root = scoped(tabId, "network");
        Map<String,String> query = new LinkedHashMap<>();
        if (arguments != null) {
            for (String key : new String[]{"filter","method","status","type","limit"})
                if (arguments.has(key) && arguments.get(key).isJsonPrimitive()) query.put(key, arguments.get(key).getAsString());
        }
        return switch (operation) {
            case "list" -> untrusted(requestJson("GET", root + qs(query), null));
            case "detail" -> {
                String requestId = text(arguments,"requestId");
                if (requestId.isBlank()) throw new IllegalArgumentException("requestId is required for network detail.");
                yield untrusted(requestJson("GET", root + "/" + segment(requestId), null));
            }
            case "clear" -> requestJson("POST", "/network/clear", new JsonObject());
            case "export" -> {
                query.put("format", text(arguments,"format").isBlank()?"har":text(arguments,"format"));
                query.put("redact", "true");
                if (arguments != null && arguments.has("includeBodies") && arguments.get("includeBodies").getAsBoolean()) query.put("body","true");
                yield untrusted(requestFlexible("GET", root + "/export" + qs(query), null));
            }
            default -> throw new IllegalArgumentException("Unsupported network operation: " + operation);
        };
    }

    @Override public JsonObject dialog(String tabId, String action, String text) throws Exception {
        if (!("accept".equals(action) || "dismiss".equals(action))) throw new IllegalArgumentException("dialog action must be accept or dismiss.");
        JsonObject body=new JsonObject(); body.addProperty("action",action); if(text!=null&&!text.isBlank()) body.addProperty("text",text);
        return requestJson("POST", scoped(tabId,"dialog"), body);
    }

    @Override public JsonObject cookies(String tabId, String operation, JsonObject arguments) throws Exception {
        String path=scoped(tabId,"cookies");
        return switch(operation){
            case "get" -> redactCookieValues(requestJson("GET",path,null));
            case "set" -> requestJson("POST",path,arguments==null?new JsonObject():arguments.deepCopy());
            case "clear" -> requestJson("DELETE",path,new JsonObject());
            default -> throw new IllegalArgumentException("Unsupported cookie operation: "+operation);
        };
    }

    @Override public JsonObject audit(String url) throws Exception {
        URI target=PublicNetworkPolicy.validate(URI.create(url));
        JsonObject body=new JsonObject(); body.addProperty("url",target.toString());
        JsonObject output=untrusted(requestJson("POST","/audit/page",body));
        output.addProperty("auditScope","browser-level page diagnostics, not a comprehensive security audit");
        return output;
    }

    @Override public JsonObject compare(String leftUrl, String rightUrl) throws Exception {
        JsonObject leftNav=navigate(leftUrl,"",true,30_000); String leftTab=tabId(leftNav);
        JsonObject rightNav=navigate(rightUrl,"",true,30_000); String rightTab=tabId(rightNav);
        if(leftTab.isBlank()||rightTab.isBlank()) throw new IOException("Browser provider did not return tab IDs for comparison.");
        byte[] left=requestBytes("GET",scoped(leftTab,"screenshot")+"?raw=true&format=png&noAnimations=true",null,MAX_BINARY_BYTES).body();
        byte[] right=requestBytes("GET",scoped(rightTab,"screenshot")+"?raw=true&format=png&noAnimations=true",null,MAX_BINARY_BYTES).body();
        JsonObject out=new JsonObject(); out.addProperty("leftUrl",PublicNetworkPolicy.validate(URI.create(leftUrl)).toString()); out.addProperty("rightUrl",PublicNetworkPolicy.validate(URI.create(rightUrl)).toString());
        out.addProperty("leftSha256",sha256(left)); out.addProperty("rightSha256",sha256(right)); out.addProperty("identical",java.util.Arrays.equals(left,right));
        BufferedImage a=ImageIO.read(new ByteArrayInputStream(left)); BufferedImage b=ImageIO.read(new ByteArrayInputStream(right));
        if(a!=null&&b!=null){ out.addProperty("leftWidth",a.getWidth()); out.addProperty("leftHeight",a.getHeight()); out.addProperty("rightWidth",b.getWidth()); out.addProperty("rightHeight",b.getHeight());
            if(a.getWidth()==b.getWidth()&&a.getHeight()==b.getHeight()){ long different=0,total=(long)a.getWidth()*a.getHeight(); for(int y=0;y<a.getHeight();y++) for(int x=0;x<a.getWidth();x++) if(a.getRGB(x,y)!=b.getRGB(x,y)) different++; out.addProperty("differentPixels",different); out.addProperty("differentPixelRatio",total==0?0.0D:(double)different/total); }
        }
        return out;
    }

    private ProviderResponse requestBytes(String method,String path,JsonObject body,int maximum) throws Exception {
        boolean safeRetry = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        int transportAttempts = safeRetry ? 3 : 1;
        IOException lastFailure = null;
        boolean lifecycleRecoveryAttempted = false;

        for (int attempt = 0; attempt < transportAttempts; attempt++) {
            try {
                return requestBytesOnce(method, path, body, maximum);
            } catch (BrowserProviderException failure) {
                throw failure;
            } catch (IOException failure) {
                lastFailure = failure;

                // A refused connect is unambiguous: no HTTP request reached PinchTab, so it is
                // safe to bootstrap the local server and replay even a mutating operation once.
                if (!lifecycleRecoveryAttempted && connectionRefused(failure) && managesDefaultEndpoint()) {
                    lifecycleRecoveryAttempted = true;
                    ensureManagedServerReady(failure);
                    try {
                        return requestBytesOnce(method, path, body, maximum);
                    } catch (BrowserProviderException providerFailure) {
                        throw providerFailure;
                    } catch (IOException afterStart) {
                        lastFailure = afterStart;
                        failure = afterStart;
                    }
                }

                if (!safeRetry || attempt + 1 >= transportAttempts || !transientTransportFailure(failure)) throw failure;
                sleepRecovery(50L * (attempt + 1L), failure);
            }
        }
        throw lastFailure == null ? new IOException("Browser provider request failed.") : lastFailure;
    }

    private ProviderResponse requestBytesOnce(String method,String path,JsonObject body,int maximum) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI target = base.resolve(path);
            connection = (HttpURLConnection) target.toURL().openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(65_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json,image/*,application/pdf,text/plain");
            connection.setRequestProperty("User-Agent", "Koil-Browser-Intelligence/1");
            connection.setRequestProperty("Connection", "close");
            String token = token();
            if (!token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token);

            if (body != null) {
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(encoded);
                }
            }

            int status = connection.getResponseCode();
            String contentType = connection.getHeaderField("Content-Type");
            InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readBounded(source, maximum);
            if (status < 200 || status >= 300) {
                String detail = compact(new String(bytes, StandardCharsets.UTF_8), 512);
                throw new BrowserProviderException(status, "Browser provider HTTP " + status + (detail.isBlank() ? "" : " | " + detail));
            }
            return new ProviderResponse(status, bytes, contentType == null ? "" : contentType);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean managesDefaultEndpoint() {
        if (!Boolean.parseBoolean(System.getProperty("koil.pinchtab.autostart", "true"))) return false;
        if (!"http".equalsIgnoreCase(base.getScheme())) return false;
        int port = base.getPort() < 0 ? 80 : base.getPort();
        return port == 9867 && base.getHost() != null && loopback(base.getHost());
    }

    private void ensureManagedServerReady(IOException originalFailure) throws IOException {
        synchronized (MANAGED_SERVER_LOCK) {
            if (healthProbe()) return;

            Process current = managedServerProcess;
            if (current != null && !current.isAlive()) managedServerProcess = null;

            if (managedServerProcess == null) {
                managedServerProcess = launchManagedServer();
                managedServerStartedAt = System.currentTimeMillis();
                Process started = managedServerProcess;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (started.isAlive()) started.destroy();
                }, "koil-pinchtab-shutdown"));
            }
        }

        long deadline = System.nanoTime() + Duration.ofMillis(MANAGED_SERVER_READY_TIMEOUT_MILLIS).toNanos();
        IOException last = originalFailure;
        while (System.nanoTime() < deadline) {
            Process process = managedServerProcess;
            if (process != null && !process.isAlive()) {
                int exit = process.exitValue();
                synchronized (MANAGED_SERVER_LOCK) {
                    if (managedServerProcess == process) managedServerProcess = null;
                    managedServerFailure = "PinchTab server exited during startup with code " + exit + ".";
                }
                throw new IOException(managedServerFailure + " See " + managedServerLogPath() + ".", originalFailure);
            }
            if (healthProbe()) {
                managedServerFailure = "";
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the managed PinchTab server to become ready.", interrupted);
            }
        }

        Process process = managedServerProcess;
        if (process != null && process.isAlive()) process.destroy();
        synchronized (MANAGED_SERVER_LOCK) {
            if (managedServerProcess == process) managedServerProcess = null;
            managedServerFailure = "PinchTab did not become ready within " + MANAGED_SERVER_READY_TIMEOUT_MILLIS + " ms.";
        }
        throw new IOException(managedServerFailure + " See " + managedServerLogPath() + ".", last);
    }

    private Process launchManagedServer() throws IOException {
        Path log = managedServerLogPath();
        Files.createDirectories(log.getParent());
        Path binaryPath = PinchTabManagedRuntime.resolveOrInstall();
        String binary = binaryPath.toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(binary, "server"));
        // PinchTab defaults to token-authenticated local control. When Koil owns the
        // process, Koil must also own the credential so the API client and server
        // cannot drift onto different implicit/config-generated tokens. Explicit
        // operator credentials remain authoritative.
        String serverToken = configuredToken();
        if (serverToken.isBlank()) {
            serverToken = managedServerAuthToken;
            if (serverToken.isBlank()) {
                serverToken = newManagedToken();
                managedServerAuthToken = serverToken;
            }
        } else {
            managedServerAuthToken = serverToken;
        }
        builder.environment().put("PINCHTAB_TOKEN", serverToken);

        String home = System.getProperty("user.home", "").strip();
        if (!home.isBlank()) {
            Path homePath = Path.of(home).toAbsolutePath().normalize();
            if (Files.isDirectory(homePath)) builder.directory(homePath.toFile());
            builder.environment().putIfAbsent("HOME", homePath.toString());
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        try {
            return builder.start();
        } catch (IOException failure) {
            managedServerFailure = "Unable to launch resolved PinchTab executable '" + binary + "': " + compact(failure.getMessage(), 240);
            throw new IOException(managedServerFailure, failure);
        }
    }

    private boolean healthProbe() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) base.resolve("/health").toURL().openConnection();
            connection.setConnectTimeout(350);
            connection.setReadTimeout(750);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "close");
            String token = token();
            if (!token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            // 401/403 still proves the server is alive. The normal request path will report the auth error.
            return status > 0;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Path managedServerLogPath() {
        return KoilInstancePaths.modelRoot().resolve("browser/pinchtab-server.log").toAbsolutePath().normalize();
    }

    private static boolean connectionRefused(IOException failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof java.net.ConnectException) {
                String message = current.getMessage();
                if (message == null || message.toLowerCase(Locale.ROOT).contains("refused")) return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("connection refused")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void sleepRecovery(long millis, IOException cause) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IOException aborted = new IOException("Browser provider recovery was interrupted.", interrupted);
            aborted.addSuppressed(cause);
            throw aborted;
        }
    }

    private static byte[] readBounded(InputStream source, int maximum) throws IOException {
        if (source == null) return new byte[0];
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maximum, 32 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > maximum) throw new IOException("Browser provider response exceeded configured bound.");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean transientTransportFailure(IOException failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof java.nio.channels.ClosedChannelException
                    || current instanceof java.io.EOFException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketException
                    || current instanceof java.net.SocketTimeoutException) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("closed channel")
                        || normalized.contains("connection reset")
                        || normalized.contains("broken pipe")
                        || normalized.contains("unexpected end of file")
                        || normalized.contains("header parser received no bytes")
                        || normalized.contains("connection closed")
                        || normalized.contains("unexpected end of stream")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JsonObject requestJson(String method,String path,JsonObject body) throws Exception { return parse(requestBytes(method,path,body,MAX_JSON_BYTES)); }
    private JsonObject requestFlexible(String method,String path,JsonObject body) throws Exception { return parse(requestBytes(method,path,body,MAX_JSON_BYTES)); }
    private static JsonObject parse(ProviderResponse response){ String value=new String(response.body(),StandardCharsets.UTF_8); try{ JsonElement e=JsonParser.parseString(value.isBlank()?"{}":value); if(e.isJsonObject()) return e.getAsJsonObject(); JsonObject o=new JsonObject(); o.add("value",e); return o; }catch(RuntimeException ignored){ JsonObject o=new JsonObject(); o.addProperty("text",compact(value,32_000)); return o; } }
    private static JsonObject binaryOrJson(ProviderResponse response,String prefix,String fallbackMime) throws Exception { String ct=response.contentType(); if(ct.contains("json")||ct.startsWith("text/")) return parse(response); return persistBinary(response.body(),prefix,ct.contains("png")?"png":"jpg",ct.isBlank()?fallbackMime:ct); }
    private static JsonObject persistBinary(byte[] bytes,String prefix,String extension,String mime) throws Exception { if(bytes==null) bytes=new byte[0]; Path dir=KoilInstancePaths.modelRoot().resolve("browser-artifacts"); Files.createDirectories(dir); String name=prefix+"-"+UUID.randomUUID().toString().substring(0,12)+"."+extension; Path path=dir.resolve(name).normalize(); if(!path.startsWith(dir)) throw new IOException("Invalid browser artifact path."); Files.write(path,bytes); JsonObject out=new JsonObject(); out.addProperty("artifact", "koil/sys/model/browser-artifacts/"+name); out.addProperty("mimeType",mime); out.addProperty("bytes",bytes.length); out.addProperty("sha256",sha256(bytes)); return out; }
    private static JsonObject untrusted(JsonObject input){ JsonObject out=input==null?new JsonObject():input; out.addProperty("trust","untrusted_external_page_data"); return out; }
    private static JsonObject redactCookieValues(JsonObject input){ JsonObject out=input==null?new JsonObject():input.deepCopy(); redactRecursive(out); out.addProperty("cookieValuesRedacted",true); return out; }
    private static void redactRecursive(JsonElement element){ if(element==null)return; if(element.isJsonObject()){ JsonObject object=element.getAsJsonObject(); for(String key:new java.util.ArrayList<>(object.keySet())){ JsonElement value=object.get(key); if("value".equalsIgnoreCase(key)||"authorization".equalsIgnoreCase(key)||"cookie".equalsIgnoreCase(key)) object.addProperty(key,"[redacted]"); else redactRecursive(value); }} else if(element.isJsonArray()) for(JsonElement e:element.getAsJsonArray()) redactRecursive(e); }
    private static String scoped(String tabId,String endpoint){ return tabId==null||tabId.isBlank()?"/"+endpoint:"/tabs/"+segment(tabId)+"/"+endpoint; }
    private static String requiredTab(String tabId){ if(tabId==null||tabId.isBlank()) throw new IllegalArgumentException("tabId is required."); return tabId.strip(); }
    private static String segment(String value){ return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    private static String qs(Map<String,String> params){ if(params.isEmpty())return ""; StringBuilder b=new StringBuilder("?"); for(var e:params.entrySet()){ if(b.length()>1)b.append('&'); b.append(segment(e.getKey())).append('=').append(segment(e.getValue())); } return b.toString(); }
    private static double boundScale(double value){ return Math.max(0.1D,Math.min(2.0D,value<=0?1.0D:value)); }
    private static String configuredToken(){
        String property = System.getProperty("koil.pinchtab.token", "").strip();
        if (!property.isBlank()) return property;
        String environment = System.getenv("PINCHTAB_TOKEN");
        return environment == null ? "" : environment.strip();
    }

    private static String token(){
        String configured = configuredToken();
        if (!configured.isBlank()) return configured;
        return managedServerAuthToken == null ? "" : managedServerAuthToken;
    }

    private static String newManagedToken(){
        byte[] bytes = new byte[32];
        MANAGED_TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean loopback(String host){ try{ for(InetAddress a:InetAddress.getAllByName(host)) if(!a.isLoopbackAddress()) return false; return true; }catch(Exception e){ return false; } }
    private static String tabId(JsonObject object){ for(String key:new String[]{"tabId","id"}) if(object!=null&&object.has(key)&&object.get(key).isJsonPrimitive()) return object.get(key).getAsString(); return ""; }
    private static String text(JsonObject o,String key){ return o!=null&&o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString().strip():""; }
    private static void trimTextFields(JsonElement e,int max){ if(e==null)return; if(e.isJsonObject()){ JsonObject object=e.getAsJsonObject(); for(String key:new java.util.ArrayList<>(object.keySet())){ JsonElement v=object.get(key); if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()&&v.getAsString().length()>max) object.addProperty(key,v.getAsString().substring(0,max)+"…"); else trimTextFields(v,max); }} else if(e.isJsonArray()) for(JsonElement v:e.getAsJsonArray()) trimTextFields(v,max); }
    private static String sha256(byte[] bytes){ try{ byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder b=new StringBuilder(); for(byte x:digest)b.append(String.format("%02x",x)); return b.toString(); }catch(Exception e){throw new IllegalStateException(e);} }
    private static String compact(String v,int max){ String s=v==null?"":v.replaceAll("\\s+"," ").strip(); return s.length()<=max?s:s.substring(0,max-1)+"…"; }

    private record ProviderResponse(int statusCode, byte[] body, String contentType) {}
    public static final class BrowserProviderException extends IOException { private final int status; BrowserProviderException(int status,String message){ super(message); this.status=status; } public int status(){return status;} }
}
