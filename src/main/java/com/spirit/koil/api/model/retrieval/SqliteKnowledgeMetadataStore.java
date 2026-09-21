package com.spirit.koil.api.model.retrieval;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SQLite implementation; metadata and compatible raw vectors remain authoritative over any index file. */
public final class SqliteKnowledgeMetadataStore implements KnowledgeMetadataStore {
    private final Connection connection;
    private final EmbeddingIdentity identity;
    private final String identityKey;
    private boolean closed;

    public SqliteKnowledgeMetadataStore(Path database, EmbeddingIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.identityKey = identity.cacheKey();
        try {
            Path parent = Objects.requireNonNull(database, "database").toAbsolutePath().getParent();
            Files.createDirectories(parent);
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            try (Statement statement = this.connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            createSchema();
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Unable to open Koil knowledge metadata store", exception);
        }
    }

    @Override
    public synchronized long allocateId() {
        ensureOpen();
        try (PreparedStatement insert = this.connection.prepareStatement(
                "INSERT INTO knowledge_id_allocator DEFAULT VALUES", Statement.RETURN_GENERATED_KEYS)) {
            while (true) {
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return a stable knowledge ID");
                    long id = keys.getLong(1);
                    if (find(id).isEmpty()) return id;
                }
            }
        } catch (SQLException exception) {
            throw failure("allocate a knowledge ID", exception);
        }
    }

