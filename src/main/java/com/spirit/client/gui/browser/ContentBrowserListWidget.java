package com.spirit.client.gui.browser;

import com.spirit.client.gui.UiSoundHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeader;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;

public class ContentBrowserListWidget<T> extends AlwaysSelectedEntryListWidget<ContentBrowserListWidget<T>.BrowserEntry> {
	private final BrowserEntryPresenter<T> presenter;
	private final Identifier fallbackIcon;

	public ContentBrowserListWidget(
			MinecraftClient client,
			int width,
			int top,
			int bottom,
			int itemHeight,
			Identifier fallbackIcon,
			BrowserEntryPresenter<T> presenter
	) {
		super(client, width, 150, top, bottom, itemHeight);
		this.fallbackIcon = fallbackIcon;
		this.presenter = presenter;
	}

	@Override
	public int getRowWidth() {
		return this.width - 4;
	}

	@Override
	public int getRowLeft() {
		return this.left + 2;
	}

	@Override
	protected int getScrollbarPositionX() {
		return this.right - 6;
	}

	@Override
	protected void renderBackground(DrawContext context) {
	}

	public void updateEntries(List<T> entries) {
		this.clearEntries();
		if (entries == null || entries.isEmpty()) {
			return;
		}
		if (this.presenter.isGroupingEnabled()) {
			Map<String, List<T>> grouped = new LinkedHashMap<>();
			for (T info : entries) {
				grouped.computeIfAbsent(this.presenter.getGroupLabel(info), ignored -> new ArrayList<>()).add(info);
			}
			for (Map.Entry<String, List<T>> group : grouped.entrySet()) {
				this.addEntry(new GroupHeaderEntry(group.getKey(), group.getValue().size()));
				for (T info : group.getValue()) {
					this.addEntry(new ItemEntry(info));
				}
			}
			return;
		}
		for (T info : entries) {
			this.addEntry(new ItemEntry(info));
		}
	}

	public void syncSelection() {
		for (int i = 0; i < this.getEntryCount(); i++) {
			BrowserEntry entry = this.getEntry(i);
			if (entry instanceof ItemEntry itemEntry && this.presenter.isSelected(itemEntry.info)) {
				this.setSelected(itemEntry);
				return;
			}
		}
		this.setSelected(null);
	}

	public interface BrowserEntryPresenter<T> {
		boolean isGroupingEnabled();

		String getGroupLabel(T info);

		boolean isSelected(T info);

		void onSelected(T info);

		Identifier resolveIcon(T info);

		String title(T info);

		String metadata(T info);

		Text narration(T info);
	}

	public abstract class BrowserEntry extends Entry<BrowserEntry> {
	}

	public class GroupHeaderEntry extends BrowserEntry {
		private final String label;
		private final int count;

		public GroupHeaderEntry(String label, int count) {
			this.label = label;
			this.count = count;
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			int background = new Color(uiColorHeader, true).getRGB();
			int border = new Color(uiColorBackgroundBorder, true).getRGB();
			int title = new Color(uiColorContentBaseTitleText, true).getRGB();
			int detail = new Color(uiColorHeaderSubTitleText, true).getRGB();
			context.fill(x + 2, y + 2, x + entryWidth - 10, y + 20, background);
			context.drawBorder(x + 2, y + 2, entryWidth - 12, 18, border);
			context.drawText(ContentBrowserListWidget.this.client.textRenderer, this.label, x + 10, y + 8, title, false);
			String countLabel = this.count + (this.count == 1 ? " item" : " items");
			context.drawText(ContentBrowserListWidget.this.client.textRenderer, countLabel, x + entryWidth - 24 - ContentBrowserListWidget.this.client.textRenderer.getWidth(countLabel), y + 8, detail, false);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button == 0) {
				UiSoundHelper.playButtonClick();
			}
			return false;
		}

		@Override
		public Text getNarration() {
			return Text.of(this.label);
		}
	}

	public class ItemEntry extends BrowserEntry {
		private final T info;

		public ItemEntry(T info) {
			this.info = info;
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			Identifier iconTexture = ContentBrowserListWidget.this.presenter.resolveIcon(this.info);
			boolean selected = ContentBrowserListWidget.this.getSelectedOrNull() == this || ContentBrowserListWidget.this.presenter.isSelected(this.info);
			ContentBrowserListRowRenderer.renderItem(
					context,
					ContentBrowserListWidget.this.client.textRenderer,
					iconTexture,
					ContentBrowserListWidget.this.fallbackIcon,
					x,
					y,
					entryWidth,
					entryHeight,
					selected,
					ContentBrowserListWidget.this.presenter.title(this.info),
					ContentBrowserListWidget.this.presenter.metadata(this.info));
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) {
				return false;
			}
			UiSoundHelper.playButtonClick();
			ContentBrowserListWidget.this.presenter.onSelected(this.info);
			ContentBrowserListWidget.this.setSelected(this);
			return true;
		}

		@Override
		public Text getNarration() {
			return ContentBrowserListWidget.this.presenter.narration(this.info);
		}
	}
}
