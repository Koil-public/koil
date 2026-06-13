package com.spirit.client.gui.main.elements;

import net.minecraft.util.Identifier;

import java.util.Map;

public class MenuBookEntry {
    private final Identifier icon;
    private final String name;
    private final String miniCredits;
    private final Map<String, String> pages;
    private final String credits;
    private final String miniDescription;
    private int currentPage;

    public MenuBookEntry(Identifier icon, String name, String credits, String miniDescription, String miniCredits, String filePath) {
        this.icon = icon;
        this.name = name;
        this.credits = credits;
        this.miniDescription = miniDescription;
        this.miniCredits = miniCredits;
        this.pages = BookLoader.loadBookPages(filePath);
        this.currentPage = 1;
    }

    public Identifier iconTexture() { return icon; }
    public String name() { return name; }
    public String miniDescription() { return miniDescription; }
    public String miniCredits() { return miniCredits; }
    public String credits() { return credits; }
    public String description() { return pages.getOrDefault("page" + currentPage, "Page not found"); }
    public int currentPage() { return currentPage; }
    public int pageCount() { return Math.max(1, pages.size()); }
    public void nextPage() { currentPage = Math.min(currentPage + 1, pages.size()); }
    public void prevPage() { currentPage = Math.max(currentPage - 1, 1); }
}