    @Override
    public synchronized long upsert(KnowledgeEntry entry, float[] normalizedEmbedding) {
        ensureOpen();
        Objects.requireNonNull(entry, "entry");
        byte[] vector = normalizedEmbedding == null ? null : encodeEmbedding(normalizedEmbedding);
        String contentHash = contentHash(entry);
        transaction(() -> {
            try (PreparedStatement statement = this.connection.prepareStatement("""
                    INSERT INTO knowledge_entry (id, type, scope, text, source, session_id, timestamp_millis, importance, confidence, trust, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET type=excluded.type, scope=excluded.scope, text=excluded.text, source=excluded.source,
                    session_id=excluded.session_id, timestamp_millis=excluded.timestamp_millis, importance=excluded.importance,
                    confidence=excluded.confidence, trust=excluded.trust, content_hash=excluded.content_hash
                    """)) {
                statement.setLong(1, entry.id());
                statement.setString(2, entry.type().name());
                statement.setString(3, entry.scope());
                statement.setString(4, entry.text());
                statement.setString(5, entry.source());
                statement.setString(6, entry.sessionId());
                statement.setLong(7, entry.timestampMillis());
                statement.setDouble(8, entry.importance());
                statement.setDouble(9, entry.confidence());
                statement.setString(10, entry.trust().name());
                statement.setString(11, contentHash);
                statement.executeUpdate();
            }
            try (PreparedStatement delete = this.connection.prepareStatement("DELETE FROM knowledge_metadata WHERE entry_id = ?")) {
                delete.setLong(1, entry.id());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = this.connection.prepareStatement(
                    "INSERT INTO knowledge_metadata (entry_id, key, value) VALUES (?, ?, ?)") ) {
                for (Map.Entry<String, String> metadata : entry.metadata().entrySet()) {
                    insert.setLong(1, entry.id());
                    insert.setString(2, metadata.getKey());
                    insert.setString(3, metadata.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            if (vector == null) {
                try (PreparedStatement delete = this.connection.prepareStatement(
                        "DELETE FROM knowledge_embedding WHERE entry_id=? AND identity_key=?")) {
                    delete.setLong(1, entry.id());
                    delete.setString(2, this.identityKey);
                    delete.executeUpdate();
                }
            } else try (PreparedStatement statement = this.connection.prepareStatement("""
                    INSERT INTO knowledge_embedding (entry_id, identity_key, dimensions, normalized, embedding, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(entry_id, identity_key) DO UPDATE SET dimensions=excluded.dimensions, normalized=excluded.normalized,
                    embedding=excluded.embedding, content_hash=excluded.content_hash
                    """)) {
                statement.setLong(1, entry.id());
                statement.setString(2, this.identityKey);
                statement.setInt(3, this.identity.dimensions());
                statement.setInt(4, this.identity.normalized() ? 1 : 0);
                statement.setBytes(5, vector);
                statement.setString(6, contentHash);
                statement.executeUpdate();
            }
        });
        return entry.id();
    }


    @Override
    public synchronized long upsertEmbedding(KnowledgeEntry entry, float[] normalizedEmbedding) {
        ensureOpen();
        Objects.requireNonNull(entry, "entry");
        if (normalizedEmbedding == null) return upsert(entry, null);
        byte[] vector = encodeEmbedding(normalizedEmbedding);
        String contentHash = contentHash(entry);
        transaction(() -> {
            try (PreparedStatement statement = this.connection.prepareStatement("""
                    INSERT INTO knowledge_embedding (entry_id, identity_key, dimensions, normalized, embedding, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(entry_id, identity_key) DO UPDATE SET dimensions=excluded.dimensions, normalized=excluded.normalized,
                    embedding=excluded.embedding, content_hash=excluded.content_hash
                    """)) {
                statement.setLong(1, entry.id());
                statement.setString(2, this.identityKey);
                statement.setInt(3, this.identity.dimensions());
                statement.setInt(4, this.identity.normalized() ? 1 : 0);
                statement.setBytes(5, vector);
                statement.setString(6, contentHash);
                statement.executeUpdate();
            }
        });
        return entry.id();
    }

    @Override
    public synchronized Optional<KnowledgeEntry> find(long id) {
        ensureOpen();
        if (id <= 0L) return Optional.empty();
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT id, type, scope, text, source, session_id, timestamp_millis, importance, confidence, trust
                FROM knowledge_entry WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(entry(result));
            }
        } catch (SQLException exception) {
            throw failure("read knowledge entry", exception);
        }
    }

    @Override
    public synchronized List<KnowledgeEntry> activeEntries() {
        ensureOpen();
        List<Long> ids = new ArrayList<>();
        try (Statement statement = this.connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM knowledge_entry ORDER BY id")) {
            while (result.next()) ids.add(result.getLong(1));
        } catch (SQLException exception) {
            throw failure("list knowledge entries", exception);
        }
        List<KnowledgeEntry> entries = new ArrayList<>(ids.size());
        for (long id : ids) find(id).ifPresent(entries::add);
        return List.copyOf(entries);
    }

    @Override
    public synchronized List<KnowledgeEntry> activeEntriesForSource(String sourceId) {
        ensureOpen();
        String normalized = sourceId == null ? "" : sourceId.strip();
        if (normalized.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT e.id FROM knowledge_entry e
                WHERE EXISTS (
                    SELECT 1 FROM knowledge_metadata m
                    WHERE m.entry_id=e.id AND m.key='sourceId' AND m.value=?
                ) ORDER BY e.id
                """)) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        } catch (SQLException exception) {
            throw failure("list source knowledge entries", exception);
        }
        List<KnowledgeEntry> entries = new ArrayList<>(ids.size());
        for (long id : ids) find(id).ifPresent(entries::add);
        return List.copyOf(entries);
    }

    @Override
    public synchronized List<KnowledgeEntry> activeEntriesMissingEmbedding() {
        ensureOpen();
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT e.id FROM knowledge_entry e
                LEFT JOIN knowledge_embedding v ON v.entry_id=e.id AND v.identity_key=?
                WHERE v.entry_id IS NULL ORDER BY e.id
                """)) {
            statement.setString(1, this.identityKey);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        } catch (SQLException exception) {
            throw failure("list knowledge entries missing embeddings", exception);
        }
        List<KnowledgeEntry> entries = new ArrayList<>(ids.size());
        for (long id : ids) find(id).ifPresent(entries::add);
        return List.copyOf(entries);
    }

