package com.spirit.client.gui.ide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EditorDocument {
    private final List<String> lines = new ArrayList<>();
    private int cursorLine;
    private int cursorColumn;
    private int preferredColumn;
    private int anchorLine;
    private int anchorColumn;
    private boolean selecting;

    public EditorDocument(List<String> initialLines) {
        if (initialLines == null || initialLines.isEmpty()) {
            this.lines.add("");
        } else {
            this.lines.addAll(initialLines);
        }
        this.cursorLine = 0;
        this.cursorColumn = 0;
        this.preferredColumn = 0;
        this.anchorLine = 0;
        this.anchorColumn = 0;
        this.selecting = false;
    }

    public List<String> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public String getLine(int index) {
        return lines.get(index);
    }

    public int getLineCount() {
        return lines.size();
    }

    public int getCursorLine() {
        return cursorLine;
    }

    public int getCursorColumn() {
        return cursorColumn;
    }

    public boolean hasSelection() {
        return selecting && (anchorLine != cursorLine || anchorColumn != cursorColumn);
    }

    public void clearSelection() {
        selecting = false;
        anchorLine = cursorLine;
        anchorColumn = cursorColumn;
    }

    public void setCursor(int line, int column, boolean extendSelection) {
        int clampedLine = clamp(line, 0, lines.size() - 1);
        int clampedColumn = clamp(column, 0, lines.get(clampedLine).length());

        if (extendSelection) {
            if (!selecting) {
                anchorLine = cursorLine;
                anchorColumn = cursorColumn;
                selecting = true;
            }
        } else {
            selecting = false;
            anchorLine = clampedLine;
            anchorColumn = clampedColumn;
        }

        cursorLine = clampedLine;
        cursorColumn = clampedColumn;
        preferredColumn = cursorColumn;
    }

    public void startSelection(int line, int column) {
        int clampedLine = clamp(line, 0, lines.size() - 1);
        int clampedColumn = clamp(column, 0, lines.get(clampedLine).length());
        cursorLine = clampedLine;
        cursorColumn = clampedColumn;
        preferredColumn = cursorColumn;
        anchorLine = clampedLine;
        anchorColumn = clampedColumn;
        selecting = true;
    }

    public void updateSelection(int line, int column) {
        int clampedLine = clamp(line, 0, lines.size() - 1);
        int clampedColumn = clamp(column, 0, lines.get(clampedLine).length());
        cursorLine = clampedLine;
        cursorColumn = clampedColumn;
        preferredColumn = cursorColumn;
        selecting = true;
    }

    public void selectRange(int startLine, int startColumn, int endLine, int endColumn) {
        anchorLine = clamp(startLine, 0, lines.size() - 1);
        anchorColumn = clamp(startColumn, 0, lines.get(anchorLine).length());
        cursorLine = clamp(endLine, 0, lines.size() - 1);
        cursorColumn = clamp(endColumn, 0, lines.get(cursorLine).length());
        preferredColumn = cursorColumn;
        selecting = true;
    }

    public void selectAll() {
        anchorLine = 0;
        anchorColumn = 0;
        cursorLine = lines.size() - 1;
        cursorColumn = lines.get(cursorLine).length();
        preferredColumn = cursorColumn;
        selecting = true;
    }

    public void moveLeft(boolean extendSelection) {
        if (cursorColumn > 0) {
            setCursor(cursorLine, cursorColumn - 1, extendSelection);
        } else if (cursorLine > 0) {
            setCursor(cursorLine - 1, lines.get(cursorLine - 1).length(), extendSelection);
        }
    }

    public void moveRight(boolean extendSelection) {
        if (cursorColumn < lines.get(cursorLine).length()) {
            setCursor(cursorLine, cursorColumn + 1, extendSelection);
        } else if (cursorLine < lines.size() - 1) {
            setCursor(cursorLine + 1, 0, extendSelection);
        }
    }

    public void moveUp(boolean extendSelection) {
        if (cursorLine > 0) {
            int nextLine = cursorLine - 1;
            setCursor(nextLine, Math.min(preferredColumn, lines.get(nextLine).length()), extendSelection);
        }
    }

    public void moveDown(boolean extendSelection) {
        if (cursorLine < lines.size() - 1) {
            int nextLine = cursorLine + 1;
            setCursor(nextLine, Math.min(preferredColumn, lines.get(nextLine).length()), extendSelection);
        }
    }

    public void moveHome(boolean extendSelection) {
        setCursor(cursorLine, 0, extendSelection);
    }

    public void moveEnd(boolean extendSelection) {
        setCursor(cursorLine, lines.get(cursorLine).length(), extendSelection);
    }

    public void moveToDocumentStart(boolean extendSelection) {
        setCursor(0, 0, extendSelection);
    }

    public void moveToDocumentEnd(boolean extendSelection) {
        int lastLine = lines.size() - 1;
        setCursor(lastLine, lines.get(lastLine).length(), extendSelection);
    }

    public void movePageUp(int lineCount, boolean extendSelection) {
        setCursor(cursorLine - Math.max(1, lineCount), Math.min(preferredColumn, lines.get(clamp(cursorLine - Math.max(1, lineCount), 0, lines.size() - 1)).length()), extendSelection);
    }

    public void movePageDown(int lineCount, boolean extendSelection) {
        setCursor(cursorLine + Math.max(1, lineCount), Math.min(preferredColumn, lines.get(clamp(cursorLine + Math.max(1, lineCount), 0, lines.size() - 1)).length()), extendSelection);
    }

    public boolean insertChar(char character) {
        return insertText(String.valueOf(character));
    }

    public boolean insertText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (hasSelection()) {
            deleteSelection();
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.contains("\n")) {
            String line = lines.get(cursorLine);
            lines.set(cursorLine, line.substring(0, cursorColumn) + normalized + line.substring(cursorColumn));
            cursorColumn += normalized.length();
            preferredColumn = cursorColumn;
            clearSelection();
            return true;
        }

        String[] parts = normalized.split("\n", -1);
        String currentLine = lines.get(cursorLine);
        String before = currentLine.substring(0, cursorColumn);
        String after = currentLine.substring(cursorColumn);

        lines.set(cursorLine, before + parts[0]);
        int insertLine = cursorLine;
        for (int i = 1; i < parts.length; i++) {
            insertLine++;
            String segment = parts[i];
            if (i == parts.length - 1) {
                lines.add(insertLine, segment + after);
            } else {
                lines.add(insertLine, segment);
            }
        }

        cursorLine = insertLine;
        cursorColumn = parts[parts.length - 1].length();
        preferredColumn = cursorColumn;
        clearSelection();
        return true;
    }

    public boolean insertNewLine() {
        return insertText("\n");
    }

    public boolean backspace() {
        if (hasSelection()) {
            return deleteSelection();
        }

        if (cursorColumn > 0) {
            String line = lines.get(cursorLine);
            lines.set(cursorLine, line.substring(0, cursorColumn - 1) + line.substring(cursorColumn));
            cursorColumn--;
            preferredColumn = cursorColumn;
            clearSelection();
            return true;
        }

        if (cursorLine > 0) {
            String previousLine = lines.get(cursorLine - 1);
            String currentLine = lines.remove(cursorLine);
            cursorLine--;
            cursorColumn = previousLine.length();
            lines.set(cursorLine, previousLine + currentLine);
            preferredColumn = cursorColumn;
            clearSelection();
            return true;
        }

        return false;
    }

    public boolean deleteForward() {
        if (hasSelection()) {
            return deleteSelection();
        }

        String line = lines.get(cursorLine);
        if (cursorColumn < line.length()) {
            lines.set(cursorLine, line.substring(0, cursorColumn) + line.substring(cursorColumn + 1));
            clearSelection();
            return true;
        }

        if (cursorLine < lines.size() - 1) {
            String nextLine = lines.remove(cursorLine + 1);
            lines.set(cursorLine, line + nextLine);
            clearSelection();
            return true;
        }

        return false;
    }

    public boolean deleteSelection() {
        if (!hasSelection()) {
            return false;
        }

        SelectionRange range = getSelectionRange();
        if (range == null) {
            return false;
        }

        if (range.start().line() == range.end().line()) {
            String line = lines.get(range.start().line());
            lines.set(range.start().line(), line.substring(0, range.start().column()) + line.substring(range.end().column()));
        } else {
            String startLine = lines.get(range.start().line());
            String endLine = lines.get(range.end().line());
            String merged = startLine.substring(0, range.start().column()) + endLine.substring(range.end().column());
            lines.set(range.start().line(), merged);
            for (int index = range.end().line(); index > range.start().line(); index--) {
                lines.remove(index);
            }
        }

        cursorLine = range.start().line();
        cursorColumn = range.start().column();
        preferredColumn = cursorColumn;
        clearSelection();
        ensureAtLeastOneLine();
        return true;
    }

    public SelectionRange getSelectionRange() {
        if (!hasSelection()) {
            return null;
        }

        CursorPosition anchor = new CursorPosition(anchorLine, anchorColumn);
        CursorPosition cursor = new CursorPosition(cursorLine, cursorColumn);
        if (compare(anchor, cursor) <= 0) {
            return new SelectionRange(anchor, cursor);
        }
        return new SelectionRange(cursor, anchor);
    }

    public String getText() {
        return String.join("\n", lines);
    }

    public String getSelectedText() {
        SelectionRange range = getSelectionRange();
        if (range == null) {
            return "";
        }

        if (range.start().line() == range.end().line()) {
            String line = lines.get(range.start().line());
            return line.substring(range.start().column(), range.end().column());
        }

        StringBuilder builder = new StringBuilder();
        String startLine = lines.get(range.start().line());
        builder.append(startLine.substring(range.start().column()));
        builder.append('\n');

        for (int lineIndex = range.start().line() + 1; lineIndex < range.end().line(); lineIndex++) {
            builder.append(lines.get(lineIndex));
            builder.append('\n');
        }

        String endLine = lines.get(range.end().line());
        builder.append(endLine, 0, range.end().column());
        return builder.toString();
    }

    private void ensureAtLeastOneLine() {
        if (lines.isEmpty()) {
            lines.add("");
            cursorLine = 0;
            cursorColumn = 0;
            preferredColumn = 0;
            clearSelection();
        }
    }

    private static int compare(CursorPosition left, CursorPosition right) {
        if (left.line() != right.line()) {
            return Integer.compare(left.line(), right.line());
        }
        return Integer.compare(left.column(), right.column());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CursorPosition(int line, int column) {}

    public record SelectionRange(CursorPosition start, CursorPosition end) {}
}
