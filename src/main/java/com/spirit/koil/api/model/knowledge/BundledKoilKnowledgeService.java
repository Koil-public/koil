package com.spirit.koil.api.model.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, read-only knowledge bundled with Koil for model self-discovery.
 *
 * <p>The public contract exposes stable document ids rather than classpath or
 * filesystem paths. Only the explicit allowlist below can be loaded, so this
 * service cannot be used as an arbitrary jar-resource reader.</p>
 */
public final class BundledKoilKnowledgeService {
    private static final int MAXIMUM_READ_LINES = 120;
    private static final int MAXIMUM_RESULTS = 8;
    private static final List<DocumentSpec> SPECS = List.of(
        new DocumentSpec("tool-use", "Tool use and capability discovery", "tool-use.md"),
        new DocumentSpec("model-modes", "Model modes and permission boundaries", "model-modes.md"),
        new DocumentSpec("automation-ktl", "Automation, Executor, and KTL", "automation-ktl.md"),
        new DocumentSpec("workspace-files", "Workspace file operations", "workspace-files.md")
    );
    private static volatile Map<String, Document> cachedDocuments;

    private BundledKoilKnowledgeService() {
    }

    public static KnowledgeResult catalog() throws IOException {
        JsonArray entries = new JsonArray();
        Map<String, Document> available = documents();
        SPECS.stream().map(spec -> available.get(spec.id())).filter(java.util.Objects::nonNull).forEach(document -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("document", document.spec().id());
            entry.addProperty("title", document.spec().title());
            entry.addProperty("lines", document.lines().size());
            entries.add(entry);
        });
        JsonObject output = new JsonObject();
        output.add("coreDocuments", entries);
        output.addProperty("documentCount", available.size());
        output.addProperty("coreDocumentCount", entries.size());
        output.addProperty("catalogSummarized", available.size() > entries.size());
        output.addProperty("discovery", "Use operation=search to resolve indexed docs/system, docs/connector, or docs/architecture document ids without listing the complete catalog.");
        output.addProperty("readOnly", true);
        return new KnowledgeResult(output, "Bundled Koil knowledge categories and compact core documents were summarized.");
    }

    public static KnowledgeResult search(String rawQuery, int requestedMaximum) throws IOException {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isBlank()) throw new IOException("query is required for a documentation search.");
        int maximum = Math.max(1, Math.min(MAXIMUM_RESULTS, requestedMaximum));
        Set<String> terms = searchTerms(query);
        List<SearchHit> hits = new ArrayList<>();
        for (Document document : documents().values()) {
            String identity = (document.spec().id() + " " + document.spec().title())
                    .replace('/', ' ').replace('-', ' ').replace('_', ' ');
            int identityScore = score(identity, query, terms);
            if (identityScore > 0) {
                hits.add(new SearchHit(
                        document,
                        1,
                        "Document",
                        document.spec().title() + " | " + document.spec().id(),
                        identityScore + 20
                ));
            }
            String currentSection = "";
            for (int index = 0; index < document.lines().size(); index++) {
                String line = document.lines().get(index);
                if (line.startsWith("#")) currentSection = headingText(line);
                int score = score(line, query, terms);
                if (score > 0) {
                    hits.add(new SearchHit(document, index + 1, currentSection, compact(line, 280), score));
                }
            }
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed()
            .thenComparing(hit -> hit.document().spec().id())
            .thenComparingInt(SearchHit::line));

        JsonArray results = new JsonArray();
        for (SearchHit hit : hits.stream().limit(maximum).toList()) {
            JsonObject result = new JsonObject();
            result.addProperty("document", hit.document().spec().id());
            result.addProperty("title", hit.document().spec().title());
            result.addProperty("section", hit.section());
            result.addProperty("line", hit.line());
            result.addProperty("snippet", hit.snippet());
            results.add(result);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.add("results", results);
        output.addProperty("resultCount", results.size());
        output.addProperty("moreMatches", hits.size() > results.size());
        output.addProperty("readOnly", true);
        return new KnowledgeResult(output, results.isEmpty()
            ? "No bundled Koil documentation matched that query."
            : "Relevant bundled Koil documentation sections were found.");
    }

    public static KnowledgeResult read(
        String rawDocumentId,
        String rawSection,
        int requestedStartLine,
        int requestedMaximumLines
    ) throws IOException {
        String documentId = rawDocumentId == null ? "" : rawDocumentId.strip().toLowerCase(Locale.ROOT);
        Document document = documents().get(documentId);
        if (document == null) {
            throw new IOException("Unknown bundled document '" + documentId + "'. Use operation=catalog or search first.");
        }
        int start = Math.max(1, requestedStartLine);
        int limit = Math.max(1, Math.min(MAXIMUM_READ_LINES, requestedMaximumLines));
        String sectionName = rawSection == null ? "" : rawSection.strip();
        int sectionEnd = document.lines().size();
        if (!sectionName.isBlank()) {
            Section section = findSection(document, sectionName);
            if (section == null) {
                throw new IOException("Unknown section '" + sectionName + "' in " + documentId + ". Use operation=catalog or search first.");
            }
            start = Math.max(section.startLine(), requestedStartLine <= 1 ? section.startLine() : requestedStartLine);
            sectionEnd = section.endLine();
            sectionName = section.title();
        }
        if (start > document.lines().size() || start > sectionEnd) {
            throw new IOException("startLine is beyond the selected document or section.");
        }
        int end = Math.min(sectionEnd, Math.min(document.lines().size(), start + limit - 1));
        StringBuilder text = new StringBuilder();
        for (int line = start; line <= end; line++) {
            if (!text.isEmpty()) text.append('\n');
            text.append(line).append(": ").append(document.lines().get(line - 1));
        }
        JsonObject output = new JsonObject();
        output.addProperty("document", document.spec().id());
        output.addProperty("title", document.spec().title());
        output.addProperty("section", sectionName);
        output.addProperty("startLine", start);
        output.addProperty("endLine", end);
        output.addProperty("totalLines", document.lines().size());
        output.addProperty("text", text.toString());
        boolean hasMore = end < sectionEnd;
        output.addProperty("hasMore", hasMore);
        if (hasMore) output.addProperty("nextStartLine", end + 1);
        output.addProperty("readOnly", true);
        return new KnowledgeResult(output, "A bounded bundled Koil documentation section was read.");
    }

    private static Map<String, Document> documents() throws IOException {
        Map<String, Document> current = cachedDocuments;
        if (current != null) return current;
        synchronized (BundledKoilKnowledgeService.class) {
            current = cachedDocuments;
            if (current != null) return current;
            Map<String, Document> loaded = new LinkedHashMap<>();
            for (DocumentSpec spec : SPECS) loaded.put(spec.id(), load(spec));
            for (DocumentSpec spec : projectDocumentSpecs()) loaded.putIfAbsent(spec.id(), load(spec));
            cachedDocuments = Map.copyOf(loaded);
            return cachedDocuments;
        }
    }

    private static Document load(DocumentSpec spec) throws IOException {
        String resource = "/koil/model/knowledge/" + spec.resource();
        try (InputStream stream = BundledKoilKnowledgeService.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Bundled Koil knowledge resource is missing: " + spec.id());
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
            List<String> lines = List.of(content.split("\n", -1));
            return new Document(spec, lines, sections(lines));
        }
    }

    private static List<DocumentSpec> projectDocumentSpecs() throws IOException {
        String manifest = "/koil/model/knowledge/project-docs.index";
        try (InputStream stream = BundledKoilKnowledgeService.class.getResourceAsStream(manifest)) {
            if (stream == null) return List.of();
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
            List<DocumentSpec> specs = new ArrayList<>();
            for (String rawPath : content.split("\n")) {
                String path = rawPath.strip().replace('\\', '/');
                if (path.isBlank() || path.startsWith("/") || path.contains("..") || !textDocument(path)) continue;
                if (!path.startsWith("systems/")
                    && !path.startsWith("connectors/")
                    && !path.startsWith("architecture/")
                    && !path.startsWith("test-datapacks/")
                    && !path.equals("file-map.md")
                    && !path.equals("project-structure.md")
                    && !path.equals("automation-capability-test-matrix.md")
                    && !path.equals("issues-and-risks.md")
                    && !path.equals("local-model-user-test-guide.md")) continue;
                String id = "docs/" + withoutExtension(path);
                specs.add(new DocumentSpec(id, titleFromPath(path), "project-docs/" + path));
            }
            return List.copyOf(specs);
        }
    }

    private static String titleFromPath(String path) {
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = withoutExtension(name);
        StringBuilder title = new StringBuilder();
        for (String word : name.split("[-_]+")) {
            if (word.isBlank()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.toString();
    }

    private static boolean textDocument(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".mcmeta")
                || lower.endsWith(".ktl") || lower.endsWith(".txt");
    }

    private static String withoutExtension(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    private static List<Section> sections(List<String> lines) {
        List<MutableSection> found = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int level = headingLevel(line);
            if (level <= 0) continue;
            for (int previous = found.size() - 1; previous >= 0; previous--) {
                MutableSection open = found.get(previous);
                if (open.endLine == Integer.MAX_VALUE && open.level >= level) open.endLine = index;
            }
            found.add(new MutableSection(headingText(line), level, index + 1, Integer.MAX_VALUE));
        }
        return found.stream().map(section -> new Section(
            section.title,
            section.startLine,
            section.endLine == Integer.MAX_VALUE ? lines.size() : section.endLine
        )).toList();
    }

    private static Section findSection(Document document, String requested) {
        String normalized = requested.toLowerCase(Locale.ROOT);
        Section partial = null;
        for (Section section : document.sections()) {
            String title = section.title().toLowerCase(Locale.ROOT);
            if (title.equals(normalized)) return section;
            if (partial == null && title.contains(normalized)) partial = section;
        }
        return partial;
    }

    private static int headingLevel(String line) {
        if (line == null) return 0;
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        return level > 0 && level <= 6 && level < line.length() && line.charAt(level) == ' ' ? level : 0;
    }

    private static String headingText(String line) {
        int level = headingLevel(line);
        return level == 0 ? "" : line.substring(level + 1).strip();
    }

    private static Set<String> searchTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_.-]+")) {
            if (term.length() >= 2) terms.add(term);
        }
        return terms;
    }

    private static int score(String line, String query, Set<String> terms) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        int score = normalized.contains(query.toLowerCase(Locale.ROOT)) ? 8 : 0;
        for (String term : terms) if (normalized.contains(term)) score += 2;
        if (line != null && line.startsWith("#") && score > 0) score += 3;
        return score;
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    public record KnowledgeResult(JsonObject output, String detail) {
    }

    private record DocumentSpec(String id, String title, String resource) {
    }

    private record Document(DocumentSpec spec, List<String> lines, List<Section> sections) {
    }

    private record Section(String title, int startLine, int endLine) {
    }

    private record SearchHit(Document document, int line, String section, String snippet, int score) {
    }

    private static final class MutableSection {
        private final String title;
        private final int level;
        private final int startLine;
        private int endLine;

        private MutableSection(String title, int level, int startLine, int endLine) {
            this.title = title;
            this.level = level;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}
