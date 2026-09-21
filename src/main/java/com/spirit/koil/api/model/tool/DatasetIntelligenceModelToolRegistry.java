package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Provider-neutral, bounded Dataset Intelligence model surface. */
public final class DatasetIntelligenceModelToolRegistry {
    public static final String SEARCH = "dataset.search";
    public static final String INSPECT = "dataset.inspect";
    public static final String QUERY = "dataset.query";
    public static final String FILTER = "dataset.filter";
    public static final String ROWS = "dataset.rows";
    public static final String STATS = "dataset.stats";
    public static final String ACQUIRE = "dataset.acquire";
    public static final String RELEASE = "dataset.release";
    public static final String RESEARCH = "dataset.research";
    private static final int MAXIMUM_LEASES = 32;
    private static final long LEASE_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final Map<String, Lease> LEASES = new ConcurrentHashMap<>();
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitions();
    private static volatile DatasetProvider provider = new HuggingFaceDatasetProvider();

    private DatasetIntelligenceModelToolRegistry() {}

    public static List<ModelToolDefinition> modelTools() { return List.copyOf(DEFINITIONS.values()); }
    public static boolean supports(String id) { return id != null && DEFINITIONS.containsKey(id); }
    public static void configureProvider(DatasetProvider value) { provider = value == null ? new HuggingFaceDatasetProvider() : value; }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failure(call, "unknown_dataset_tool", "Unknown dataset capability."));
        return CompletableFuture.supplyAsync(() -> {
            cleanupLeases();
            try {
                return switch (call.toolId()) {
                    case SEARCH -> search(call);
                    case INSPECT -> inspect(call);
                    case QUERY -> query(call);
                    case FILTER -> filter(call);
                    case ROWS -> rows(call);
                    case STATS -> stats(call);
                    case ACQUIRE -> acquire(call);
                    case RELEASE -> release(call);
                    case RESEARCH -> research(call);
                    default -> failure(call, "unknown_dataset_tool", "Unknown dataset capability.");
                };
            } catch (HuggingFaceDatasetProvider.ProviderException providerFailure) {
                JsonObject output = provenance(call.arguments());
                output.addProperty("provider", provider.id());
                output.addProperty("httpStatus", providerFailure.statusCode());
                output.addProperty("retryable", providerFailure.retryable());
                return new ModelToolResult(call.id(), call.toolId(), "failed", output,
                        providerFailure.code(), providerFailure.getMessage());
            } catch (Exception failure) {
                return failure(call, "dataset_operation_failed", message(failure));
            }
        });
    }

    private static ModelToolResult search(ModelToolCall call) throws Exception {
        String query = required(call.arguments(), "query");
        int limit = integer(call.arguments(), "limit", 8, 1, 20);
        JsonObject output = provider.search(query, limit);
        output.addProperty("provider", provider.id());
        output.addProperty("query", query);
        output.addProperty("bounded", true);
        return completed(call, output, "Dataset discovery completed.");
    }

    private static ModelToolResult inspect(ModelToolCall call) throws Exception {
        String dataset = required(call.arguments(), "dataset");
        JsonObject output = provider.inspect(dataset);
        addProvenance(output, dataset, optional(call.arguments(), "revision"), "", "");
        output.addProperty("provider", provider.id());
        return completed(call, output, "Dataset metadata and Viewer capabilities were inspected.");
    }

    private static ModelToolResult rows(ModelToolCall call) throws Exception {
        Selection s = selection(call.arguments());
        int offset = integer(call.arguments(), "offset", 0, 0, 10_000_000);
        int length = integer(call.arguments(), "length", 20, 1, 100);
        JsonObject output = provider.rows(s.dataset(), s.config(), s.split(), offset, length);
        addProvenance(output, s.dataset(), s.revision(), s.config(), s.split());
        output.addProperty("offset", offset);
        output.addProperty("length", length);
        output.addProperty("provider", provider.id());
        return completed(call, output, "A bounded dataset row slice was retrieved.");
    }

    private static ModelToolResult query(ModelToolCall call) throws Exception {
        Selection s = selection(call.arguments());
        String query = required(call.arguments(), "query");
        int offset = integer(call.arguments(), "offset", 0, 0, 10_000_000);
        int length = integer(call.arguments(), "length", 20, 1, 100);
        JsonObject output = provider.query(s.dataset(), s.config(), s.split(), query, offset, length);
        addProvenance(output, s.dataset(), s.revision(), s.config(), s.split());
        output.addProperty("query", query);
        output.addProperty("offset", offset);
        output.addProperty("length", length);
        output.addProperty("provider", provider.id());
        return completed(call, output, "Dataset full-text query completed within the row bound.");
    }

    private static ModelToolResult filter(ModelToolCall call) throws Exception {
        Selection s = selection(call.arguments());
        String where = required(call.arguments(), "where");
        String order = optional(call.arguments(), "orderBy");
        int offset = integer(call.arguments(), "offset", 0, 0, 10_000_000);
        int length = integer(call.arguments(), "length", 20, 1, 100);
        JsonObject output = provider.filter(s.dataset(), s.config(), s.split(), where, order, offset, length);
        addProvenance(output, s.dataset(), s.revision(), s.config(), s.split());
        output.addProperty("where", where);
        if (!order.isBlank()) output.addProperty("orderBy", order);
        output.addProperty("provider", provider.id());
        return completed(call, output, "Dataset filter completed within the row bound.");
    }

    private static ModelToolResult stats(ModelToolCall call) throws Exception {
        Selection s = selection(call.arguments());
        JsonObject output = provider.stats(s.dataset(), s.config(), s.split());
        addProvenance(output, s.dataset(), s.revision(), s.config(), s.split());
        output.addProperty("provider", provider.id());
        return completed(call, output, "Dataset split statistics were retrieved.");
    }

    private static ModelToolResult acquire(ModelToolCall call) throws Exception {
        String dataset = required(call.arguments(), "dataset");
        String config = optional(call.arguments(), "config");
        String split = optional(call.arguments(), "split");
        int maximumFiles = integer(call.arguments(), "maxFiles", 4, 1, 16);
        if (LEASES.size() >= MAXIMUM_LEASES) cleanupLeases(true);
        if (LEASES.size() >= MAXIMUM_LEASES) return failure(call, "dataset_lease_limit", "Too many active dataset leases.");
        JsonObject parquet = provider.parquet(dataset);
        JsonArray selected = new JsonArray();
        JsonArray files = parquet.has("parquet_files") && parquet.get("parquet_files").isJsonArray()
                ? parquet.getAsJsonArray("parquet_files") : new JsonArray();
        for (JsonElement element : files) {
            if (!element.isJsonObject() || selected.size() >= maximumFiles) continue;
            JsonObject file = element.getAsJsonObject();
            if (!config.isBlank() && file.has("config") && !config.equals(file.get("config").getAsString())) continue;
            if (!split.isBlank() && file.has("split") && !split.equals(file.get("split").getAsString())) continue;
            selected.add(file.deepCopy());
        }
        String leaseId = "dataset-" + UUID.randomUUID().toString().substring(0, 12);
        long expires = System.currentTimeMillis() + LEASE_TTL_MILLIS;
        LEASES.put(leaseId, new Lease(leaseId, dataset, config, split, selected.deepCopy(), expires));
        JsonObject output = new JsonObject();
        output.addProperty("leaseId", leaseId);
        output.addProperty("dataset", dataset);
        output.addProperty("config", config);
        output.addProperty("split", split);
        output.add("parquetFiles", selected);
        output.addProperty("fileCount", selected.size());
        output.addProperty("expiresAtMillis", expires);
        output.addProperty("downloaded", false);
        output.addProperty("bounded", true);
        output.addProperty("provider", provider.id());
        return completed(call, output, "A bounded Parquet lease was acquired without downloading the full dataset.");
    }

    private static ModelToolResult release(ModelToolCall call) {
        String leaseId = required(call.arguments(), "leaseId");
        Lease removed = LEASES.remove(leaseId);
        JsonObject output = new JsonObject();
        output.addProperty("leaseId", leaseId);
        output.addProperty("released", removed != null);
        return completed(call, output, removed == null ? "The dataset lease was already absent." : "Dataset lease released.");
    }

    private static ModelToolResult research(ModelToolCall call) throws Exception {
        String query = required(call.arguments(), "query");
        int limit = integer(call.arguments(), "limit", 3, 1, 5);
        JsonObject search = provider.search(query, limit);
        JsonArray candidates = search.has("datasets") && search.get("datasets").isJsonArray()
                ? search.getAsJsonArray("datasets") : new JsonArray();
        JsonArray inspected = new JsonArray();
        for (JsonElement candidate : candidates) {
            if (!candidate.isJsonObject() || inspected.size() >= limit) continue;
            JsonObject object = candidate.getAsJsonObject();
            String id = object.has("id") ? object.get("id").getAsString() : "";
            if (id.isBlank()) continue;
            try {
                JsonObject dossier = provider.inspect(id);
                addProvenance(dossier, id, object.has("sha") ? object.get("sha").getAsString() : "", "", "");
                inspected.add(dossier);
            } catch (Exception ignored) {
                JsonObject degraded = new JsonObject();
                degraded.addProperty("dataset", id);
                degraded.addProperty("inspectAvailable", false);
                inspected.add(degraded);
            }
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.addProperty("provider", provider.id());
        output.add("candidates", candidates.deepCopy());
        output.add("inspected", inspected);
        output.addProperty("singleAgentComposite", true);
        output.addProperty("bounded", true);
        return completed(call, output, "Dataset research combined bounded discovery with capability inspection.");
    }

    private static void addProvenance(JsonObject output, String dataset, String revision, String config, String split) {
        JsonObject provenance = new JsonObject();
        provenance.addProperty("provider", provider.id());
        provenance.addProperty("dataset", dataset);
        provenance.addProperty("revision", revision == null ? "" : revision);
        provenance.addProperty("config", config == null ? "" : config);
        provenance.addProperty("split", split == null ? "" : split);
        output.add("provenance", provenance);
    }

    private static JsonObject provenance(JsonObject arguments) {
        JsonObject output = new JsonObject();
        if (arguments == null) return output;
        for (String key : List.of("dataset", "revision", "config", "split")) {
            if (arguments.has(key) && arguments.get(key).isJsonPrimitive()) output.add(key, arguments.get(key).deepCopy());
        }
        return output;
    }

    private static Selection selection(JsonObject arguments) {
        return new Selection(required(arguments, "dataset"), required(arguments, "config"), required(arguments, "split"), optional(arguments, "revision"));
    }

    private static void cleanupLeases() { cleanupLeases(false); }
    private static void cleanupLeases(boolean pressure) {
        long now = System.currentTimeMillis();
        LEASES.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (pressure && LEASES.size() >= MAXIMUM_LEASES) {
            LEASES.values().stream().sorted(java.util.Comparator.comparingLong(Lease::expiresAtMillis))
                    .limit(Math.max(1, LEASES.size() - MAXIMUM_LEASES + 1L))
                    .map(Lease::id).toList().forEach(LEASES::remove);
        }
    }

    private static Map<String, ModelToolDefinition> definitions() {
        LinkedHashMap<String, ModelToolDefinition> map = new LinkedHashMap<>();
        JsonObject search = objectSchema(); property(search, "query", stringSchema()); property(search, "limit", integerSchema(1, 20)); require(search, "query");
        map.put(SEARCH, readOnly(SEARCH, "Search provider-neutral dataset catalogues for datasets relevant to an objective.", search));
        JsonObject inspect = objectSchema(); property(inspect, "dataset", stringSchema()); property(inspect, "revision", stringSchema()); require(inspect, "dataset");
        map.put(INSPECT, readOnly(INSPECT, "Inspect dataset metadata, revision evidence, Viewer capabilities, splits and size without downloading the dataset.", inspect));
        JsonObject rows = selectionSchema(); property(rows, "offset", integerSchema(0, 10_000_000)); property(rows, "length", integerSchema(1, 100));
        map.put(ROWS, readOnly(ROWS, "Read a bounded slice of dataset rows while preserving dataset/config/split provenance.", rows));
        JsonObject query = selectionSchema(); property(query, "query", stringSchema()); property(query, "offset", integerSchema(0, 10_000_000)); property(query, "length", integerSchema(1, 100)); require(query, "query");
        map.put(QUERY, readOnly(QUERY, "Run bounded provider-supported full-text search inside a dataset split.", query));
        JsonObject filter = selectionSchema(); property(filter, "where", stringSchema()); property(filter, "orderBy", stringSchema()); property(filter, "offset", integerSchema(0, 10_000_000)); property(filter, "length", integerSchema(1, 100)); require(filter, "where");
        map.put(FILTER, readOnly(FILTER, "Filter a dataset split using the provider query language with a hard 100-row response bound.", filter));
        JsonObject stats = selectionSchema();
        map.put(STATS, readOnly(STATS, "Retrieve provider-computed statistics for one dataset config/split.", stats));
        JsonObject acquire = objectSchema(); property(acquire, "dataset", stringSchema()); property(acquire, "config", stringSchema()); property(acquire, "split", stringSchema()); property(acquire, "maxFiles", integerSchema(1,16)); require(acquire, "dataset");
        map.put(ACQUIRE, readOnly(ACQUIRE, "Acquire a short-lived bounded lease over selected Parquet artifacts. Does not silently download a whole dataset.", acquire));
        JsonObject release = objectSchema(); property(release, "leaseId", stringSchema()); require(release, "leaseId");
        map.put(RELEASE, readOnly(RELEASE, "Release a short-lived dataset artifact lease.", release));
        JsonObject research = objectSchema(); property(research, "query", stringSchema()); property(research, "limit", integerSchema(1,5)); require(research, "query");
        map.put(RESEARCH, readOnly(RESEARCH, "Single-agent bounded dataset research: discover candidates and inspect their current capabilities/provenance.", research));
        return Map.copyOf(map);
    }

    private static ModelToolDefinition readOnly(String id, String description, JsonObject schema) {
        return new ModelToolDefinition(id, description, schema, List.of("public_network_available"), Set.of(), true,
                Duration.ofSeconds(35), true, false, Set.of("completed", "failed", "unsupported"),
                ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.REMOTE, ToolExecutionPolicy.CostClass.EXPENSIVE));
    }

    private static JsonObject selectionSchema() {
        JsonObject schema = objectSchema();
        property(schema, "dataset", stringSchema()); property(schema, "revision", stringSchema()); property(schema, "config", stringSchema()); property(schema, "split", stringSchema());
        require(schema, "dataset", "config", "split");
        return schema;
    }
    private static JsonObject objectSchema() { JsonObject o=new JsonObject(); o.addProperty("type","object"); o.add("properties",new JsonObject()); o.addProperty("additionalProperties",false); return o; }
    private static JsonObject stringSchema() { JsonObject o=new JsonObject(); o.addProperty("type","string"); return o; }
    private static JsonObject integerSchema(int min,int max) { JsonObject o=new JsonObject(); o.addProperty("type","integer"); o.addProperty("minimum",min); o.addProperty("maximum",max); return o; }
    private static void property(JsonObject schema,String name,JsonObject value){ schema.getAsJsonObject("properties").add(name,value); }
    private static void require(JsonObject schema,String... names){ JsonArray a=schema.has("required")?schema.getAsJsonArray("required"):new JsonArray(); for(String n:names) if(!a.contains(new com.google.gson.JsonPrimitive(n))) a.add(n); schema.add("required",a); }
    private static String required(JsonObject o,String key){ if(o==null||!o.has(key)||o.get(key).isJsonNull()||o.get(key).getAsString().isBlank()) throw new IllegalArgumentException(key+" is required."); return o.get(key).getAsString().strip(); }
    private static String optional(JsonObject o,String key){ return o!=null&&o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString().strip():""; }
    private static int integer(JsonObject o,String key,int fallback,int min,int max){ return o==null||!o.has(key)?fallback:Math.max(min,Math.min(max,o.get(key).getAsInt())); }
    private static ModelToolResult completed(ModelToolCall call,JsonObject output,String detail){ return new ModelToolResult(call.id(),call.toolId(),"completed",output,"",detail); }
    private static ModelToolResult failure(ModelToolCall call,String code,String detail){ return new ModelToolResult(call==null?"":call.id(),call==null?"":call.toolId(),"failed",new JsonObject(),code,detail); }
    private static String message(Throwable failure){ Throwable current=failure; while(current.getCause()!=null) current=current.getCause(); return current.getMessage()==null?current.getClass().getSimpleName():current.getMessage(); }
    private record Selection(String dataset,String config,String split,String revision) {}
    private record Lease(String id,String dataset,String config,String split,JsonArray parquetFiles,long expiresAtMillis) {}
}
