package com.spirit.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorToolTipIdea;
import static com.spirit.koil.api.design.uiColorVal.uiColorToolTipLabel;
import static com.spirit.koil.api.design.uiColorVal.uiColorToolTipPrimary;
import static com.spirit.koil.api.design.uiColorVal.uiColorToolTipSecondary;
import static com.spirit.koil.api.design.uiColorVal.uiColorToolTipWarning;

@Environment(EnvType.CLIENT)
public final class InfoPopup {
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int GAP = 4;
    private static final int WARNING_WIDTH = 360;

    private final List<String> lines = new ArrayList<>();
    private Style style = Style.DEFAULT;
    private boolean open;
    private int x;
    private int y;
    private int width;
    private int height;

    public void openAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight, List<String> infoLines) {
        openAtPointer(mouseX, mouseY, screenWidth, screenHeight, infoLines, Style.DEFAULT);
    }

    public void openWarningAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight, List<String> infoLines) {
        openAtPointer(mouseX, mouseY, screenWidth, screenHeight, infoLines, Style.WARNING);
    }

    private void openAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight, List<String> infoLines, Style style) {
        lines.clear();
        lines.addAll(infoLines);
        this.style = style;
        if (lines.isEmpty()) {
            close();
            return;
        }
        measure();
        int pointerX = (int) Math.round(mouseX);
        int pointerY = (int) Math.round(mouseY);
        if (style == Style.WARNING) {
            x = pointerX;
            y = pointerY;
            open = true;
            return;
        }
        x = clampHorizontal(pointerX + GAP, screenWidth);
        if (x + width > screenWidth - 8) {
            x = clampHorizontal(pointerX - width - GAP, screenWidth);
        }
        y = clampVertical(pointerY - 2, screenHeight);
        open = true;
    }

    public void openNearAnchor(int anchorX, int anchorY, int anchorWidth, int screenWidth, int screenHeight, List<String> infoLines) {
        openNearAnchor(anchorX, anchorY, anchorWidth, screenWidth, screenHeight, infoLines, Style.DEFAULT);
    }

    public void openWarningNearAnchor(int anchorX, int anchorY, int anchorWidth, int screenWidth, int screenHeight, List<String> infoLines) {
        openNearAnchor(anchorX, anchorY, anchorWidth, screenWidth, screenHeight, infoLines, Style.WARNING);
    }

    private void openNearAnchor(int anchorX, int anchorY, int anchorWidth, int screenWidth, int screenHeight, List<String> infoLines, Style style) {
        lines.clear();
        lines.addAll(infoLines);
        this.style = style;
        if (lines.isEmpty()) {
            close();
            return;
        }
        measure();
        if (style == Style.WARNING) {
            x = Math.max(8, Math.min((screenWidth - width) / 2, screenWidth - width - 8));
            y = clampVertical(anchorY + anchorWidth + 8, screenHeight);
        } else {
            int desiredX = anchorX + anchorWidth + GAP;
            x = clampHorizontal(desiredX, screenWidth);
            if (x + width > screenWidth - 8) {
                x = clampHorizontal(anchorX - width - GAP, screenWidth);
            }
            y = clampVertical(anchorY - 2, screenHeight);
        }
        open = true;
    }

    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public void render(DrawContext context) {
        if (!open || lines.isEmpty()) {
            return;
        }
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 8000.0F);
        if (style == Style.WARNING) {
            context.drawTooltip(renderer, buildWarningTooltipLines(renderer), Optional.empty(), x, y);
            context.getMatrices().pop();
            return;
        }
        context.fill(x + 1, y + 2, x + width + 3, y + height + 3, 0x6A000000);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 242));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < lines.size(); i++) {
            int rowY = y + PADDING_Y + i * LINE_HEIGHT;
            int color = i == 0
                    ? new Color(uiColorContentBaseTitleText, true).getRGB()
                    : new Color(uiColorHeaderSubTitleText, true).getRGB();
            context.drawText(renderer, lines.get(i), x + PADDING_X, rowY, color, false);
        }
        context.getMatrices().pop();
    }

    private void measure() {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        if (style == Style.WARNING) {
            width = WARNING_WIDTH;
            int bodyLines = 1;
            int bodyWidth = width - 34;
            if (lines.size() > 1) {
                bodyLines += wrapPlain(renderer, lines.get(1), bodyWidth).size();
            }
            if (lines.size() > 2) {
                bodyLines += wrapPlain(renderer, lines.get(2), bodyWidth).size();
            }
            height = (PADDING_Y * 2) + (bodyLines * LINE_HEIGHT) + 6;
            return;
        }
        int computedWidth = 0;
        for (String line : lines) {
            computedWidth = Math.max(computedWidth, renderer.getWidth(line));
        }
        width = Math.max(120, computedWidth + (PADDING_X * 2));
        height = (PADDING_Y * 2) + (lines.size() * LINE_HEIGHT);
    }

    private int clampHorizontal(int desiredX, int screenWidth) {
        return Math.max(8, Math.min(desiredX, screenWidth - width - 8));
    }

    private int clampVertical(int desiredY, int screenHeight) {
        return Math.max(8, Math.min(desiredY, screenHeight - height - 8));
    }

    private List<Text> buildWarningTooltipLines(TextRenderer renderer) {
        List<Text> tooltipLines = new ArrayList<>();
        int warningColor = new Color(uiColorToolTipWarning, true).getRGB();
        int labelColor = new Color(uiColorToolTipLabel, true).getRGB();
        int primaryColor = new Color(uiColorToolTipPrimary, true).getRGB();
        int secondaryColor = new Color(uiColorToolTipSecondary, true).getRGB();
        int ideaColor = new Color(uiColorToolTipIdea, true).getRGB();

        String title = lines.isEmpty() ? "Machine-generated formatting" : lines.get(0);
        tooltipLines.add(
                Text.literal("Warning").setStyle(net.minecraft.text.Style.EMPTY.withColor(warningColor).withBold(true))
                        .append(Text.literal("  ").setStyle(net.minecraft.text.Style.EMPTY.withColor(labelColor)))
                        .append(Text.literal(title).setStyle(net.minecraft.text.Style.EMPTY.withColor(labelColor)))
        );
        if (lines.size() > 1) {
            addWrappedTooltipLine(tooltipLines, renderer, "Generated: ", lines.get(1), labelColor, primaryColor);
        }
        if (lines.size() > 2) {
            addWrappedTooltipLine(tooltipLines, renderer, "Check: ", lines.get(2), labelColor, secondaryColor);
        }
        tooltipLines.add(
                Text.literal("Use: ").setStyle(net.minecraft.text.Style.EMPTY.withColor(ideaColor))
                        .append(Text.literal("Verify important content before acting on it.").setStyle(net.minecraft.text.Style.EMPTY.withColor(secondaryColor)))
        );
        return tooltipLines;
    }

    private void addWrappedTooltipLine(List<Text> tooltipLines, TextRenderer renderer, String label, String value, int labelColor, int valueColor) {
        int labelWidth = renderer.getWidth(label);
        List<String> wrapped = wrapPlain(renderer, value, WARNING_WIDTH - 38 - labelWidth);
        for (int i = 0; i < wrapped.size(); i++) {
            String prefix = i == 0 ? label : "  ";
            tooltipLines.add(
                    Text.literal(prefix).setStyle(net.minecraft.text.Style.EMPTY.withColor(labelColor))
                            .append(Text.literal(wrapped.get(i)).setStyle(net.minecraft.text.Style.EMPTY.withColor(valueColor)))
            );
        }
    }

    private List<String> wrapPlain(TextRenderer renderer, String value, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = row.isEmpty() ? word : row + " " + word;
            if (!row.isEmpty() && renderer.getWidth(candidate) > maxWidth) {
                wrapped.add(row.toString());
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(candidate);
            }
        }
        if (!row.isEmpty()) {
            wrapped.add(row.toString());
        }
        if (wrapped.isEmpty()) {
            wrapped.add("");
        }
        return wrapped;
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    private enum Style {
        DEFAULT,
        WARNING
    }
}
