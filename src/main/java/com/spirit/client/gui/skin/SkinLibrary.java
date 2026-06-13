package com.spirit.client.gui.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.spirit.Main.SUBLOGGER;

public final class SkinLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SkinLibrary instance;
    private final Path root;
    private final Path textures;
    private final Path libraryFile;
    private final Map<String, Identifier> textureCache = new HashMap<>();
    private LibraryData data = new LibraryData();

    private SkinLibrary() {
        Path run = MinecraftClient.getInstance().runDirectory.toPath();
        this.root = run.resolve("koil/sys/skins");
        this.textures = this.root.resolve("textures");
        this.libraryFile = this.root.resolve("library.json");
    }

    public static SkinLibrary get() {
        if (instance == null) {
            instance = new SkinLibrary();
        }
        return instance;
    }

    public void load() {
        try {
            Files.createDirectories(this.textures);
            if (Files.exists(this.libraryFile)) {
                String raw = Files.readString(this.libraryFile);
                LibraryData loaded = GSON.fromJson(raw, LibraryData.class);
                if (loaded != null) {
                    this.data = loaded;
                }
            }
            if (this.data.entries == null) {
                this.data.entries = new ArrayList<>();
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Library thread", "Failed to load skin library: " + e.getMessage());
            this.data = new LibraryData();
        }
    }

    public void save() {
        try {
            Files.createDirectories(this.root);
            Files.writeString(this.libraryFile, GSON.toJson(this.data));
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Library thread", "Failed to save skin library: " + e.getMessage());
        }
    }

    public List<SkinEntry> entries() {
        List<SkinEntry> result = new ArrayList<>(this.data.entries);
        result.sort(Comparator.comparingLong((SkinEntry entry) -> entry.lastUsedAt).reversed().thenComparing(entry -> entry.safeName().toLowerCase()));
        return result;
    }

    public SkinEntry get(String id) {
        if (id == null) {
            return null;
        }
        for (SkinEntry entry : this.data.entries) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public String activeId() {
        return this.data.activeId;
    }

    public SkinEntry activeEntry() {
        return this.get(this.data.activeId);
    }

    public void setActive(SkinEntry entry) {
        if (entry == null) {
            this.data.activeId = null;
            SkinRuntime.clearActiveSkin();
            save();
            return;
        }
        long now = System.currentTimeMillis();
        entry.lastUsedAt = now;
        entry.updatedAt = now;
        this.data.activeId = entry.id;
        Identifier texture = texture(entry);
        SkinRuntime.setActiveSkin(entry.id, texture, entry.isSlim());
        save();
    }

    public SkinEntry addFromPath(Path source, String name, boolean slim, String sourceName, String sourceDetail, String textureUrl) throws Exception {
        NativeImage image = SkinTextureTools.readSkin(source);
        try {
            String id = UUID.randomUUID().toString().replace("-", "");
            Path target = this.textures.resolve(id + ".png");
            SkinTextureTools.writeSkin(image, target);
            return addEntry(id, target, name, slim, sourceName, sourceDetail, textureUrl, image.getWidth(), image.getHeight());
        } finally {
            image.close();
        }
    }

    public SkinEntry addFromNativeImage(NativeImage image, String name, boolean slim, String sourceName, String sourceDetail, String textureUrl) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = this.textures.resolve(id + ".png");
        NativeImage copy = SkinTextureTools.copy(image);
        try {
            SkinTextureTools.writeSkin(copy, target);
        } finally {
            copy.close();
        }
        return addEntry(id, target, name, slim, sourceName, sourceDetail, textureUrl, image.getWidth(), image.getHeight());
    }

    public SkinEntry addDownloaded(Path tempFile, String name, boolean slim, String sourceName, String sourceDetail, String textureUrl) throws Exception {
        SkinEntry entry = addFromPath(tempFile, name, slim, sourceName, sourceDetail, textureUrl);
        Files.deleteIfExists(tempFile);
        return entry;
    }

    public void overwriteEntry(SkinEntry entry, NativeImage image, boolean slim) throws Exception {
        if (entry == null || entry.file == null) {
            return;
        }
        Path target = this.root.resolve(entry.file).normalize();
        NativeImage copy = SkinTextureTools.copy(image);
        try {
            SkinTextureTools.writeSkin(copy, target);
        } finally {
            copy.close();
        }
        entry.model = slim ? "slim" : "regular";
        entry.updatedAt = System.currentTimeMillis();
        entry.width = image.getWidth();
        entry.height = image.getHeight();
        this.textureCache.remove(entry.id);
        save();
    }

    public void delete(SkinEntry entry) {
        if (entry == null) {
            return;
        }
        this.data.entries.removeIf(value -> entry.id.equals(value.id));
        this.textureCache.remove(entry.id);
        if (entry.id.equals(this.data.activeId)) {
            this.data.activeId = null;
            SkinRuntime.clearActiveSkin();
        }
        try {
            if (entry.file != null) {
                Files.deleteIfExists(this.root.resolve(entry.file).normalize());
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Library thread", "Failed to delete skin texture: " + e.getMessage());
        }
        save();
    }

    public Identifier texture(SkinEntry entry) {
        if (entry == null) {
            return null;
        }
        Identifier cached = this.textureCache.get(entry.id);
        if (cached != null) {
            return cached;
        }
        try {
            Path path = this.root.resolve(entry.file).normalize();
            NativeImage image = SkinTextureTools.readSkin(path);
            Identifier id = SkinTextureTools.registerTexture("library/" + entry.id, image);
            this.textureCache.put(entry.id, id);
            return id;
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Library thread", "Failed to load library skin texture: " + e.getMessage());
            return new Identifier("minecraft", entry != null && entry.isSlim() ? "textures/entity/alex.png" : "textures/entity/steve.png");
        }
    }

    public NativeImage readEntryImage(SkinEntry entry) throws Exception {
        if (entry == null || entry.file == null) {
            throw new IllegalArgumentException("No skin selected");
        }
        return SkinTextureTools.readSkin(this.root.resolve(entry.file).normalize());
    }

    public Path root() {
        return this.root;
    }

    public int[] editorPalette(int[] defaults) {
        load();
        int[] result = new int[defaults.length];
        System.arraycopy(defaults, 0, result, 0, defaults.length);
        if (this.data.metadata == null) {
            this.data.metadata = new LinkedHashMap<>();
            return result;
        }
        for (int i = 0; i < result.length; i++) {
            String value = this.data.metadata.get("editor.palette." + i);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                result[i] = (int) Long.parseLong(value, 16);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public void saveEditorPalette(int[] palette) {
        if (palette == null) {
            return;
        }
        if (this.data.metadata == null) {
            this.data.metadata = new LinkedHashMap<>();
        }
        for (int i = 0; i < palette.length; i++) {
            this.data.metadata.put("editor.palette." + i, String.format("%08X", palette[i]));
        }
        save();
    }

    public void rename(SkinEntry entry, String name, boolean slim) {
        if (entry == null) {
            return;
        }
        entry.name = name == null || name.isBlank() ? entry.safeName() : name.trim();
        entry.model = slim ? "slim" : "regular";
        entry.updatedAt = System.currentTimeMillis();
        if (entry.id != null && entry.id.equals(this.data.activeId)) {
            SkinRuntime.setActiveSkin(entry.id, texture(entry), entry.isSlim());
        }
        save();
    }

    public boolean containsTextureUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        for (SkinEntry entry : this.data.entries) {
            if (url.equals(entry.textureUrl)) {
                return true;
            }
        }
        return false;
    }

    private SkinEntry addEntry(String id, Path target, String name, boolean slim, String sourceName, String sourceDetail, String textureUrl, int width, int height) throws Exception {
        long now = System.currentTimeMillis();
        SkinEntry entry = new SkinEntry();
        entry.id = id;
        entry.name = name == null || name.isBlank() ? "Skin " + (this.data.entries.size() + 1) : name;
        entry.file = this.root.relativize(target).toString().replace('\\', '/');
        entry.source = sourceName == null || sourceName.isBlank() ? "local" : sourceName;
        entry.sourceDetail = sourceDetail == null ? "" : sourceDetail;
        entry.textureUrl = textureUrl == null ? "" : textureUrl;
        entry.model = slim ? "slim" : "regular";
        entry.createdAt = now;
        entry.updatedAt = now;
        entry.lastUsedAt = now;
        entry.width = width;
        entry.height = height;
        this.data.entries.add(entry);
        save();
        return entry;
    }

    private static class LibraryData {
        String activeId;
        List<SkinEntry> entries = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
    }
}