    @Override
    public synchronized List<Long> filterIds(KnowledgeFilter filter) {
        ensureOpen();
        KnowledgeFilter effective = filter == null ? KnowledgeFilter.any() : filter;
        StringBuilder query = new StringBuilder("SELECT e.id FROM knowledge_entry e WHERE 1=1");
        List<Object> values = new ArrayList<>();
        appendIn(query, "e.type", effective.types().stream().map(Enum::name).toList(), values);
        appendIn(query, "e.scope", effective.scopes(), values);
        appendIn(query, "e.session_id", effective.sessions(), values);
        if (effective.notBeforeMillis() > 0L) {
            query.append(" AND e.timestamp_millis >= ?");
            values.add(effective.notBeforeMillis());
        }
        for (Map.Entry<String, String> metadata : effective.metadataEquals().entrySet()) {
            query.append(" AND EXISTS (SELECT 1 FROM knowledge_metadata m WHERE m.entry_id=e.id AND m.key=? AND m.value=?)");
            values.add(metadata.getKey());
            values.add(metadata.getValue());
        }
        query.append(" ORDER BY e.id");
        try (PreparedStatement statement = this.connection.prepareStatement(query.toString())) {
            for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
            return List.copyOf(ids);
        } catch (SQLException exception) {
            throw failure("filter knowledge entries", exception);
        }
    }

    @Override
    public synchronized boolean markDeleted(long id) {
        ensureOpen();
        if (id <= 0L) return false;
        final boolean[] deleted = {false};
        transaction(() -> {
            try (PreparedStatement statement = this.connection.prepareStatement("DELETE FROM knowledge_entry WHERE id = ?")) {
                statement.setLong(1, id);
                deleted[0] = statement.executeUpdate() == 1;
            }
        });
        return deleted[0];
    }

