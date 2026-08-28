package com.spirit.koil.api.model.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.spirit.koil.api.model.catalog.LocalModelCanonicalMetadata.Architecture;
import static com.spirit.koil.api.model.catalog.LocalModelCanonicalMetadata.Maturity;

/**
 * Hugging Face is a discovery source for the existing Koil local-model catalog.
 * It does not create a second runtime or installer. Resolved GGUF implementations
 * are materialized as normal {@link LocalModelCatalogEntry} instances and therefore
 * use the same llama.cpp provider, verified installer, hardware checks, and model
 * selection flow as built-in catalog entries.
 */
public final class HuggingFaceLocalModelDiscovery {
    private static final String API = "https://huggingface.co/api/models";
    private static final String USER_AGENT = "Koil-LocalModelCatalog/1";
    private static final Path CACHE_PATH = Path.of("koil", "sys", "model", "hugging-face-catalog-cache.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(12);
    private static final int FEED_LIMIT = 64;
    private static final int MAX_RESOLVED_FEED_MODELS = 48;
    private static final List<String> QUANTIZATION_PRIORITY = List.of(
            "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q6_K", "Q8_0", "Q4_0", "Q3_K_M", "Q2_K"
    );
    private static final Set<String> TRUSTED_CONVERTERS = Set.of(
            "ggml-org", "bartowski", "unsloth", "lmstudio-community", "mradermacher"
    );
    private static final Pattern PARAMETER_PATTERN = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*([MBT])(?:-A(\\d+(?:\\.\\d+)?)[MBT]?)?"
    );
    private static final Pattern SHARD_SUFFIX = Pattern.compile("(?i)-\\d{5}-of-\\d{5}\\.gguf$");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "koil-hf-model-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<Map<String, LocalModelCatalogEntry>> OVERRIDES =
            new AtomicReference<>(Map.of());
    private static final AtomicReference<List<LocalModelCatalogEntry>> DISCOVERED =
            new AtomicReference<>(List.of());
    private static final AtomicReference<RefreshResult> LAST_RESULT =
            new AtomicReference<>(new RefreshResult(0, 0, 0, "not refreshed"));
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile long lastRefreshMillis;

    static {
        restoreCache();
    }

    private HuggingFaceLocalModelDiscovery() {
    }

    public static List<LocalModelCatalogEntry> merge(List<LocalModelCatalogEntry> builtIns) {
        List<LocalModelCatalogEntry> base = builtIns == null ? List.of() : builtIns;
        Map<String, LocalModelCatalogEntry> overrides = OVERRIDES.get();
        LinkedHashMap<String, LocalModelCatalogEntry> merged = new LinkedHashMap<>();
        LinkedHashSet<String> variants = new LinkedHashSet<>();

        for (LocalModelCatalogEntry entry : base) {
            LocalModelCatalogEntry value = overrides.getOrDefault(entry.id(), entry);
            merged.put(value.id(), value);
            variants.add(value.canonical().variantKey());
        }
        for (LocalModelCatalogEntry entry : DISCOVERED.get()) {
            if (merged.containsKey(entry.id())) {
                continue;
            }
            if (!variants.add(entry.canonical().variantKey())) {
                continue;
            }
            merged.put(entry.id(), entry);
        }
        return List.copyOf(merged.values());
    }

    public static boolean canResolve(LocalModelCatalogEntry entry) {
        if (entry == null || entry.runnable()) {
            return entry != null && entry.runnable();
        }
        LocalModelCanonicalMetadata canonical = entry.canonical();
        return "llama_cpp".equals(entry.providerId())
                && canonical.modalities().contains("text")
                && canonical.architecture() != Architecture.DIFFUSION
                && canonical.architecture() != Architecture.AUDIO
                && canonical.architecture() != Architecture.EMBEDDING
                && canonical.architecture() != Architecture.RERANKER;
    }

    public static CompletableFuture<RefreshResult> refresh(
            List<LocalModelCatalogEntry> builtIns,
            boolean force
    ) {
        long now = System.currentTimeMillis();
        if (!force && lastRefreshMillis > 0L
                && now - lastRefreshMillis < REFRESH_INTERVAL.toMillis()) {
            return CompletableFuture.completedFuture(LAST_RESULT.get());
        }
        if (!REFRESHING.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(LAST_RESULT.get());
        }
        return CompletableFuture.supplyAsync(() -> refreshBlocking(builtIns), WORKER)
                .whenComplete((ignored, failure) -> REFRESHING.set(false));
    }

