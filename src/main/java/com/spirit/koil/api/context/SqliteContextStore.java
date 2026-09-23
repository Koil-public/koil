package com.spirit.koil.api.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded SQLite-backed canonical context store; scope checks prevent cross-session reference access. */
public final class SqliteContextStore implements ContextStore, AutoCloseable {
    private final Connection connection;
    private final Clock clock;
    private final int maximumEntries;
    private boolean closed;

    public SqliteContextStore(Path database, Clock clock, int maximumEntries) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maximumEntries = Math.max(1, maximumEntries);
        try {
            Path absolute = Objects.requireNonNull(database, "database").toAbsolutePath();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = this.connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            createSchema();
            expire();
            trim();
        } catch (IOException | SQLException exception) {
            throw failure("open context store", exception);
        }
    }

    @Override
    public synchronized ContextArtifact register(ContextArtifactRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        expire();
        Instant created = this.clock.instant();
        ContextArtifact artifact = new ContextArtifact(
                "ctx-" + UUID.randomUUID(), request.scopeId(), digest(request.canonicalContent()), request.sourceType(), request.sourceId(),
                request.canonicalContent(), ContextRepresentationLevel.L0_EXACT, created, created.plusSeconds(request.ttlSeconds())
        );
        try (PreparedStatement statement = this.connection.prepareStatement("""
                INSERT INTO context_artifact (id, scope, content_hash, source_type, source_id, canonical_content, level, created_at, expires_at, last_access, pin_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """)) {
            statement.setString(1, artifact.id());
            statement.setString(2, artifact.scopeId());
            statement.setString(3, artifact.contentHash());
            statement.setString(4, artifact.sourceType());
            statement.setString(5, artifact.sourceId());
            statement.setString(6, artifact.canonicalContent());
            statement.setString(7, artifact.level().name());
            statement.setLong(8, artifact.createdAt().toEpochMilli());
            statement.setLong(9, artifact.expiresAt().toEpochMilli());
            statement.setLong(10, created.toEpochMilli());
            statement.executeUpdate();
            trim();
            return artifact;
        } catch (SQLException exception) {
            throw failure("register context artifact", exception);
        }
    }

    @Override
    public synchronized Optional<ContextArtifact> retrieve(String scopeId, String artifactId) {
        ensureOpen();
        expire();
        String scope = normalizeScope(scopeId);
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT id, scope, content_hash, source_type, source_id, canonical_content, level, created_at, expires_at
                FROM context_artifact WHERE id=? AND scope=?
                """)) {
            statement.setString(1, artifactId == null ? "" : artifactId);
            statement.setString(2, scope);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                ContextArtifact artifact = artifact(result);
                touch(artifact.id());
                return Optional.of(artifact);
            }
        } catch (SQLException exception) {
            throw failure("retrieve context artifact", exception);
        }
    }

    @Override
    public synchronized boolean pin(String scopeId, String artifactId) {
        return changePin(normalizeScope(scopeId), artifactId, 1);
    }

    @Override
    public synchronized boolean release(String scopeId, String artifactId) {
        boolean released = changePin(normalizeScope(scopeId), artifactId, -1);
        if (released) trim();
        return released;
    }

    @Override
    public synchronized List<ContextArtifact> search(String scopeId, String query, int limit) {
        ensureOpen();
        expire();
        int maximum = Math.max(1, Math.min(8, limit));
        String needle = query == null ? "" : query.strip();
        // ponytail: SQLite LIKE is the bounded deterministic fallback; P7 may rank selected segments with TurboVec.
        String sql = needle.isBlank() ? """
                SELECT id, scope, content_hash, source_type, source_id, canonical_content, level, created_at, expires_at
                FROM context_artifact WHERE scope=? ORDER BY last_access DESC, id LIMIT ?
                """ : """
                SELECT id, scope, content_hash, source_type, source_id, canonical_content, level, created_at, expires_at
                FROM context_artifact WHERE scope=? AND (source_type LIKE ? ESCAPE '\\' OR source_id LIKE ? ESCAPE '\\' OR canonical_content LIKE ? ESCAPE '\\')
                ORDER BY last_access DESC, id LIMIT ?
                """;
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            statement.setString(1, normalizeScope(scopeId));
            int index = 2;
            if (!needle.isBlank()) {
                String pattern = "%" + escapeLike(needle) + "%";
                statement.setString(index++, pattern);
                statement.setString(index++, pattern);
                statement.setString(index++, pattern);
            }
            statement.setInt(index, maximum);
            try (ResultSet result = statement.executeQuery()) {
                List<ContextArtifact> artifacts = new ArrayList<>();
                while (result.next()) artifacts.add(artifact(result));
                for (ContextArtifact artifact : artifacts) touch(artifact.id());
                return List.copyOf(artifacts);
            }
        } catch (SQLException exception) {
            throw failure("search context artifacts", exception);
        }
    }

    @Override
    public synchronized ContextStoreStats stats(String scopeId) {
        ensureOpen();
        expire();
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN pin_count > 0 THEN 1 ELSE 0 END), 0), COALESCE(SUM(LENGTH(canonical_content)), 0)
                FROM context_artifact WHERE scope=?
                """)) {
            statement.setString(1, normalizeScope(scopeId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new ContextStoreStats(0L, 0L, 0L);
                return new ContextStoreStats(result.getLong(1), result.getLong(2), result.getLong(3));
            }
        } catch (SQLException exception) {
            throw failure("read context statistics", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        try {
            this.connection.close();
        } catch (SQLException exception) {
            throw failure("close context store", exception);
        }
    }

    private boolean changePin(String scope, String artifactId, int delta) {
        ensureOpen();
        expire();
        String id = artifactId == null ? "" : artifactId;
        String query = delta > 0
                ? "UPDATE context_artifact SET pin_count=pin_count+1 WHERE id=? AND scope=?"
                : "UPDATE context_artifact SET pin_count=pin_count-1 WHERE id=? AND scope=? AND pin_count>0";
        try (PreparedStatement statement = this.connection.prepareStatement(query)) {
            statement.setString(1, id);
            statement.setString(2, scope);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure(delta > 0 ? "pin context artifact" : "release context artifact", exception);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS context_artifact (
                    id TEXT PRIMARY KEY, scope TEXT NOT NULL, content_hash TEXT NOT NULL, source_type TEXT NOT NULL, source_id TEXT NOT NULL,
                    canonical_content TEXT NOT NULL, level TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                    last_access INTEGER NOT NULL, pin_count INTEGER NOT NULL)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS context_artifact_expiry ON context_artifact(expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS context_artifact_lru ON context_artifact(pin_count, last_access)");
        }
    }

    private void expire() {
        try (PreparedStatement statement = this.connection.prepareStatement("DELETE FROM context_artifact WHERE expires_at<=?")) {
            statement.setLong(1, this.clock.instant().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("expire context artifacts", exception);
        }
    }

    private void trim() {
        try (PreparedStatement count = this.connection.prepareStatement("SELECT COUNT(*) FROM context_artifact");
             ResultSet result = count.executeQuery()) {
            long excess = result.next() ? result.getLong(1) - this.maximumEntries : 0L;
            if (excess <= 0L) return;
            try (PreparedStatement candidates = this.connection.prepareStatement("""
                    SELECT id FROM context_artifact WHERE pin_count=0 ORDER BY last_access, id LIMIT ?
                    """)) {
                candidates.setLong(1, excess);
                try (ResultSet ids = candidates.executeQuery();
                     PreparedStatement delete = this.connection.prepareStatement("DELETE FROM context_artifact WHERE id=?")) {
                    while (ids.next()) {
                        delete.setString(1, ids.getString(1));
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
            }
        } catch (SQLException exception) {
            throw failure("trim context artifacts", exception);
        }
    }

    private void touch(String artifactId) throws SQLException {
        try (PreparedStatement statement = this.connection.prepareStatement("UPDATE context_artifact SET last_access=? WHERE id=?")) {
            statement.setLong(1, this.clock.instant().toEpochMilli());
            statement.setString(2, artifactId);
            statement.executeUpdate();
        }
    }

    private static ContextArtifact artifact(ResultSet result) throws SQLException {
        return new ContextArtifact(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5),
                result.getString(6), ContextRepresentationLevel.valueOf(result.getString(7)),
                Instant.ofEpochMilli(result.getLong(8)), Instant.ofEpochMilli(result.getLong(9)));
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalizeScope(String scopeId) {
        return scopeId == null || scopeId.isBlank() ? "local" : scopeId.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("context store is closed");
    }

    private static IllegalStateException failure(String action, Exception exception) {
        return new IllegalStateException("Unable to " + action + ": " + exception.getMessage(), exception);
    }
}
