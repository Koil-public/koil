package com.spirit.koil.api.model.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Bounded catalog search/pagination shared by chat commands and future catalog surfaces. */
public final class LocalModelCatalogView {
    private LocalModelCatalogView() {
    }

    public static Page page(List<LocalModelCatalogEntry> entries, int requestedPage, int requestedPageSize) {
        List<LocalModelCatalogEntry> snapshot = entries == null ? List.of() : List.copyOf(entries);
        int pageSize = Math.max(1, Math.min(50, requestedPageSize));
        int pageCount = Math.max(1, (snapshot.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(pageCount, requestedPage));
        int from = Math.min(snapshot.size(), (page - 1) * pageSize);
        int to = Math.min(snapshot.size(), from + pageSize);
        return new Page(List.copyOf(snapshot.subList(from, to)), page, pageCount, snapshot.size());
    }

    public static List<LocalModelCatalogEntry> search(List<LocalModelCatalogEntry> entries, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
        if (normalized.isBlank()) return List.of();
        List<String> terms = List.of(normalized.split(" "));
        return (entries == null ? List.<LocalModelCatalogEntry>of() : entries).stream()
                .filter(entry -> terms.stream().allMatch(searchText(entry)::contains))
                .sorted(Comparator.comparing(LocalModelCatalogEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String searchText(LocalModelCatalogEntry entry) {
        if (entry == null) return "";
        return String.join(" ",
                entry.id(), entry.displayName(), entry.modelId(), entry.parameterCount(), entry.summary(),
                entry.canonical().family(), entry.canonical().generation(), entry.canonical().baseCapability(),
                entry.canonical().modelType(), entry.canonical().canonicalRepository(),
                entry.canonical().canonicalParent(), String.join(" ", entry.canonical().modifiers()),
                String.join(" ", entry.canonical().modalities()))
                .toLowerCase(Locale.ROOT);
    }

    public record Page(List<LocalModelCatalogEntry> entries, int page, int pageCount, int totalEntries) {
        public Page {
            entries = entries == null ? List.of() : List.copyOf(entries);
            page = Math.max(1, page);
            pageCount = Math.max(1, pageCount);
            totalEntries = Math.max(0, totalEntries);
        }
    }
}
