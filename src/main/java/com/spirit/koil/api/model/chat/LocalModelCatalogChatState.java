package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCatalogView;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Request-local catalog view state for the bottom chat panel. */
public final class LocalModelCatalogChatState {
    private static final int PAGE_SIZE = 12;
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>();

    private LocalModelCatalogChatState() {
    }

    public static void show(List<LocalModelCatalogEntry> entries, int requestedPage, String title, String detail) {
        LocalModelCatalogView.Page page = LocalModelCatalogView.page(entries, requestedPage, PAGE_SIZE);
        SNAPSHOT.set(new Snapshot(page.entries(), page.page(), page.pageCount(), page.totalEntries(), title, detail));
    }

    public static void page(List<LocalModelCatalogEntry> entries, int requestedPage) {
        Snapshot current = SNAPSHOT.get();
        show(entries, requestedPage, current == null ? "Local Model Catalog" : current.title(),
                current == null ? "" : current.detail());
    }

    public static Snapshot snapshot() {
        return SNAPSHOT.get();
    }

    public static void detail(String detail) {
        SNAPSHOT.updateAndGet(current -> current == null ? null : current.withDetail(detail));
    }

    public static void close() {
        SNAPSHOT.set(null);
    }

    public record Snapshot(
            List<LocalModelCatalogEntry> entries,
            int page,
            int pageCount,
            int totalEntries,
            String title,
            String detail
    ) {
        public Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
            page = Math.max(1, page);
            pageCount = Math.max(1, pageCount);
            totalEntries = Math.max(0, totalEntries);
            title = title == null || title.isBlank() ? "Local Model Catalog" : title.strip();
            detail = detail == null ? "" : detail.strip();
        }

        private Snapshot withDetail(String nextDetail) {
            return new Snapshot(entries, page, pageCount, totalEntries, title, nextDetail);
        }
    }
}
