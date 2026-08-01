package com.spirit.client.gui.content.browser;

import java.util.List;

public record ContentPreviewSection(String title, List<ContentPreviewSectionRow> rows, List<String> paragraphs) {
}