    @Override
    public synchronized List<StoredEmbedding> activeEmbeddings() {
        ensureOpen();
        List<StoredEmbedding> embeddings = new ArrayList<>();
        try (PreparedStatement statement = this.connection.prepareStatement("""
                SELECT entry_id, embedding, content_hash FROM knowledge_embedding
                WHERE identity_key = ? ORDER BY entry_id
                """)) {
            statement.setString(1, this.identityKey);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    Optional<KnowledgeEntry> entry = find(id);
                    if (entry.isPresent()) embeddings.add(new StoredEmbedding(entry.get(), decodeEmbedding(result.getBytes(2)), result.getString(3)));
                }
            }
            return List.copyOf(embeddings);
        } catch (SQLException exception) {
            throw failure("read knowledge embeddings", exception);
        }
    }

    @Override
    public synchronized IntegrityReport validate() {
        ensureOpen();
        try {
            long entries = count("SELECT COUNT(*) FROM knowledge_entry");
            long embeddings = count("SELECT COUNT(*) FROM knowledge_embedding WHERE identity_key = ?", this.identityKey);
            long missing = count("""
                    SELECT COUNT(*) FROM knowledge_entry e LEFT JOIN knowledge_embedding v
                    ON v.entry_id=e.id AND v.identity_key=? WHERE v.entry_id IS NULL
                    """, this.identityKey);
            long orphaned = count("""
                    SELECT COUNT(*) FROM knowledge_embedding v LEFT JOIN knowledge_entry e ON e.id=v.entry_id
                    WHERE e.id IS NULL
                    """);
            long incompatible = count("""
                    SELECT COUNT(*) FROM knowledge_embedding WHERE identity_key=? AND (dimensions<>? OR normalized<>?)
                    """, this.identityKey, this.identity.dimensions(), this.identity.normalized() ? 1 : 0);
            return new IntegrityReport(orphaned == 0L && incompatible == 0L,
                    entries, embeddings, missing, orphaned, incompatible);
        } catch (SQLException exception) {
            throw failure("validate knowledge metadata", exception);
        }
    }

    @Override
    public EmbeddingIdentity embeddingIdentity() {
        return this.identity;
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        try {
            this.connection.close();
        } catch (SQLException exception) {
            throw failure("close knowledge metadata store", exception);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS knowledge_id_allocator (id INTEGER PRIMARY KEY AUTOINCREMENT)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_entry (
                    id INTEGER PRIMARY KEY, type TEXT NOT NULL, scope TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL,
                    session_id TEXT NOT NULL, timestamp_millis INTEGER NOT NULL, importance REAL NOT NULL, confidence REAL NOT NULL,
                    trust TEXT NOT NULL, content_hash TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_metadata (
                    entry_id INTEGER NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                    PRIMARY KEY (entry_id, key), FOREIGN KEY(entry_id) REFERENCES knowledge_entry(id) ON DELETE CASCADE)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_embedding (
                    entry_id INTEGER NOT NULL, identity_key TEXT NOT NULL, dimensions INTEGER NOT NULL, normalized INTEGER NOT NULL,
                    embedding BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY (entry_id, identity_key),
                    FOREIGN KEY(entry_id) REFERENCES knowledge_entry(id) ON DELETE CASCADE)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS knowledge_entry_filter ON knowledge_entry(type, scope, session_id, timestamp_millis)");
            statement.execute("CREATE INDEX IF NOT EXISTS knowledge_metadata_filter ON knowledge_metadata(key, value, entry_id)");
        }
    }

    private KnowledgeEntry entry(ResultSet result) throws SQLException {
        long id = result.getLong(1);
        return new KnowledgeEntry(id, KnowledgeType.valueOf(result.getString(2)), result.getString(3), result.getString(4),
                result.getString(5), result.getString(6), result.getLong(7), result.getDouble(8), result.getDouble(9),
                KnowledgeTrust.valueOf(result.getString(10)), metadata(id));
    }

    private Map<String, String> metadata(long id) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (PreparedStatement statement = this.connection.prepareStatement(
                "SELECT key, value FROM knowledge_metadata WHERE entry_id=? ORDER BY key")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.put(result.getString(1), result.getString(2));
            }
        }
        return Map.copyOf(values);
    }

    private byte[] encodeEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != this.identity.dimensions()) {
            throw new IllegalArgumentException("embedding dimensions must match " + this.identity.dimensions());
        }
        double normSquared = 0.0D;
        ByteBuffer bytes = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding values must be finite");
            normSquared += value * value;
            bytes.putFloat(value);
        }
        if (this.identity.normalized() && Math.abs(Math.sqrt(normSquared) - 1.0D) > 0.001D) {
            throw new IllegalArgumentException("normalized embedding must have unit length");
        }
        return bytes.array();
    }

    private float[] decodeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length != this.identity.dimensions() * Float.BYTES) {
            throw new IllegalStateException("Stored embedding has incompatible dimensions");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] embedding = new float[this.identity.dimensions()];
        for (int index = 0; index < embedding.length; index++) embedding[index] = buffer.getFloat();
        return embedding;
    }

    private String contentHash(KnowledgeEntry entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(entry.text().strip().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(this.identityKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long count(String query, Object... values) throws SQLException {
        try (PreparedStatement statement = this.connection.prepareStatement(query)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private void transaction(SqlWork work) {
        try {
            this.connection.setAutoCommit(false);
            work.run();
            this.connection.commit();
        } catch (SQLException exception) {
            try {
                this.connection.rollback();
            } catch (SQLException rollback) {
                exception.addSuppressed(rollback);
            }
            throw failure("write knowledge metadata", exception);
        } finally {
            try {
                this.connection.setAutoCommit(true);
            } catch (SQLException exception) {
                throw failure("restore knowledge metadata transaction mode", exception);
            }
        }
    }

    private static void appendIn(StringBuilder query, String column, java.util.Collection<?> values, List<Object> parameters) {
        if (values.isEmpty()) return;
        query.append(" AND ").append(column).append(" IN (");
        query.append("?,".repeat(values.size()));
        query.setLength(query.length() - 1);
        query.append(')');
        parameters.addAll(values);
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("knowledge metadata store is closed");
    }

    private static IllegalStateException failure(String action, SQLException exception) {
        return new IllegalStateException("Unable to " + action + ": " + exception.getMessage(), exception);
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