    public static CompletableFuture<Optional<LocalModelCatalogEntry>> resolveForInstall(
            LocalModelCatalogEntry entry,
            List<LocalModelCatalogEntry> builtIns
    ) {
        if (entry == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (entry.runnable()) {
            return CompletableFuture.completedFuture(Optional.of(entry));
        }
        LocalModelCatalogEntry cached = OVERRIDES.get().get(entry.id());
        if (cached != null && cached.runnable()) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        if (!canResolve(entry)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> resolveKnownEntry(entry), WORKER);
    }

    public static CompletableFuture<SearchResult> search(
            String query,
            List<LocalModelCatalogEntry> builtIns
    ) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return CompletableFuture.completedFuture(new SearchResult(0, 0, 0, "Search query is empty."));
        }
        return CompletableFuture.supplyAsync(() -> searchBlocking(normalized, builtIns), WORKER);
    }

    /** Resolves one explicit Hugging Face GGUF file into the normal Koil catalog. */
    public static CompletableFuture<DirectFileResult> registerDirectFile(
            String rawUrl,
            List<LocalModelCatalogEntry> builtIns
    ) {
        return CompletableFuture.supplyAsync(() -> registerDirectFileBlocking(rawUrl, builtIns), WORKER);
    }

    public static RefreshResult lastResult() {
        return LAST_RESULT.get();
    }

    private static DirectFileResult registerDirectFileBlocking(
            String rawUrl,
            List<LocalModelCatalogEntry> builtIns
    ) {
        try {
            DirectHuggingFaceFile direct = parseDirectFile(rawUrl);
            JsonObject detail = modelInfo(direct.repository(), true).orElse(null);
            if (detail == null) {
                return DirectFileResult.failed("Hugging Face repository metadata could not be read.");
            }
            if (gated(detail)) {
                return DirectFileResult.failed("This Hugging Face repository is gated and cannot be installed anonymously.");
            }
            Optional<ModelArtifact> artifact = artifactFromDetail(
                    direct.repository(), direct.remotePath(), detail
            ).or(() -> artifactFromHead(direct.repository(), direct.remotePath()));
            if (artifact.isEmpty()) {
                return DirectFileResult.failed(
                        "The exact GGUF file did not expose a verifiable LFS size and SHA-256 digest."
                );
            }
            String quantization = quantizationFromFile(direct.remotePath());
            ResolvedRepository resolved = new ResolvedRepository(
                    direct.repository(), quantization, List.of(artifact.get()), detail
            );
            LocalModelCatalogEntry candidate = buildDiscovered(detail, resolved).orElse(null);
            if (candidate == null) {
                return DirectFileResult.failed(
                        "The repository does not expose enough text-model metadata to build a runnable Koil entry."
                );
            }
            LocalModelCatalogEntry registered = registerDirectCandidate(candidate, builtIns);
            persistCache();
            return new DirectFileResult(true, registered,
                    "Resolved exact Hugging Face GGUF with verified size and SHA-256.");
        } catch (Exception exception) {
            return DirectFileResult.failed(safeMessage(exception));
        }
    }

    private static SearchResult searchBlocking(
            String query,
            List<LocalModelCatalogEntry> builtIns
    ) {
        try {
            LinkedHashMap<String, JsonObject> candidates = new LinkedHashMap<>();

            // If the user supplied owner/repository, probe it directly before fuzzy search.
            if (query.contains("/") && !query.contains(" ")) {
                modelInfo(query, false).ifPresent(value -> candidates.put(query.toLowerCase(Locale.ROOT), value));
            }

            URI searchUri = URI.create(API
                    + "?search=" + encodeQuery(query)
                    + "&filter=gguf&pipeline_tag=text-generation&sort=downloads&direction=-1&limit=32&full=true");
            for (JsonElement element : fetchArray(searchUri)) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject summary = element.getAsJsonObject();
                String repository = modelId(summary);
                if (!repository.isBlank() && !gated(summary)) {
                    candidates.putIfAbsent(repository.toLowerCase(Locale.ROOT), summary);
                }
            }

            List<LocalModelCatalogEntry> added = new ArrayList<>();
            int resolvedCount = 0;
            int promotedCount = 0;
            for (JsonObject summary : candidates.values()) {
                if (resolvedCount >= 12) {
                    break;
                }
                String repository = modelId(summary);
                if (repository.isBlank() || gated(summary) || !looksConversational(summary)) {
                    continue;
                }
                Optional<ResolvedRepository> resolved = resolveRepository(repository);
                if (resolved.isEmpty()) {
                    continue;
                }
                resolvedCount++;

                LocalModelCatalogEntry known = bestKnownMatch(summary, repository, builtIns);
                if (known != null && !known.runnable() && canResolve(known)) {
                    registerOverride(promoteKnown(known, summary, resolved.get()));
                    promotedCount++;
                    continue;
                }

                if (known == null) {
                    buildDiscovered(summary, resolved.get()).ifPresent(added::add);
                }
            }

            int before = DISCOVERED.get().size();
            if (!added.isEmpty()) {
                List<LocalModelCatalogEntry> combined = new ArrayList<>(DISCOVERED.get());
                combined.addAll(added);
                DISCOVERED.set(deduplicateDiscovered(combined, builtIns));
                persistCache();
            }
            int newlyAdded = Math.max(0, DISCOVERED.get().size() - before);
            return new SearchResult(
                    candidates.size(),
                    promotedCount,
                    newlyAdded,
                    "Hugging Face GGUF search completed for " + query
            );
        } catch (Exception exception) {
            return new SearchResult(
                    0,
                    0,
                    0,
                    "Hugging Face search failed: " + safeMessage(exception)
            );
        }
    }

    private static RefreshResult refreshBlocking(List<LocalModelCatalogEntry> builtIns) {
        try {
            LinkedHashMap<String, JsonObject> summaries = new LinkedHashMap<>();
            appendFeed(summaries, feedUri("downloads"));
            appendFeed(summaries, feedUri("lastModified"));

            List<LocalModelCatalogEntry> discovered = new ArrayList<>();
            int checked = 0;
            int promoted = 0;
            for (JsonObject summary : summaries.values()) {
                if (checked >= MAX_RESOLVED_FEED_MODELS) {
                    break;
                }
                String repository = modelId(summary);
                if (repository.isBlank() || gated(summary) || !looksConversational(summary)) {
                    continue;
                }
                checked++;
                Optional<ResolvedRepository> resolved = resolveRepository(repository);
                if (resolved.isEmpty()) {
                    continue;
                }
                LocalModelCatalogEntry known = bestKnownMatch(summary, repository, builtIns);
                if (known != null && !known.runnable() && canResolve(known)) {
                    LocalModelCatalogEntry upgraded = promoteKnown(known, summary, resolved.get());
                    registerOverride(upgraded);
                    promoted++;
                    continue;
                }
                if (known == null) {
                    buildDiscovered(summary, resolved.get()).ifPresent(discovered::add);
                }
            }

            List<LocalModelCatalogEntry> combinedDiscovered = new ArrayList<>(DISCOVERED.get());
            combinedDiscovered.addAll(discovered);
            DISCOVERED.set(deduplicateDiscovered(combinedDiscovered, builtIns));
            persistCache();
            lastRefreshMillis = System.currentTimeMillis();
            RefreshResult result = new RefreshResult(
                    summaries.size(),
                    promoted,
                    DISCOVERED.get().size(),
                    "Hugging Face GGUF discovery refreshed"
            );
            LAST_RESULT.set(result);
            return result;
        } catch (Exception exception) {
            RefreshResult result = new RefreshResult(
                    0,
                    0,
                    DISCOVERED.get().size(),
                    "Hugging Face discovery failed: " + safeMessage(exception)
            );
            LAST_RESULT.set(result);
            return result;
        }
    }

    private static Optional<LocalModelCatalogEntry> resolveKnownEntry(LocalModelCatalogEntry entry) {
        try {
            String canonicalRepository = entry.canonical().canonicalRepository();
            LinkedHashMap<String, JsonObject> candidates = new LinkedHashMap<>();

            for (String direct : directRepositoryCandidates(canonicalRepository)) {
                JsonObject summary = modelInfo(direct, false).orElse(null);
                if (summary != null) {
                    candidates.put(direct.toLowerCase(Locale.ROOT), summary);
                }
            }

            String search = canonicalRepository.isBlank()
                    ? entry.displayName()
                    : repositoryName(canonicalRepository);
            URI searchUri = URI.create(API
                    + "?search=" + encodeQuery(search)
                    + "&filter=gguf&pipeline_tag=text-generation&sort=downloads&direction=-1&limit=24&full=true");
            JsonArray searchResults = fetchArray(searchUri);
            for (JsonElement element : searchResults) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject summary = element.getAsJsonObject();
                String repository = modelId(summary);
                if (!repository.isBlank() && !gated(summary)) {
                    candidates.putIfAbsent(repository.toLowerCase(Locale.ROOT), summary);
                }
            }

            List<JsonObject> ranked = new ArrayList<>(candidates.values());
            ranked.sort(Comparator.comparingInt((JsonObject value) ->
                    matchScore(entry, value, modelId(value))).reversed());
            for (JsonObject candidate : ranked) {
                String repository = modelId(candidate);
                if (matchScore(entry, candidate, repository) < 55) {
                    continue;
                }
                Optional<ResolvedRepository> resolved = resolveRepository(repository);
                if (resolved.isEmpty()) {
                    continue;
                }
                LocalModelCatalogEntry upgraded = promoteKnown(entry, candidate, resolved.get());
                registerOverride(upgraded);
                return Optional.of(upgraded);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static LocalModelCatalogEntry promoteKnown(
            LocalModelCatalogEntry base,
            JsonObject summary,
            ResolvedRepository resolved
    ) {
        LocalModelCanonicalMetadata old = base.canonical();
        String quantization = resolved.quantization();
        LinkedHashSet<String> formats = new LinkedHashSet<>(old.runtimeFormats());
        formats.add("GGUF");
        if (!quantization.isBlank()) {
            formats.add(quantization);
        }
        LocalModelCanonicalMetadata canonical = new LocalModelCanonicalMetadata(
                old.family(),
                old.generation(),
                old.baseCapability(),
                old.totalParametersBillions(),
                old.activeParametersBillions(),
                old.architecture(),
                old.modelType(),
                old.modifiers(),
                old.modalities(),
                old.nativeContextTokens(),
                old.extendedContextTokens(),
                old.canonicalRepository(),
                old.canonicalParent(),
                old.maturity(),
                old.chatTemplateAdapter(),
                old.reasoningParserAdapter(),
                old.toolParserAdapter(),
                List.copyOf(formats),
                true,
                ""
        );
        long download = resolved.artifacts().stream().mapToLong(ModelArtifact::sizeBytes).sum();
        long minimum = roundedGiB(download + 2L * gib());
        long recommended = roundedGiB(download + Math.max(4L * gib(), download / 4L));
        List<LocalModelCapabilityTag> tags = new ArrayList<>(base.capabilityTags());
        if (!tags.contains(LocalModelCapabilityTag.CHAT)) {
            tags.add(LocalModelCapabilityTag.CHAT);
        }
        if (base.toolCalling() && !tags.contains(LocalModelCapabilityTag.AUTOMATION_TOOLS)) {
            tags.add(LocalModelCapabilityTag.AUTOMATION_TOOLS);
        }
        String license = modelLicense(summary);
        if (license.isBlank()) {
            license = base.license();
        }
        return LocalModelCatalog.dynamicLocalTextModel(
                base.id(),
                base.displayName(),
                base.modelId(),
                base.parameterCount(),
                quantization,
                license,
                Math.max(512, old.nativeContextTokens()),
                minimum,
                recommended,
                base.complexReasoningEstimatePercent(),
                base.toolCalling(),
                List.copyOf(tags),
                "Hugging Face GGUF implementation resolved dynamically and verified by exact LFS size and SHA-256.",
                resolved.artifacts(),
                canonical
        );
    }

    private static Optional<LocalModelCatalogEntry> buildDiscovered(
            JsonObject summary,
            ResolvedRepository resolved
    ) {
        String repository = resolved.repository();
        String baseRepository = baseRepository(summary, repository);
        String baseName = repositoryName(baseRepository.isBlank() ? repository : baseRepository);
        String cleaned = cleanModelName(baseName);
        ParameterShape shape = parameterShape(cleaned, summary);
        if (shape.capability().equals("Unknown")) {
            return Optional.empty();
        }
        String type = inferType(cleaned, summary);
        if ("Base".equals(type) && !hasConversationTag(summary)) {
            return Optional.empty();
        }
        List<String> modifiers = inferModifiers(cleaned);
        String family = inferFamily(cleaned, shape.capability(), type, modifiers);
        if (family.isBlank()) {
            return Optional.empty();
        }
        Architecture architecture = shape.activeBillions() > 0.0D
                ? Architecture.MOE
                : inferArchitecture(summary, cleaned);
        int context = inferContext(summary);
        String id = "hf-dynamic-" + repository.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String displayName = family + " " + shape.capability()
                + ("Base".equals(type) ? "" : " " + type)
                + (modifiers.isEmpty() ? "" : " " + String.join(" ", modifiers));
        LinkedHashSet<String> formats = new LinkedHashSet<>();
        formats.add("GGUF");
        formats.add(resolved.quantization());
        boolean toolCalling = declaresToolCalling(summary, cleaned, type);
        LocalModelCanonicalMetadata canonical = new LocalModelCanonicalMetadata(
                family,
                family,
                shape.capability(),
                shape.totalBillions(),
                shape.activeBillions(),
                architecture,
                type,
                modifiers,
                List.of("text"),
                context,
                context,
                baseRepository.isBlank() ? repository : baseRepository,
                baseRepository.isBlank() ? "" : repository,
                Maturity.EXPERIMENTAL,
                "llama_cpp_embedded",
                type.equals("Thinking") || type.equals("Reasoning") ? "model_native" : "",
                toolCalling ? "llama_cpp_native" : "",
                List.copyOf(formats),
                true,
                ""
        );
        long download = resolved.artifacts().stream().mapToLong(ModelArtifact::sizeBytes).sum();
        return Optional.of(LocalModelCatalog.dynamicLocalTextModel(
                id,
                displayName,
                id,
                parameterLabel(shape),
                resolved.quantization(),
                modelLicense(summary).isBlank() ? "See Hugging Face model card" : modelLicense(summary),
                context,
                roundedGiB(download + 2L * gib()),
                roundedGiB(download + Math.max(4L * gib(), download / 4L)),
                reasoningEstimate(type),
                toolCalling,
                discoveredCapabilities(type, toolCalling),
                toolCalling
                        ? "Dynamically discovered Hugging Face GGUF with model-declared tool/function calling. Koil enables registered tools and observes runtime reliability."
                        : "Dynamically discovered Hugging Face GGUF. Chat is enabled; no compatible tool/function-calling declaration was found.",
                resolved.artifacts(),
                canonical
        ));
    }

    private static List<LocalModelCapabilityTag> discoveredCapabilities(String type, boolean toolCalling) {
        List<LocalModelCapabilityTag> tags = new ArrayList<>();
        tags.add(LocalModelCapabilityTag.CHAT);
        if ("Coder".equals(type)) tags.add(LocalModelCapabilityTag.CODE);
        if (toolCalling) tags.add(LocalModelCapabilityTag.AUTOMATION_TOOLS);
        return List.copyOf(tags);
    }

    private static boolean declaresToolCalling(JsonObject summary, String name, String type) {
        String combined = (modelId(summary) + " " + name + " " + type + " "
                + String.join(" ", tags(summary))).toLowerCase(Locale.ROOT)
                .replace('_', '-');
        if (combined.contains("tool-use")
                || combined.contains("tool-calling")
                || combined.contains("function-calling")
                || combined.contains("function-call")
                || combined.contains("function calling")
                || combined.contains("tools")
                || "Agent".equals(type)) {
            return true;
        }
        String metadata = summary == null ? "" : summary.toString().toLowerCase(Locale.ROOT);
        return metadata.contains("chat_template")
                && (metadata.contains("<tool_call>")
                || metadata.contains("tool_calls")
                || metadata.contains("function calls")
                || metadata.contains("if tools and tools"));
    }

    private static LocalModelCatalogEntry registerDirectCandidate(
            LocalModelCatalogEntry candidate,
            List<LocalModelCatalogEntry> builtIns
    ) {
        if (builtIns != null) {
            for (LocalModelCatalogEntry existing : builtIns) {
                if (existing.canonical().variantKey().equals(candidate.canonical().variantKey())) {
                    LocalModelCatalogEntry replacement = new LocalModelCatalogEntry(
                            existing.id(), existing.displayName(), candidate.providerId(), candidate.runtimeId(),
                            existing.modelId(), existing.parameterCount(), candidate.quantization(),
                            candidate.license(), candidate.contextTokens(), candidate.estimatedMinimumMemoryBytes(),
                            candidate.estimatedRecommendedMemoryBytes(), existing.complexReasoningEstimatePercent(),
                            existing.toolCalling() || candidate.toolCalling(),
                            candidate.toolCalling() ? discoveredCapabilities(candidate.canonical().modelType(), true)
                                    : existing.capabilityTags(),
                            "User-selected direct Hugging Face GGUF implementation; exact size and SHA-256 verified.",
                            candidate.artifacts(), candidate.canonical()
                    );
                    registerOverride(replacement);
                    return replacement;
                }
            }
        }
        List<LocalModelCatalogEntry> next = new ArrayList<>();
        for (LocalModelCatalogEntry existing : DISCOVERED.get()) {
            if (!existing.id().equals(candidate.id())
                    && !existing.canonical().variantKey().equals(candidate.canonical().variantKey())) {
                next.add(existing);
            }
        }
        next.add(candidate);
        DISCOVERED.set(List.copyOf(next));
        return candidate;
    }

    private static DirectHuggingFaceFile parseDirectFile(String rawUrl) {
        URI uri = URI.create(rawUrl == null ? "" : rawUrl.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"huggingface.co".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Only direct HTTPS huggingface.co model links are supported.");
        }
        String[] parts = uri.getPath().split("/", -1);
        if (parts.length < 6 || parts[1].isBlank() || parts[2].isBlank()
                || !("blob".equals(parts[3]) || "resolve".equals(parts[3]))) {
            throw new IllegalArgumentException("Expected a Hugging Face /blob/main/... or /resolve/main/... file URL.");
        }
        if (!"main".equals(parts[4])) {
            throw new IllegalArgumentException("Direct model installation currently requires the main repository revision.");
        }
        String remotePath = String.join("/", java.util.Arrays.copyOfRange(parts, 5, parts.length));
        if (java.util.Arrays.stream(java.util.Arrays.copyOfRange(parts, 5, parts.length))
                .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("The Hugging Face model path contains an invalid segment.");
        }
        String lower = remotePath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".gguf") || lower.contains("mmproj") || lower.contains("projector")) {
            throw new IllegalArgumentException("The direct link must target one text-model GGUF file.");
        }
        return new DirectHuggingFaceFile(parts[1] + "/" + parts[2], remotePath);
    }

    private static String quantizationFromFile(String remotePath) {
        String upper = baseName(remotePath).toUpperCase(Locale.ROOT);
        for (String quantization : QUANTIZATION_PRIORITY) {
            if (upper.contains(quantization)) return quantization;
        }
        Matcher matcher = Pattern.compile("(?i)(Q[2-8](?:_[A-Z0-9]+)*)").matcher(upper);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "GGUF";
    }

    private static List<LocalModelCatalogEntry> deduplicateDiscovered(
            List<LocalModelCatalogEntry> discovered,
            List<LocalModelCatalogEntry> builtIns
    ) {
        LinkedHashSet<String> builtInVariants = new LinkedHashSet<>();
        if (builtIns != null) {
            for (LocalModelCatalogEntry entry : builtIns) {
                builtInVariants.add(entry.canonical().variantKey());
            }
        }
        LinkedHashMap<String, LocalModelCatalogEntry> result = new LinkedHashMap<>();
        for (LocalModelCatalogEntry entry : discovered) {
            String variant = entry.canonical().variantKey();
            if (!builtInVariants.contains(variant)) result.put(variant, entry);
        }
        return List.copyOf(result.values());
    }

    private static LocalModelCatalogEntry bestKnownMatch(
            JsonObject summary,
            String repository,
            List<LocalModelCatalogEntry> builtIns
    ) {
        if (builtIns == null) {
            return null;
        }
        LocalModelCatalogEntry best = null;
        int bestScore = 0;
        for (LocalModelCatalogEntry entry : builtIns) {
            if (entry.runnable() || !canResolve(entry)) {
                continue;
            }
            int score = matchScore(entry, summary, repository);
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return bestScore >= 65 ? best : null;
    }

    private static int matchScore(LocalModelCatalogEntry entry, JsonObject summary, String repository) {
        String canonical = entry.canonical().canonicalRepository();
        String candidateBase = baseRepository(summary, repository);
        String canonicalKey = normalizeRepositoryKey(canonical);
        String repositoryKey = normalizeRepositoryKey(repository);
        String baseKey = normalizeRepositoryKey(candidateBase);
        String canonicalName = normalizeNameKey(repositoryName(canonical));
        String candidateName = normalizeNameKey(repositoryName(repository));

        if (!canonicalKey.isBlank() && canonicalKey.equals(baseKey)) {
            return 120;
        }
        if (!canonicalKey.isBlank() && canonicalKey.equals(repositoryKey)) {
            return 115;
        }
        if (!canonicalName.isBlank() && canonicalName.equals(normalizeNameKey(repositoryName(candidateBase)))) {
            return 105;
        }
        if (!canonicalName.isBlank() && canonicalName.equals(stripGguf(candidateName))) {
            return sameOwner(canonical, repository) ? 100 : trustedRepository(repository) ? 90 : 80;
        }
        if (!canonicalName.isBlank() && stripGguf(candidateName).contains(canonicalName)) {
            return sameOwner(canonical, repository) ? 85 : trustedRepository(repository) ? 75 : 55;
        }
        return 0;
    }

    private static Optional<ResolvedRepository> resolveRepository(String repository) {
        JsonObject detail = modelInfo(repository, true).orElse(null);
        if (detail == null || gated(detail)) {
            return Optional.empty();
        }
        List<String> files = siblingNames(detail);
        QuantizedFiles selected = chooseQuantizedFiles(files);
        if (selected.files().isEmpty()) {
            return Optional.empty();
        }
        List<ModelArtifact> artifacts = new ArrayList<>();
        for (String remotePath : selected.files()) {
            Optional<ModelArtifact> artifact = artifactFromDetail(repository, remotePath, detail)
                    .or(() -> artifactFromHead(repository, remotePath));
            if (artifact.isEmpty()) {
                return Optional.empty();
            }
            artifacts.add(artifact.get());
        }
        return Optional.of(new ResolvedRepository(repository, selected.quantization(), List.copyOf(artifacts), detail));
    }

    private static Optional<ModelArtifact> artifactFromDetail(
            String repository,
            String remotePath,
            JsonObject detail
    ) {
        JsonArray siblings = array(detail, "siblings");
        for (JsonElement element : siblings) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject sibling = element.getAsJsonObject();
            if (!remotePath.equals(string(sibling, "rfilename"))) {
                continue;
            }
            long size = longValue(sibling, "size", -1L);
            String sha = "";
            JsonObject lfs = object(sibling, "lfs");
            if (lfs != null) {
                if (size < 0L) {
                    size = longValue(lfs, "size", -1L);
                }
                sha = firstNonBlank(string(lfs, "sha256"), string(lfs, "oid"));
            }
            if (size > 0L && sha.matches("(?i)[0-9a-f]{64}")) {
                return Optional.of(new ModelArtifact(
                        baseName(remotePath),
                        resolveUri(repository, remotePath),
                        size,
                        sha
                ));
            }
        }
        return Optional.empty();
    }

    private static Optional<ModelArtifact> artifactFromHead(String repository, String remotePath) {
        try {
            URI uri = resolveUri(repository, remotePath);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", USER_AGENT)
                    .build();
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                return Optional.empty();
            }
            long size = response.headers().firstValue("x-linked-size")
                    .or(() -> response.headers().firstValue("content-length"))
                    .map(HuggingFaceLocalModelDiscovery::parseLong)
                    .orElse(-1L);
            String sha = response.headers().firstValue("x-linked-etag")
                    .or(() -> response.headers().firstValue("etag"))
                    .map(HuggingFaceLocalModelDiscovery::normalizeEtag)
                    .orElse("");
            if (size <= 0L || !sha.matches("[0-9a-f]{64}")) {
                return Optional.empty();
            }
            return Optional.of(new ModelArtifact(baseName(remotePath), uri, size, sha));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static QuantizedFiles chooseQuantizedFiles(List<String> files) {
        List<String> candidates = files.stream()
                .filter(value -> value != null && value.toLowerCase(Locale.ROOT).endsWith(".gguf"))
                .filter(value -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    return !lower.contains("mmproj")
                            && !lower.contains("projector")
                            && !lower.contains("clip")
                            && !lower.contains("embedding")
                            && !lower.contains("reranker");
                })
                .toList();
        for (String quantization : QUANTIZATION_PRIORITY) {
            List<String> matching = candidates.stream()
                    .filter(value -> value.toUpperCase(Locale.ROOT).contains(quantization))
                    .sorted()
                    .toList();
            if (matching.isEmpty()) {
                continue;
            }
            String first = matching.get(0);
            if (SHARD_SUFFIX.matcher(first).find()) {
                String stem = SHARD_SUFFIX.matcher(first).replaceFirst("");
                List<String> shards = matching.stream()
                        .filter(value -> SHARD_SUFFIX.matcher(value).find())
                        .filter(value -> SHARD_SUFFIX.matcher(value).replaceFirst("").equals(stem))
                        .sorted()
                        .toList();
                if (!shards.isEmpty()) {
                    return new QuantizedFiles(quantization, shards);
                }
            }
            return new QuantizedFiles(quantization, List.of(first));
        }
        return new QuantizedFiles("", List.of());
    }

    private static Optional<JsonObject> modelInfo(String repository, boolean fileMetadata) {
        if (repository == null || repository.isBlank() || !repository.contains("/")) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(API + "/" + encodeRepository(repository)
                    + (fileMetadata ? "?blobs=true" : "?full=true"));
            JsonElement value = fetchJson(uri);
            return value != null && value.isJsonObject()
                    ? Optional.of(value.getAsJsonObject())
                    : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void appendFeed(Map<String, JsonObject> target, URI uri) {
        JsonArray values = fetchArray(uri);
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String id = modelId(object);
            if (!id.isBlank()) {
                target.putIfAbsent(id.toLowerCase(Locale.ROOT), object);
            }
        }
    }

    private static URI feedUri(String sort) {
        return URI.create(API
                + "?filter=gguf&pipeline_tag=text-generation&sort=" + sort
                + "&direction=-1&limit=" + FEED_LIMIT + "&full=true");
    }

    private static JsonArray fetchArray(URI uri) {
        JsonElement value = fetchJson(uri);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonElement fetchJson(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return JsonParser.parseString(response.body());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> directRepositoryCandidates(String canonicalRepository) {
        if (canonicalRepository == null || canonicalRepository.isBlank() || !canonicalRepository.contains("/")) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String repository = canonicalRepository.trim();
        String owner = repository.substring(0, repository.indexOf('/'));
        String name = repositoryName(repository);
        if (name.toLowerCase(Locale.ROOT).endsWith("-gguf")) {
            result.add(repository);
        } else {
            result.add(owner + "/" + name + "-GGUF");
            for (String converter : TRUSTED_CONVERTERS) {
                result.add(converter + "/" + name + "-GGUF");
            }
        }
        return List.copyOf(result);
    }

    private static void registerOverride(LocalModelCatalogEntry entry) {
        for (;;) {
            Map<String, LocalModelCatalogEntry> current = OVERRIDES.get();
            LinkedHashMap<String, LocalModelCatalogEntry> next = new LinkedHashMap<>(current);
            next.put(entry.id(), entry);
            if (OVERRIDES.compareAndSet(current, Map.copyOf(next))) {
                persistCache();
                return;
            }
        }
    }

    private static void restoreCache() {
        if (!Files.isRegularFile(CACHE_PATH)) {
            return;
        }
        try {
            CacheState state = GSON.fromJson(
                    Files.readString(CACHE_PATH, StandardCharsets.UTF_8),
                    CacheState.class
            );
            if (state == null) {
                return;
            }
            LinkedHashMap<String, LocalModelCatalogEntry> overrides = new LinkedHashMap<>();
            for (LocalModelCatalogEntry entry : safeEntries(state.overrides())) {
                if (validCachedTextEntry(entry)) {
                    overrides.put(entry.id(), entry);
                }
            }
            List<LocalModelCatalogEntry> discovered = new ArrayList<>();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (LocalModelCatalogEntry entry : safeEntries(state.discovered())) {
                if (validCachedTextEntry(entry) && ids.add(entry.id())) {
                    discovered.add(entry);
                }
            }
            OVERRIDES.set(Map.copyOf(overrides));
            DISCOVERED.set(List.copyOf(discovered));
        } catch (Exception ignored) {
            // A corrupt/stale cache must never prevent Koil from loading its built-in catalog.
        }
    }

    private static synchronized void persistCache() {
        try {
            Path parent = CACHE_PATH.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            CacheState state = new CacheState(
                    List.copyOf(OVERRIDES.get().values()),
                    DISCOVERED.get()
            );
            Path temp = Files.createTempFile(parent, "hugging-face-catalog-", ".tmp");
            Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, CACHE_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, CACHE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Discovery remains usable in-memory if the cache cannot be written.
        }
    }

    private static List<LocalModelCatalogEntry> safeEntries(List<LocalModelCatalogEntry> entries) {
        return entries == null ? List.of() : entries;
    }

    private static boolean validCachedTextEntry(LocalModelCatalogEntry entry) {
        return entry != null
                && entry.runnable()
                && "llama_cpp".equals(entry.providerId())
                && entry.canonical().modalities().contains("text")
                && !entry.artifacts().isEmpty();
    }

    private static boolean looksConversational(JsonObject model) {
        String pipeline = firstNonBlank(string(model, "pipeline_tag"), string(model, "pipelineTag"));
        return pipeline.isBlank()
                || "text-generation".equalsIgnoreCase(pipeline)
                || hasConversationTag(model);
    }

    private static boolean hasConversationTag(JsonObject model) {
        for (String tag : tags(model)) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.equals("conversational") || lower.equals("text-generation") || lower.equals("chat")) {
                return true;
            }
        }
        String name = modelId(model).toLowerCase(Locale.ROOT);
        return name.contains("instruct") || name.contains("chat") || name.contains("thinking")
                || name.contains("reasoning") || name.contains("coder") || name.contains("agent");
    }

    private static boolean gated(JsonObject model) {
        JsonElement gated = model == null ? null : model.get("gated");
        if (gated == null || gated.isJsonNull()) {
            return false;
        }
        try {
            if (gated.isJsonPrimitive() && gated.getAsJsonPrimitive().isBoolean()) {
                return gated.getAsBoolean();
            }
            String value = gated.getAsString();
            return !value.isBlank() && !"false".equalsIgnoreCase(value);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String baseRepository(JsonObject model, String fallback) {
        JsonElement bases = model == null ? null : first(model, "baseModels", "base_models");
        String value = firstBaseModel(bases);
        if (!value.isBlank()) {
            return value;
        }
        JsonObject card = object(model, "cardData");
        if (card == null) {
            card = object(model, "card_data");
        }
        if (card != null) {
            value = firstBaseModel(first(card, "base_model", "baseModel", "base_models"));
            if (!value.isBlank()) {
                return value;
            }
        }
        if (fallback != null && fallback.toLowerCase(Locale.ROOT).endsWith("-gguf")) {
            return fallback.substring(0, fallback.length() - 5);
        }
        return "";
    }

    private static String firstBaseModel(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            if (element.isJsonPrimitive()) {
                return element.getAsString().trim();
            }
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    String value = firstBaseModel(child);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                return firstNonBlank(string(object, "id"), string(object, "name"), string(object, "model"));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static ParameterShape parameterShape(String name, JsonObject summary) {
        JsonObject gguf = object(summary, "gguf");
        double total = gguf == null ? 0.0D : normalizedBillions(number(gguf, "total", 0.0D));
        double active = gguf == null ? 0.0D : normalizedBillions(number(gguf, "active", 0.0D));
        Matcher matcher = PARAMETER_PATTERN.matcher(name == null ? "" : name);
        String capability = "Unknown";
        if (matcher.find()) {
            double namedTotal = toBillions(number(matcher.group(1)), matcher.group(2));
            double namedActive = matcher.group(3) == null ? 0.0D : toBillions(number(matcher.group(3)), matcher.group(2));
            if (total <= 0.0D) total = namedTotal;
            if (active <= 0.0D) active = namedActive;
            capability = matcher.group(0).toUpperCase(Locale.ROOT).replace(" ", "");
        } else if (total > 0.0D) {
            capability = formatBillions(total);
        }
        return new ParameterShape(capability, total, active);
    }

    private static Architecture inferArchitecture(JsonObject summary, String name) {
        String combined = (name + " " + String.join(" ", tags(summary))).toLowerCase(Locale.ROOT);
        if (combined.contains("hybrid") || combined.contains("mamba") || combined.contains("ssm")) {
            return Architecture.HYBRID;
        }
        if (combined.contains("moe") || combined.contains("mixture-of-experts")) {
            return Architecture.MOE;
        }
        return Architecture.DENSE;
    }

    private static int inferContext(JsonObject summary) {
        JsonObject config = object(summary, "config");
        int value = firstPositiveInt(config,
                "max_position_embeddings", "model_max_length", "max_sequence_length", "seq_length", "n_ctx");
        if (value > 0) {
            return Math.min(2_000_000, value);
        }
        return 32_768;
    }

    private static String inferType(String name, JsonObject summary) {
        String lower = (name + " " + String.join(" ", tags(summary))).toLowerCase(Locale.ROOT);
        if (lower.contains("thinking")) return "Thinking";
        if (lower.contains("reasoning") || lower.contains("reasoner")) return "Reasoning";
        if (lower.contains("coder") || lower.contains("code")) return "Coder";
        if (lower.contains("agent")) return "Agent";
        if (lower.contains("instruct")) return "Instruct";
        if (lower.contains("chat")) return "Chat";
        return "Base";
    }

    private static List<String> inferModifiers(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (lower.contains("uncensored")) result.add("Uncensored");
        if (lower.contains("abliterated")) result.add("Abliterated");
        if (lower.contains("fine-tuned") || lower.contains("finetuned")) result.add("Fine-tuned");
        return List.copyOf(result);
    }

    private static String inferFamily(String name, String capability, String type, List<String> modifiers) {
        String value = name == null ? "" : name.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        if (!"Unknown".equals(capability)) {
            value = value.replaceFirst("(?i)\\b" + Pattern.quote(capability.replace("-", "[- ]?")) + "\\b", "");
        }
        value = value.replaceAll("(?i)\\b(instruct|chat|thinking|reasoning|reasoner|coder|code|agent)\\b", " ");
        for (String modifier : modifiers) {
            value = value.replaceAll("(?i)\\b" + Pattern.quote(modifier.replace("-", " ")) + "\\b", " ");
        }
        value = value.replaceAll("(?i)\\bGGUF\\b", " ").replaceAll("\\s+", " ").trim();
        return value.isBlank() ? name : value;
    }

    private static String modelLicense(JsonObject model) {
        JsonObject card = object(model, "cardData");
        if (card == null) card = object(model, "card_data");
        String license = card == null ? "" : string(card, "license");
        return license.isBlank() ? "" : license;
    }

    private static List<String> tags(JsonObject model) {
        JsonArray array = array(model, "tags");
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                if (element.isJsonPrimitive()) values.add(element.getAsString());
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    private static List<String> siblingNames(JsonObject detail) {
        JsonArray siblings = array(detail, "siblings");
        List<String> values = new ArrayList<>();
        for (JsonElement element : siblings) {
            if (element.isJsonObject()) {
                String name = string(element.getAsJsonObject(), "rfilename");
                if (!name.isBlank()) values.add(name);
            }
        }
        return values;
    }

    private static String modelId(JsonObject model) {
        return firstNonBlank(string(model, "id"), string(model, "modelId"));
    }

    private static String repositoryName(String repository) {
        if (repository == null) return "";
        int slash = repository.lastIndexOf('/');
        return slash < 0 ? repository : repository.substring(slash + 1);
    }

    private static String cleanModelName(String value) {
        String name = value == null ? "" : value;
        name = name.replaceAll("(?i)[-_]GGUF$", "");
        name = name.replaceAll("(?i)[-_](Q[234568](?:_[K0-9MSL]+)?|FP8|BF16|FP16|MXFP4|NVFP4)$", "");
        return name;
    }

    private static String normalizeRepositoryKey(String repository) {
        if (repository == null) return "";
        String[] parts = repository.toLowerCase(Locale.ROOT).split("/", 2);
        if (parts.length != 2) return normalizeNameKey(repository);
        return parts[0] + "/" + stripGguf(normalizeNameKey(parts[1]));
    }

    private static String normalizeNameKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String stripGguf(String value) {
        return value != null && value.endsWith("gguf") ? value.substring(0, value.length() - 4) : value;
    }

    private static boolean sameOwner(String first, String second) {
        if (first == null || second == null || !first.contains("/") || !second.contains("/")) return false;
        return first.substring(0, first.indexOf('/')).equalsIgnoreCase(second.substring(0, second.indexOf('/')));
    }

    private static boolean trustedRepository(String repository) {
        if (repository == null || !repository.contains("/")) return false;
        String owner = repository.substring(0, repository.indexOf('/')).toLowerCase(Locale.ROOT);
        return TRUSTED_CONVERTERS.contains(owner);
    }

    private static URI resolveUri(String repository, String remotePath) {
        return URI.create("https://huggingface.co/" + encodeRepository(repository)
                + "/resolve/main/" + encodePath(remotePath));
    }

    private static String encodeRepository(String repository) {
        String[] parts = repository.split("/");
        List<String> encoded = new ArrayList<>();
        for (String part : parts) encoded.add(encodePathSegment(part));
        return String.join("/", encoded);
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        List<String> encoded = new ArrayList<>();
        for (String part : parts) encoded.add(encodePathSegment(part));
        return String.join("/", encoded);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String normalizeEtag(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("w/")) normalized = normalized.substring(2).trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double normalizedBillions(double value) {
        return value > 10_000.0D ? value / 1_000_000_000.0D : value;
    }

    private static double toBillions(double value, String unit) {
        if (unit == null) return value;
        return switch (unit.toUpperCase(Locale.ROOT)) {
            case "M" -> value / 1_000.0D;
            case "T" -> value * 1_000.0D;
            default -> value;
        };
    }

    private static String parameterLabel(ParameterShape shape) {
        if (shape.totalBillions() <= 0.0D) return shape.capability();
        String total = formatBillions(shape.totalBillions());
        return shape.activeBillions() > 0.0D && shape.activeBillions() < shape.totalBillions()
                ? total + " / " + formatBillions(shape.activeBillions()) + " active"
                : total;
    }

    private static String formatBillions(double value) {
        if (value >= 1_000.0D) return trim(value / 1_000.0D) + "T";
        if (value < 1.0D) return trim(value * 1_000.0D) + "M";
        return trim(value) + "B";
    }

    private static String trim(double value) {
        if (Math.rint(value) == value) return Long.toString((long) value);
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static int reasoningEstimate(String type) {
        return switch (type == null ? "" : type) {
            case "Thinking", "Reasoning" -> 90;
            case "Agent" -> 85;
            case "Coder" -> 78;
            case "Instruct" -> 65;
            case "Chat" -> 60;
            default -> 50;
        };
    }

    private static long roundedGiB(long bytes) {
        if (bytes <= 0L) return 0L;
        long gib = gib();
        return ((bytes + gib - 1L) / gib) * gib;
    }

    private static long gib() {
        return 1024L * 1024L * 1024L;
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "unknown failure" : failure.getClass().getSimpleName();
        }
        return failure.getMessage().replace('\r', ' ').replace('\n', ' ');
    }

    private static JsonObject object(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                    ? parent.getAsJsonObject(key)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray array(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                    ? parent.getAsJsonArray(key)
                    : new JsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }

    private static JsonElement first(JsonObject parent, String... keys) {
        if (parent == null) return null;
        for (String key : keys) {
            if (parent.has(key) && !parent.get(key).isJsonNull()) return parent.get(key);
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim()
                    : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int firstPositiveInt(JsonObject object, String... keys) {
        if (object == null) return 0;
        for (String key : keys) {
            try {
                if (object.has(key)) {
                    int value = object.get(key).getAsInt();
                    if (value > 0) return value;
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private record CacheState(
            List<LocalModelCatalogEntry> overrides,
            List<LocalModelCatalogEntry> discovered
    ) {
    }

    public record SearchResult(
            int candidatesSeen,
            int builtInModelsPromoted,
            int newModelsAdded,
            String detail
    ) {
        public SearchResult {
            candidatesSeen = Math.max(0, candidatesSeen);
            builtInModelsPromoted = Math.max(0, builtInModelsPromoted);
            newModelsAdded = Math.max(0, newModelsAdded);
            detail = detail == null ? "" : detail.trim();
        }

        public boolean failed() {
            return detail.toLowerCase(Locale.ROOT).startsWith("hugging face search failed");
        }
    }

    public record RefreshResult(
            int candidatesSeen,
            int builtInModelsPromoted,
            int newModelsAdded,
            String detail
    ) {
        public RefreshResult {
            candidatesSeen = Math.max(0, candidatesSeen);
            builtInModelsPromoted = Math.max(0, builtInModelsPromoted);
            newModelsAdded = Math.max(0, newModelsAdded);
            detail = detail == null ? "" : detail.trim();
        }
    }

    public record DirectFileResult(
            boolean resolved,
            LocalModelCatalogEntry entry,
            String detail
    ) {
        public DirectFileResult {
            detail = detail == null ? "" : detail.strip();
        }

        private static DirectFileResult failed(String detail) {
            return new DirectFileResult(false, null, detail);
        }
    }

    private record ResolvedRepository(
            String repository,
            String quantization,
            List<ModelArtifact> artifacts,
            JsonObject detail
    ) {
    }

    private record QuantizedFiles(String quantization, List<String> files) {
    }

    private record ParameterShape(String capability, double totalBillions, double activeBillions) {
    }

    private record DirectHuggingFaceFile(String repository, String remotePath) {
    }
}
