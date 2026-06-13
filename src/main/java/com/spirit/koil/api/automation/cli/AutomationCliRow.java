package com.spirit.koil.api.automation.cli;

public record AutomationCliRow(
        String rowId,
        String sectionId,
        String rowType,
        int indentationDepth,
        String statusMarker,
        String label,
        String value,
        boolean visible,
        boolean dirty,
        String search,
        String requires,
        String received,
        String output,
        String failure,
        String recovery,
        String source
) {
    public AutomationCliRow(String rowId, String sectionId, String rowType, int indentationDepth, String statusMarker, String label, String value, boolean visible, boolean dirty) {
        this(rowId, sectionId, rowType, indentationDepth, statusMarker, label, value, visible, dirty, "", "", "", "", "", "", "");
    }

    public AutomationCliRow withVisibility(boolean visible) {
        return new AutomationCliRow(rowId, sectionId, rowType, indentationDepth, statusMarker, label, value, visible, true, search, requires, received, output, failure, recovery, source);
    }
}
