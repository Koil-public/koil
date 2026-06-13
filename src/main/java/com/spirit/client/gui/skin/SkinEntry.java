package com.spirit.client.gui.skin;

public class SkinEntry {
    public String id;
    public String name;
    public String file;
    public String source;
    public String sourceDetail;
    public String textureUrl;
    public String model;
    public long createdAt;
    public long updatedAt;
    public long lastUsedAt;
    public int width;
    public int height;

    public SkinEntry() {
    }

    public boolean isSlim() {
        return "slim".equalsIgnoreCase(this.model);
    }

    public String modelLabel() {
        return this.isSlim() ? "Slim" : "Regular";
    }

    public String safeName() {
        if (this.name == null || this.name.isBlank()) {
            return "Unnamed Skin";
        }
        return this.name;
    }

    public String safeSource() {
        if (this.source == null || this.source.isBlank()) {
            return "local";
        }
        return this.source;
    }
}
