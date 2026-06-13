package com.spirit.client.gui.ide;

import java.io.File;

public class FileItem {
    private final String name;
    private final FileType type;
    private final File file;
    private boolean expanded;
    private final FileItem parent;
    private final int depth;

    public FileItem(String name, FileType type, File file) {
        this(name, type, file, null, 0);
    }

    public FileItem(String name, FileType type, File file, FileItem parent, int depth) {
        this.name = name;
        this.type = type;
        this.file = file;
        this.expanded = false;
        this.parent = parent;
        this.depth = depth;
    }

    public String getName() {
        return name;
    }

    public FileType getType() {
        return type;
    }

    public File getFile() {
        return file;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public FileItem getParent() {
        return parent;
    }

    public int getDepth() {
        return depth;
    }
}
