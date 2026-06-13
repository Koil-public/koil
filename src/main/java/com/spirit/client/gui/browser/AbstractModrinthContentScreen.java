package com.spirit.client.gui.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.FolderOpenHelper;
import com.spirit.client.gui.MarkdownPreviewRenderer;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.ScreenChromeHost;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.apache.commons.io.FileUtils;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;

public abstract class AbstractModrinthContentScreen extends Screen {
	protected enum ViewMode {
		CATALOG("All"),
		INSTALLED_MATCHES("Installed"),
		NOT_INSTALLED("Not Installed");

		private final String label;

		ViewMode(String label) {
			this.label = label;
		}
	}

	protected enum SortMode {
		RELEVANCE("Relevance"),
		NAME("Name"),
		DOWNLOADS("Downloads"),
		AUTHOR("Author");

		private final String label;

		SortMode(String label) {
			this.label = label;
		}
	}

	protected enum GroupMode {
		NONE("None"),
		AUTHOR("Publisher"),
		SOURCE("Source");

		private final String label;

		GroupMode(String label) {
			this.label = label;
		}
	}

	protected enum FilterMenu {
		SORT,
		GROUP,
		SHOW,
		LIMIT
	}

	protected record InstallChoice(String id, String label, ContentFileInfo file, String versionNumber, ContentVersionInfo version, String provider) {
		protected InstallChoice(String id, String label, ContentFileInfo file, String versionNumber, ContentVersionInfo version) {
			this(id, label, file, versionNumber, version, "Modrinth");
		}
	}

	protected final Screen parent;
	protected TextFieldWidget searchField;
	protected ContentBrowserListWidget<ContentProjectInfo> listWidget;
	protected ContentProjectInfo selectedInfo;
	protected ButtonWidget versionSelectorButton;
	protected ButtonWidget websiteButton;
	protected ButtonWidget reloadButton;
	protected ButtonWidget openFolderButton;
	protected ButtonWidget deleteButton;
	protected ButtonWidget updateButton;
	protected ButtonWidget installOrRemoveButton;
	protected ButtonWidget cancelButton;
	protected final PopupMenu filterPopup = new PopupMenu();
	protected final PopupMenu previewSourcePopup = new PopupMenu();
	protected final PopupMenu versionPopup = new PopupMenu();
	protected final PopupMenu folderOpenPopup = new PopupMenu();
	protected final String initialQuery;
	protected String previewSourceId = "AUTO";
	protected String lastSearchQuery = null;
	protected ViewMode viewMode = ViewMode.CATALOG;
	protected SortMode sortMode = SortMode.RELEVANCE;
	protected GroupMode groupMode = GroupMode.NONE;
	protected int resultLimit = 24;
	protected File pendingFolderOpenDirectory;
	protected int lastMouseX;
	protected int lastMouseY;
	protected int previewScrollOffset = 0;
	protected int previewScrollMax = 0;
	protected int previewViewportX = -1;
	protected int previewViewportY = -1;
	protected int previewViewportWidth = 0;
	protected int previewViewportHeight = 0;
	protected boolean previewScrollbarDragging = false;
	protected int previewScrollbarDragOffset = 0;
	protected int previewSourceChipX = -1;
	protected int previewSourceChipY = -1;
	protected int previewSourceChipWidth = 0;
	protected int previewVersionX = -1;
	protected int previewVersionY = -1;
	protected int previewVersionWidth = 0;
	protected int previewVersionSplitX = -1;
	protected List<ContentPreviewTooltipRegion> previewTooltipRegions = new ArrayList<>();
	protected List<ContentPreviewInteractiveRegion> previewInteractiveRegions = new ArrayList<>();
	protected final Map<String, ContentVersionInfo> latestVersionCache = new LinkedHashMap<>();
	protected final Map<String, ContentResultsInfo> remoteProjectSearchCache = new ConcurrentHashMap<>();
	protected final Set<String> remoteProjectSearchLoading = ConcurrentHashMap.newKeySet();
	protected final Map<String, ContentRemotePreviewData> remotePreviewCache = new ConcurrentHashMap<>();
	protected final Set<String> remotePreviewLoading = ConcurrentHashMap.newKeySet();
	protected final Map<String, Boolean> curseForgeAvailabilityCache = new ConcurrentHashMap<>();
	protected final Set<String> curseForgeAvailabilityLoading = ConcurrentHashMap.newKeySet();
	protected ContentResultsInfo lastVisibleRemoteProjects = new ContentResultsInfo(List.of());
	protected final Map<String, List<InstallChoice>> compatibleInstallChoicesCache = new ConcurrentHashMap<>();
	protected final Map<String, InstallChoice> selectedInstallChoiceCache = new ConcurrentHashMap<>();
	protected String pendingSearchQuery = "";
	protected long searchRefreshDeadlineMs = 0L;
	protected boolean reloadRequired = false;
	protected static final long SEARCH_DEBOUNCE_MS = 140L;
	private static final int PREVIEW_INFO_LABEL_WIDTH = 96;
	private static final int PREVIEW_INFO_ROW_PADDING = 4;

	protected AbstractModrinthContentScreen(Screen parent) {
		this(parent, "");
	}

	protected AbstractModrinthContentScreen(Screen parent, String initialQuery) {
		super(Text.of("Screen"));
		this.parent = parent;
		this.initialQuery = initialQuery == null ? "" : initialQuery;
	}

	@Override
	public void resize(MinecraftClient client, int width, int height) {
		String oldSearch = this.searchField == null ? "" : this.searchField.getText();
		this.init(client, width, height);
		if (this.searchField != null) {
			this.searchField.setText(oldSearch);
		}
	}

	@Override
	protected void init() {
		int footerX = BrowserLayoutHelper.FOOTER_BUTTON_X;
		int searchWidth = usesKoilChrome() ? 200 : Math.min(220, Math.max(160, this.width - 20));
		int searchX = usesKoilChrome() ? this.width - 210 : this.width / 2 - searchWidth / 2;
		int searchY = usesKoilChrome() ? BrowserLayoutHelper.FILTER_BUTTON_Y : 34;
		int sortWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getSortButtonLabel());
		int groupWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getGroupButtonLabel());
		int viewWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getViewButtonLabel());
		this.searchField = new TextFieldWidget(this.textRenderer, searchX, searchY, searchWidth, 20, Text.of(getSearchPlaceholder()));
		this.searchField.setPlaceholder(Text.of(getSearchPlaceholder()));
		if (!this.initialQuery.isBlank()) {
			this.searchField.setText(this.initialQuery);
		}
		this.listWidget = new ContentBrowserListWidget<>(this.client, getListWidth(), getListTop(), getListBottom(), 36, getFallbackIcon(), new ContentBrowserListWidget.BrowserEntryPresenter<>() {
			@Override
			public boolean isGroupingEnabled() {
				return AbstractModrinthContentScreen.this.isGroupingEnabled();
			}

			@Override
			public String getGroupLabel(ContentProjectInfo info) {
				return AbstractModrinthContentScreen.this.getGroupLabel(info);
			}

			@Override
			public boolean isSelected(ContentProjectInfo info) {
				return AbstractModrinthContentScreen.this.isSelected(info);
			}

			@Override
			public void onSelected(ContentProjectInfo info) {
				AbstractModrinthContentScreen.this.setSelectedInfo(info);
			}

			@Override
			public Identifier resolveIcon(ContentProjectInfo info) {
				return AbstractModrinthContentScreen.this.resolveListIconIdentifier(info);
			}

			@Override
			public String title(ContentProjectInfo info) {
				return blank(info.title, "Unknown");
			}

			@Override
			public String metadata(ContentProjectInfo info) {
				String author = blank(info.author, "Unknown");
				String downloads = blank(info.downloads, "0");
				if (info.installed) {
					String version = blank(info.installedVersion, localVersionSummary(info));
					String stateLabel = info.disabled ? "Disabled" : installedLabel(info);
					return "v" + version + "  |  " + stateLabel + "  |  " + downloads + "  |  " + author;
				}
				return downloads + "  |  " + author + "  |  " + blank(info.license, "remote");
			}

			@Override
			public Text narration(ContentProjectInfo info) {
				return Text.of(getScreenTitle());
			}
		});
		this.listWidget.setLeftPos(getListLeft());
		this.addSelectableChild(this.searchField);
		this.addSelectableChild(this.listWidget);
		{
			boolean koilChrome = usesKoilChrome();
			int versionX = koilChrome ? BrowserLayoutHelper.footerTopButtonX(0) : Math.max(4, this.width / 2 - 236);
			int websiteX = koilChrome ? BrowserLayoutHelper.footerTopButtonX(1) + getKoilWebsiteFooterOffset() : Math.max(4, this.width / 2 - 236);
			int rightButtonX = koilChrome ? BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X : Math.min(this.width - 78, this.width / 2 + 160);
			int openFolderX = koilChrome ? rightButtonX : Math.min(this.width - 78, this.width / 2 + 160);
			int topButtonWidth = koilChrome ? BrowserLayoutHelper.FOOTER_TOP_BUTTON_WIDTH : 74;
			int smallButtonWidth = koilChrome ? BrowserLayoutHelper.FOOTER_SMALL_BUTTON_WIDTH : 74;
			int topButtonY = this.height - 52;
			int sideBottomY = this.height - 28;
			int smallButtonY = this.height - 28;
			this.versionSelectorButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Version"), button -> {
				List<PopupMenu.MenuEntry> entries = buildVersionEntries();
				if (!entries.isEmpty()) {
					this.versionPopup.openNearAnchor(this.versionSelectorButton.getX(), this.versionSelectorButton.getY(), this.versionSelectorButton.getWidth(), this.width, this.height, entries);
				}
			}).dimensions(versionX, topButtonY, topButtonWidth, 20).build());
			this.websiteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Website"), button -> openSelectedWebsite()).dimensions(websiteX, koilChrome ? topButtonY : sideBottomY, topButtonWidth, 20).build());
			this.openFolderButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open Folder"), button -> openContentFolderPopup()).dimensions(koilChrome ? rightButtonX : openFolderX, koilChrome ? smallButtonY : topButtonY, topButtonWidth, 20).build());
			this.updateButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Update"), button -> {
				if (this.selectedInfo != null) {
					if (installSelectedProject()) {
						markReloadRequired();
					}
					refreshProjects();
				}
			}).dimensions(koilChrome ? BrowserLayoutHelper.footerSmallButtonX(0) : this.width / 2 - 154, smallButtonY, smallButtonWidth, 20).build());
			this.installOrRemoveButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Install"), button -> {
				if (this.selectedInfo != null) {
					if (this.selectedInfo.installed) {
						confirmRemoveInstalledSelection("Uninstall");
					} else {
						if (installSelectedProject()) {
							markReloadRequired();
						}
						refreshProjects();
					}
				}
			}).dimensions(koilChrome ? BrowserLayoutHelper.footerSmallButtonX(1) : this.width / 2 - 76, smallButtonY, smallButtonWidth, 20).build());
			this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Delete"), button -> {
				if (this.selectedInfo != null) {
					confirmRemoveInstalledSelection("Delete");
				}
			}).dimensions(koilChrome ? BrowserLayoutHelper.footerSmallButtonX(2) + getKoilDeleteFooterOffset() : this.width / 2 + 2, smallButtonY, smallButtonWidth, 20).build());
			this.reloadButton = this.addDrawableChild(ButtonWidget.builder(Text.of(getReloadButtonLabel()), button -> performReloadAction()).dimensions(koilChrome ? BrowserLayoutHelper.footerSmallButtonX(3) : this.width / 2 + 80, smallButtonY, smallButtonWidth, 20).build());
			this.cancelButton = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> {
				if (this.client != null) {
					this.client.setScreen(this.parent);
				}
			}).dimensions(koilChrome ? BrowserLayoutHelper.footerSmallButtonX(4) + getKoilCancelFooterOffset() : this.width / 2 + 2, smallButtonY, smallButtonWidth, 20).build());
		}
		this.pendingSearchQuery = currentSearchQuery();
		ensureRemoteProjectsLoaded(this.pendingSearchQuery);
		refreshProjects();
	}

	protected abstract String getProjectTypeFacet();

	protected abstract String getScreenTitle();

	protected abstract String getSearchPlaceholder();

	protected abstract Identifier getFallbackIcon();

	protected abstract File getContentDirectory();

	protected abstract String getLocalFileExtension();

	protected int getKoilWebsiteFooterOffset() {
		return 0;
	}

	protected int getKoilDeleteFooterOffset() {
		return 0;
	}

	protected int getKoilCancelFooterOffset() {
		return 0;
	}

	protected String installedFileDisplayName(String fileName) {
		return blank(fileName, "Unavailable");
	}

	protected boolean showLocalFileNamesInPreview() {
		return false;
	}

	protected List<String> getModrinthLoaderFilters() {
		return List.of();
	}

	protected int getCurseForgeGameId() {
		return 432;
	}

	protected int getCurseForgeClassId() {
		return -1;
	}

	protected boolean supportsCurseForgeSource(ContentProjectInfo info) {
		return getCurseForgeClassId() > 0;
	}

	protected List<ContentPreviewSectionRow> buildLocalMetadataRows(ContentProjectInfo info, ContentRemotePreviewData remoteData, ContentVersionInfo latestVersion, String localVersion, String description, String fileName, String filePath, InstallChoice selectedChoice) {
		return List.of();
	}

	protected List<String> buildLocalMetadataParagraphs(ContentProjectInfo info, ContentRemotePreviewData remoteData, ContentVersionInfo latestVersion, String localVersion, String description, String fileName, String filePath, InstallChoice selectedChoice) {
		return List.of();
	}

	protected List<ContentPreviewSourceOption> getAdditionalPreviewSources(ContentProjectInfo info) {
		if (!isCurseForgeUsableForInfo(info)) {
			return List.of();
		}
		return List.of(new ContentPreviewSourceOption(
				"CURSEFORGE",
				"CurseForge",
				List.of(
						Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
						Text.literal("CurseForge provider data").styled(style -> style.withColor(0xFFE6EDF5)),
						Text.literal("Use the shared CurseForge metadata and download path for this content type.").styled(style -> style.withColor(0xFFB8C4D2))
				)
		));
	}

	protected List<ContentPreviewSection> buildProviderDetailSections(ContentProjectInfo info, ContentRemotePreviewData remoteData, ContentVersionInfo latestVersion, String localVersion, String description, String fileName, String filePath, InstallChoice selectedChoice) {
		List<ContentPreviewSection> sections = new ArrayList<>();
		List<ContentPreviewSectionRow> localRows = buildLocalMetadataRows(info, remoteData, latestVersion, localVersion, description, fileName, filePath, selectedChoice);
		List<String> localParagraphs = buildLocalMetadataParagraphs(info, remoteData, latestVersion, localVersion, description, fileName, filePath, selectedChoice);
		if ((localRows != null && !localRows.isEmpty()) || (localParagraphs != null && !localParagraphs.isEmpty())) {
			sections.add(new ContentPreviewSection("Local Metadata", localRows == null ? List.of() : localRows, localParagraphs == null ? List.of() : localParagraphs));
		}
		return sections;
	}

	protected ContentRemotePreviewData fetchRemotePreviewForSource(ContentProjectInfo info, String sourceId) throws Exception {
		if ("CURSEFORGE".equals(sourceId)) {
			return fetchCurseForgePreview(info);
		}
		return emptyRemotePreview(sourceLabelForSourceId(sourceId));
	}

	protected List<InstallChoice> fetchCompatibleInstallChoicesForSource(ContentProjectInfo info, String sourceId) {
		if ("CURSEFORGE".equals(sourceId)) {
			return fetchCompatibleCurseForgeChoices(info);
		}
		return List.of();
	}

	protected ContentRemotePreviewData currentRemotePreview(ContentProjectInfo info) {
		return getOrFetchRemotePreview(info);
	}

	protected List<ContentPreviewSourceOption> getAvailablePreviewSources(ContentProjectInfo info) {
		List<ContentPreviewSourceOption> options = new ArrayList<>();
		options.add(new ContentPreviewSourceOption(
				"AUTO",
				"Auto",
				List.of(
						Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
						Text.literal("Best available match").styled(style -> style.withColor(0xFFE6EDF5)),
						Text.literal("Use the strongest provider match for this entry.").styled(style -> style.withColor(0xFFB8C4D2))
				)
		));
		options.add(new ContentPreviewSourceOption(
				"MODRINTH",
				"Modrinth",
				List.of(
						Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
						Text.literal("Remote provider data").styled(style -> style.withColor(0xFFE6EDF5)),
						Text.literal("Use the Modrinth project/version metadata path.").styled(style -> style.withColor(0xFFB8C4D2))
				)
		));
		options.addAll(getAdditionalPreviewSources(info));
		options.add(new ContentPreviewSourceOption(
				"LOCAL",
				"Local",
				List.of(
						Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
						Text.literal("Local file metadata only").styled(style -> style.withColor(0xFFE6EDF5)),
						Text.literal("Skip remote provider preview and stay on the installed/local copy.").styled(style -> style.withColor(0xFFB8C4D2))
				)
		));
		return options;
	}

	protected ContentPreviewSourceOption getSelectedPreviewSourceOption(ContentProjectInfo info) {
		for (ContentPreviewSourceOption option : getAvailablePreviewSources(info)) {
			if (Objects.equals(option.id(), this.previewSourceId)) {
				return option;
			}
		}
		return new ContentPreviewSourceOption(this.previewSourceId, sourceLabelForSourceId(this.previewSourceId), List.of());
	}

	protected String getRemoteProjectUrl(ContentProjectInfo info) {
		return "https://modrinth.com/" + getProjectTypeFacet() + "/" + blank(info.slug, "");
	}

	protected String getIdLabel() {
		return switch (getProjectTypeFacet()) {
			case "mod" -> "Mod ID";
			case "resourcepack", "datapack" -> "Pack ID";
			default -> "ID";
		};
	}

	protected String getLocalContextLabel() {
		return "";
	}

	protected String getReloadButtonLabel() {
		return "Reload";
	}

	protected void performReloadAction() {
		if (!this.reloadRequired || this.client == null) {
			return;
		}
		Screen replacement = createReloadScreen();
		if (replacement != null) {
			this.client.setScreen(replacement);
			return;
		}
		this.remoteProjectSearchCache.clear();
		this.latestVersionCache.clear();
		this.remotePreviewCache.clear();
		this.compatibleInstallChoicesCache.clear();
		this.selectedInstallChoiceCache.clear();
		this.lastVisibleRemoteProjects = new ContentResultsInfo(List.of());
		this.reloadRequired = false;
		queueSearchRefresh();
		refreshProjects();
	}

	protected Screen createReloadScreen() {
		return null;
	}

	protected void markReloadRequired() {
		this.reloadRequired = true;
		updateSelectionButtons();
	}

	protected String getDisplayProjectTypeLabel(String projectType) {
		String normalized = blank(projectType, getProjectTypeFacet()).trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mod" -> "Mod";
			case "resourcepack", "resource_pack" -> "Resource Pack";
			case "datapack", "data_pack" -> "Data Pack";
			case "shader", "shaderpack", "shader_pack" -> "Shader Pack";
			default -> {
				if (normalized.isBlank()) {
					yield "Content";
				}
				String[] parts = normalized.replace('-', ' ').replace('_', ' ').split("\\s+");
				StringBuilder builder = new StringBuilder();
				for (String part : parts) {
					if (part.isBlank()) {
						continue;
					}
					if (!builder.isEmpty()) {
						builder.append(' ');
					}
					builder.append(Character.toUpperCase(part.charAt(0)));
					if (part.length() > 1) {
						builder.append(part.substring(1));
					}
				}
				yield builder.isEmpty() ? "Content" : builder.toString();
			}
		};
	}

	protected String getPreviewSourceLabel(ContentProjectInfo info) {
		if ("LOCAL".equalsIgnoreCase(this.previewSourceId)) {
			return "Local";
		}
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		if (remoteData != null && remoteData.found()) {
			return blank(remoteData.provider(), sourceLabelForSourceId(this.previewSourceId));
		}
		return getSelectedPreviewSourceOption(info).label();
	}

	protected List<Text> getPreviewSourceTooltipLines(ContentProjectInfo info) {
		List<Text> lines = new ArrayList<>(getSelectedPreviewSourceOption(info).tooltipLines());
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		if (remoteData != null && remoteData.found()) {
			int providerColor = "Modrinth".equalsIgnoreCase(remoteData.provider()) ? 0xFF76E6A0 : 0xFF8FC5FF;
			lines.add(Text.literal(blank(remoteData.provider(), getPreviewSourceLabel(info))).styled(style -> style.withColor(providerColor)));
		}
		return lines.isEmpty()
				? List.of(
				Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
				Text.literal(getPreviewSourceLabel(info)).styled(style -> style.withColor(0xFFE6EDF5))
		)
				: lines;
	}

	protected List<String> getPreviewCategoryChips(ContentProjectInfo info, ContentVersionInfo latestVersion) {
		List<String> chips = new ArrayList<>();
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		if (remoteData != null && remoteData.found() && remoteData.categories() != null && !remoteData.categories().isEmpty()) {
			for (String rawCategory : remoteData.categories().subList(0, Math.min(6, remoteData.categories().size()))) {
				String category = sanitizePreviewTag(rawCategory);
				if (!category.isBlank()) {
					chips.add(category);
				}
			}
		}
		return chips;
	}


	protected String sanitizePreviewTag(String rawTag) {
		if (rawTag == null) {
			return "";
		}
		String tag = rawTag.trim();
		if (tag.isEmpty() || tag.length() > 48) {
			return "";
		}
		String lower = tag.toLowerCase(Locale.ROOT);
		if (lower.equals("unknown") || lower.equals("none") || lower.equals("null") || lower.equals("n/a")) {
			return "";
		}
		if (lower.contains(".class") || lower.contains(".java") || lower.contains(".jar") || lower.contains(".zip")) {
			return "";
		}
		if (lower.matches(".*\\.(class|java|jar|zip|png|jpg|jpeg|gif|webp|json|json5|toml|yml|yaml|txt|md|properties|cfg|conf)$")) {
			return "";
		}
		if (tag.contains("/") || tag.contains("\\") || tag.contains(":") || tag.contains("{") || tag.contains("}") || tag.contains("[") || tag.contains("]")) {
			return "";
		}
		if (tag.matches(".*\\s{2,}.*")) {
			return "";
		}
		if (tag.matches("(?:[A-Za-z_$][\\\\w$]*\\\\.){2,}[A-Za-z_$][\\\\w$]*")) {
			return "";
		}
		if (tag.matches("^[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*$") && lower.contains("class")) {
			return "";
		}
		return tag;
	}

	protected int getPreviewTagBackground(String tag) {
		String lower = tag.toLowerCase(Locale.ROOT);
		if (lower.contains("vanilla") || lower.contains("default")) {
			return 0x8A41513A;
		}
		if (lower.contains("faithful") || lower.contains("classic")) {
			return 0x8A33465C;
		}
		if (lower.contains("dark") || lower.contains("night")) {
			return 0x8A4A3B55;
		}
		if (lower.contains("medieval") || lower.contains("fantasy") || lower.contains("rpg")) {
			return 0x8A5A4B33;
		}
		if (lower.contains("pvp") || lower.contains("utility") || lower.contains("ui")) {
			return 0x8A38543F;
		}
		return 0x8A33465C;
	}

	protected int getPreviewTagAccentColor(String tag) {
		String lower = tag.toLowerCase(Locale.ROOT);
		if (lower.contains("vanilla") || lower.contains("default")) {
			return 0xFFB9D49A;
		}
		if (lower.contains("faithful") || lower.contains("classic")) {
			return 0xFF8FC5FF;
		}
		if (lower.contains("dark") || lower.contains("night")) {
			return 0xFFD5B6FF;
		}
		if (lower.contains("medieval") || lower.contains("fantasy") || lower.contains("rpg")) {
			return 0xFFD9C48F;
		}
		if (lower.contains("pvp") || lower.contains("utility") || lower.contains("ui")) {
			return 0xFF9BDEAE;
		}
		return 0xFF8FC5FF;
	}

	protected List<Text> buildPreviewTagTooltip(String tag) {
		return List.of(
				Text.literal("Category").styled(style -> style.withColor(0xFF96A9BC)),
				Text.literal(tag).styled(style -> style.withColor(getPreviewTagAccentColor(tag))),
				Text.literal("Remote provider category tag used to describe the content's purpose, style, or intended use.").styled(style -> style.withColor(0xFFE6EDF5))
		);
	}

	private String blankFallback(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	protected List<ContentPreviewChip> buildHeaderChips(ContentProjectInfo info, ContentVersionInfo latestVersion) {
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		List<ContentPreviewChip> chips = new ArrayList<>();
		chips.add(new ContentPreviewChip(
				info.installed ? installedLabel(info) : "Not Installed",
				info.installed ? (info.disabled ? 0x8A5A3B3B : 0x8A314E39) : 0x8A5A3B3B,
				List.of(
						Text.literal("State").styled(style -> style.withColor(0xFF96A9BC)),
						Text.literal(info.installed ? installedLabel(info) : "Not Installed").styled(style -> style.withColor(info.installed ? (info.disabled ? 0xFFD48E8E : 0xFF8ED4A8) : 0xFFD48E8E))
				)
		));
		if (remoteData != null && remoteData.found()) {
			chips.add(new ContentPreviewChip(remoteData.exactVersion() ? "Exact Match" : "Closest Match", remoteData.exactVersion() ? 0x8A33465C : 0x8A5A4B33,
					List.of(
							Text.literal(remoteData.exactVersion() ? "Exact Match" : "Closest Match").styled(style -> style.withColor(remoteData.exactVersion() ? 0xFF8ED4A8 : 0xFFD9C48F)),
							Text.literal(blankFallback(remoteData.provider(), "Remote")).styled(style -> style.withColor("Modrinth".equalsIgnoreCase(remoteData.provider()) ? 0xFF1BD96A : 0xFF8FC5FF)),
							Text.literal(remoteData.exactVersion() ? "Remote version data matches this installed resource pack directly." : "Found the nearest matching remote version data for this resource pack.").styled(style -> style.withColor(0xFFE6EDF5))
					)
			));
			if (remoteData.approximateProject()) {
				chips.add(new ContentPreviewChip("Closest Project", 0x8A5A4B33, List.of(Text.literal("Project id/title was matched approximately.").styled(style -> style.withColor(0xFFE6EDF5)))));
			}
			if (remoteData.updateAvailable()) {
				chips.add(new ContentPreviewChip(
						"Update Available",
						0x8A38543F,
						List.of(
								Text.literal("Update Available").styled(style -> style.withColor(0xFF9BDEAE)),
								Text.literal("Click to open downloader search.").styled(style -> style.withColor(0xFF8FC5FF))
						),
						"open_downloader_update"
				));
			}
			if (!remoteData.exactGameVersion()) {
				chips.add(new ContentPreviewChip("Closest Game Version", 0x8A5A4B33,
						List.of(
								Text.literal("Closest Game Version").styled(style -> style.withColor(0xFFD9C48F)),
								Text.literal(String.valueOf(remoteData.exactVersion())).styled(style -> style.withColor(0xFF8FC5FF)),
								Text.literal("The remote project title or id was matched as closely as possible instead of exactly.").styled(style -> style.withColor(0xFFE6EDF5))
						)));
			}
			if (!remoteData.exactLoaderMatch()) {
				chips.add(new ContentPreviewChip("Closest Loader", 0x8A5A4B33,
						List.of(
								Text.literal("Closest Project").styled(style -> style.withColor(0xFFD9C48F)),
								Text.literal(blankFallback(remoteData.projectSlug(), "unknown project")).styled(style -> style.withColor(0xFF8FC5FF)),
								Text.literal("The remote project title or id was matched as closely as possible instead of exactly.").styled(style -> style.withColor(0xFFE6EDF5))
						)));
			}
		} else if (latestVersion != null) {
			chips.add(new ContentPreviewChip(
					"Latest " + blank(latestVersion.version_number, "Build"),
					0x8A38543F,
					List.of(
							Text.literal("Newest Version").styled(style -> style.withColor(0xFF9BDEAE)),
							Text.literal(blank(latestVersion.version_number, "Unknown")).styled(style -> style.withColor(0xFFE6EDF5))
					)
			));
		}
		String localContext = getLocalContextLabel();
		if (!localContext.isBlank()) {
			chips.add(new ContentPreviewChip(
					trimPreview(localContext, 110),
					0x8A33465C,
					List.of(
							Text.literal("Local Context").styled(style -> style.withColor(0xFF96A9BC)),
							Text.literal(localContext).styled(style -> style.withColor(0xFFE6EDF5))
					)
			));
		}
		return chips;
	}

	protected List<ContentPreviewChip> buildCategoryChips(ContentProjectInfo info, ContentVersionInfo latestVersion) {
		List<ContentPreviewChip> chips = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String rawChip : getPreviewCategoryChips(info, latestVersion)) {
			String chip = sanitizePreviewTag(rawChip);
			if (chip.isBlank() || shouldSuppressPreviewCategoryChip(chip) || !seen.add(chip.toLowerCase(Locale.ROOT))) {
				continue;
			}
			chips.add(new ContentPreviewChip(
					chip,
					getPreviewTagBackground(chip),
					buildPreviewTagTooltip(chip)
			));
		}
		return chips;
	}

	protected boolean shouldSuppressPreviewCategoryChip(String chip) {
		if (chip == null || chip.isBlank()) {
			return true;
		}
		String normalized = chip.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
		String projectType = getProjectTypeFacet();
		if ("resourcepack".equals(projectType) || "datapack".equals(projectType)) {
			return normalized.equals("pack")
					|| normalized.equals("resource pack")
					|| normalized.equals("resourcepack")
					|| normalized.equals("data pack")
					|| normalized.equals("datapack");
		}
		if ("shader".equals(projectType)) {
			return normalized.equals("shader")
					|| normalized.equals("shaders")
					|| normalized.equals("shader pack")
					|| normalized.equals("shaderpack")
					|| normalized.equals("pack");
		}
		return false;
	}

	protected Identifier getPreviewIconIdentifier(ContentProjectInfo info) {
		return resolveListIconIdentifier(info);
	}

	protected String localVersionSummary(ContentProjectInfo info) {
		return info.fileName == null || info.fileName.isBlank() ? "local" : info.fileName;
	}

	protected String installedLabel(ContentProjectInfo info) {
		return info.disabled ? "Disabled" : "Installed";
	}

	protected int getListLeft() {
		return BrowserLayoutHelper.LIST_INNER_LEFT;
	}

	protected int getListTop() {
		return BrowserLayoutHelper.LIST_WIDGET_TOP;
	}

	protected int getListWidth() {
		return BrowserLayoutHelper.LIST_INNER_RIGHT - BrowserLayoutHelper.LIST_INNER_LEFT;
	}

	protected int getListBottom() {
		return this.height - BrowserLayoutHelper.FOOTER_HEIGHT;
	}

	protected boolean usesKoilChrome() {
		return true;
	}

	protected String currentSearchQuery() {
		return this.searchField == null ? "" : this.searchField.getText().trim();
	}

	protected void queueSearchRefresh() {
		this.pendingSearchQuery = currentSearchQuery();
		this.searchRefreshDeadlineMs = System.currentTimeMillis() + SEARCH_DEBOUNCE_MS;
	}

	protected void ensureRemoteProjectsLoaded(String query) {
		String normalized = query == null ? "" : query.trim();
		if (this.client == null || this.remoteProjectSearchCache.containsKey(normalized) || !this.remoteProjectSearchLoading.add(normalized)) {
			return;
		}
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request;
		try {
			String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
			String encodedQuery = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
			URI uri = new URI("https://api.modrinth.com/v2/search?query=" + encodedQuery + "&limit=" + this.resultLimit + "&facets=%5B%5B%22versions%3A" + version + "%22%5D,%5B%22project_type%3A" + getProjectTypeFacet() + "%22%5D%5D");
			request = HttpRequest.newBuilder().uri(uri).GET().build();
		} catch (Exception exception) {
			this.remoteProjectSearchLoading.remove(normalized);
			return;
		}
		client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, throwable) -> {
			this.remoteProjectSearchLoading.remove(normalized);
			if (throwable != null || response == null || this.client == null) {
				return;
			}
			ContentResultsInfo parsed = new Gson().fromJson(response.body(), ContentResultsInfo.class);
			ContentResultsInfo result = parsed == null || parsed.hits() == null ? new ContentResultsInfo(List.of()) : parsed;
			this.remoteProjectSearchCache.put(normalized, result);
			this.client.execute(() -> {
				if (normalized.equals(currentSearchQuery())) {
					refreshProjects();
				}
			});
		});
	}

	protected ContentResultsInfo fetchRemoteProjects() {
		String query = currentSearchQuery();
		ContentResultsInfo cached = this.remoteProjectSearchCache.get(query);
		if (cached != null) {
			this.lastVisibleRemoteProjects = cached;
			return cached;
		}
		ensureRemoteProjectsLoaded(query);
		return this.lastVisibleRemoteProjects == null ? new ContentResultsInfo(List.of()) : this.lastVisibleRemoteProjects;
	}

	protected void refreshProjects() {
		List<ContentProjectInfo> visible = buildVisibleProjects();
		this.listWidget.updateEntries(visible);
		if (visible.isEmpty()) {
			this.selectedInfo = null;
			this.listWidget.setSelected(null);
		} else if (this.selectedInfo == null || visible.stream().noneMatch(this::matchesSelectedInfo)) {
			this.selectedInfo = visible.get(0);
		}
		this.lastSearchQuery = currentSearchQuery();
		this.listWidget.syncSelection();
		updateSelectionButtons();
	}

	protected boolean matchesSelectedInfo(ContentProjectInfo info) {
		if (this.selectedInfo == null || info == null) {
			return false;
		}
		String selectedSlug = blank(this.selectedInfo.slug, "").trim();
		String infoSlug = blank(info.slug, "").trim();
		if (!selectedSlug.isBlank() && !infoSlug.isBlank()) {
			return selectedSlug.equalsIgnoreCase(infoSlug);
		}
		String selectedTitle = blank(this.selectedInfo.title, "").trim();
		String infoTitle = blank(info.title, "").trim();
		return !selectedTitle.isBlank() && !infoTitle.isBlank() && selectedTitle.equalsIgnoreCase(infoTitle);
	}

	protected List<ContentProjectInfo> buildVisibleProjects() {
		List<ContentProjectInfo> visible = new ArrayList<>();
		for (ContentProjectInfo info : fetchRemoteProjects().hits()) {
			File installedFile = findInstalledFile(info.slug, info.title);
			ContentProjectInfo enriched = new ContentProjectInfo(
					info.title,
					info.description,
					info.author,
					info.slug,
					info.icon_url,
					info.downloads,
					info.license,
					"Modrinth",
					false,
					installedFile != null,
					installedFile == null ? "" : installedFile.getName(),
					installedFile == null ? "" : localVersionSummary(new ContentProjectInfo(info.title, info.description, info.author, info.slug, info.icon_url, info.downloads, info.license, "Modrinth", false, true, installedFile.getName(), "", false)),
					installedFile != null && installedFile.getName().toLowerCase(Locale.ROOT).endsWith(".disabled")
			);
			enriched.project_type = blank(info.project_type, getProjectTypeFacet());
			visible.add(enriched);
		}
		String query = currentSearchQuery().toLowerCase(Locale.ROOT);
		if (!query.isBlank()) {
			visible.removeIf(info -> !matchesQuery(info, query));
		}
		switch (this.viewMode) {
			case INSTALLED_MATCHES -> visible.removeIf(info -> !info.installed);
			case NOT_INSTALLED -> visible.removeIf(info -> info.installed);
			case CATALOG -> {
			}
		}
		sortVisibleProjects(visible);
		return visible;
	}

	protected boolean matchesQuery(ContentProjectInfo info, String query) {
		return contains(info.title, query)
				|| contains(info.slug, query)
				|| contains(info.author, query)
				|| contains(info.description, query)
				|| contains(info.fileName, query);
	}

	protected void sortVisibleProjects(List<ContentProjectInfo> visible) {
		Comparator<ContentProjectInfo> comparator = switch (this.sortMode) {
			case NAME -> Comparator.comparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case DOWNLOADS -> Comparator
					.comparingLong((ContentProjectInfo info) -> parseDownloads(info.downloads))
					.reversed()
					.thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case AUTHOR -> Comparator
					.comparing((ContentProjectInfo info) -> blank(info.author, "").toLowerCase(Locale.ROOT))
					.thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case RELEVANCE -> Comparator
					.comparing((ContentProjectInfo info) -> !info.installed)
					.thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
		};
		visible.sort(comparator);
	}

	protected long parseDownloads(String downloads) {
		if (downloads == null || downloads.isBlank()) {
			return 0L;
		}
		try {
			return Long.parseLong(downloads.replaceAll("[^0-9]", ""));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	protected boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.client == null) {
			return;
		}
		this.lastMouseX = mouseX;
		this.lastMouseY = mouseY;
		JSONFileEditor.getValueFromJson("./koil/sys/config.json", "jsonBackground");
		BrowserLayoutHelper.renderContentBackground(context, this.client, this.width, this.height);
		MarkdownPreviewRenderer.beginInteractiveFrame();
		if (usesKoilChrome()) {
			BrowserLayoutHelper.renderBrowserChrome(context, this.client, this.textRenderer, this.width, this.height, getScreenTitle());
		} else {
			this.renderBackground(context);
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(getScreenTitle()), this.width / 2, 18, 0xFFFFFF);
		}
		this.listWidget.render(context, mouseX, mouseY, delta);
		renderSelectedPreview(context);
		if (usesKoilChrome()) {
			BrowserLayoutHelper.renderBrowserBars(context, this.client, this.textRenderer, this.width, this.height, getScreenTitle());
		}
		syncWebsiteButtonState();
		super.render(context, mouseX, mouseY, delta);
		if (usesKoilChrome()) {
			int searchX = this.width - 210;
			int sortX = searchX - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getViewButtonLabel()) - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getGroupButtonLabel()) - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getSortButtonLabel());
			int limitX = sortX - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getLimitButtonLabel());
			BrowserLayoutHelper.renderFilterButton(context, this.textRenderer, limitX, getLimitButtonLabel());
			BrowserLayoutHelper.renderFilterButton(context, this.textRenderer, sortX, getSortButtonLabel());
			int groupButtonX = sortX + BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getSortButtonLabel()) + BrowserLayoutHelper.FILTER_BUTTON_GAP;
			BrowserLayoutHelper.renderFilterButton(context, this.textRenderer, groupButtonX, getGroupButtonLabel());
			BrowserLayoutHelper.renderFilterButton(context, this.textRenderer, groupButtonX + BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getGroupButtonLabel()) + BrowserLayoutHelper.FILTER_BUTTON_GAP, getViewButtonLabel());
		}
		this.searchField.render(context, mouseX, mouseY, delta);
		this.filterPopup.render(context, mouseX, mouseY);
		this.previewSourcePopup.render(context, mouseX, mouseY);
		this.versionPopup.render(context, mouseX, mouseY);
		this.folderOpenPopup.render(context, mouseX, mouseY);
		renderPreviewHoverTooltip(context, mouseX, mouseY);
		((ScreenChromeHost) this).koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
	}

	@Override
	public void tick() {
		this.searchField.tick();
		String query = currentSearchQuery();
		if (!Objects.equals(this.pendingSearchQuery, query)) {
			queueSearchRefresh();
		}
		if (this.searchRefreshDeadlineMs > 0L && System.currentTimeMillis() >= this.searchRefreshDeadlineMs) {
			this.searchRefreshDeadlineMs = 0L;
			ensureRemoteProjectsLoaded(this.pendingSearchQuery);
			refreshProjects();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.client != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			this.client.setScreen(this.parent);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (((ScreenChromeHost) this).koil$consumeScreenChromeClick(mouseX, mouseY, button)) {
			return true;
		}
		if (button == 0 && this.filterPopup.isOpen()) {
			PopupMenu.MenuEntry selected = this.filterPopup.click(mouseX, mouseY);
			if (selected != null) {
				UiSoundHelper.playButtonClick();
				applyFilterSelection(selected.id());
				return true;
			}
			if (!this.filterPopup.isOpen()) {
				return true;
			}
		}
		if (button == 0 && this.versionPopup.isOpen()) {
			PopupMenu.MenuEntry selected = this.versionPopup.click(mouseX, mouseY);
			if (selected != null) {
				UiSoundHelper.playButtonClick();
				applyVersionSelection(selected.id());
				return true;
			}
			if (!this.versionPopup.isOpen()) {
				return true;
			}
		}
		if (button == 0 && this.previewSourcePopup.isOpen()) {
			PopupMenu.MenuEntry selected = this.previewSourcePopup.click(mouseX, mouseY);
			if (selected != null) {
				UiSoundHelper.playButtonClick();
				applyPreviewSourceSelection(selected.id());
				return true;
			}
			if (!this.previewSourcePopup.isOpen()) {
				return true;
			}
		}
		if (button == 0 && this.folderOpenPopup.isOpen()) {
			PopupMenu.MenuEntry selected = this.folderOpenPopup.click(mouseX, mouseY);
			if (selected != null) {
				FolderOpenHelper.handleAction(this.client, this.pendingFolderOpenDirectory, selected.id());
				UiSoundHelper.playButtonClick();
				return true;
			}
			if (!this.folderOpenPopup.isOpen()) {
				return true;
			}
		}
		if (button == 0 && usesKoilChrome()) {
			int searchX = this.width - 210;
			int sortX = searchX - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getViewButtonLabel()) - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getGroupButtonLabel()) - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getSortButtonLabel());
			int limitX = sortX - BrowserLayoutHelper.FILTER_BUTTON_GAP - BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getLimitButtonLabel());
			int limitWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getLimitButtonLabel());
			int sortWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getSortButtonLabel());
			int groupX = sortX + sortWidth + BrowserLayoutHelper.FILTER_BUTTON_GAP;
			int groupWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getGroupButtonLabel());
			int viewX = groupX + groupWidth + BrowserLayoutHelper.FILTER_BUTTON_GAP;
			int viewWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, getViewButtonLabel());
			if (isWithinButton(mouseX, mouseY, limitX, BrowserLayoutHelper.FILTER_BUTTON_Y, limitWidth, BrowserLayoutHelper.FILTER_BUTTON_HEIGHT)) {
				openFilterMenu(FilterMenu.LIMIT, mouseX, mouseY);
				return true;
			}
			if (isWithinButton(mouseX, mouseY, sortX, BrowserLayoutHelper.FILTER_BUTTON_Y, sortWidth, BrowserLayoutHelper.FILTER_BUTTON_HEIGHT)) {
				openFilterMenu(FilterMenu.SORT, mouseX, mouseY);
				return true;
			}
			if (isWithinButton(mouseX, mouseY, groupX, BrowserLayoutHelper.FILTER_BUTTON_Y, groupWidth, BrowserLayoutHelper.FILTER_BUTTON_HEIGHT)) {
				openFilterMenu(FilterMenu.GROUP, mouseX, mouseY);
				return true;
			}
			if (isWithinButton(mouseX, mouseY, viewX, BrowserLayoutHelper.FILTER_BUTTON_Y, viewWidth, BrowserLayoutHelper.FILTER_BUTTON_HEIGHT)) {
				openFilterMenu(FilterMenu.SHOW, mouseX, mouseY);
				return true;
			}
		}
		if (button == 0 && isWithinButton(mouseX, mouseY, this.previewSourceChipX, this.previewSourceChipY, this.previewSourceChipWidth, 10)) {
			List<PopupMenu.MenuEntry> entries = buildPreviewSourceEntries();
			if (!entries.isEmpty()) {
				UiSoundHelper.playButtonClick();
				this.previewSourcePopup.openAtPointer((int) mouseX, (int) mouseY, this.width, this.height, entries);
				return true;
			}
		}
		if (button == 0 && this.previewScrollMax > 0 && this.previewViewportHeight > 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
			int thumbY = previewScrollbarThumbY();
			int thumbHeight = previewScrollbarThumbHeight();
			if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
				this.previewScrollbarDragging = true;
				this.previewScrollbarDragOffset = (int) mouseY - thumbY;
			} else {
				this.previewScrollbarDragging = true;
				this.previewScrollbarDragOffset = thumbHeight / 2;
				setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
			}
			return true;
		}
		if (button == 0) {
			for (ContentPreviewInteractiveRegion region : this.previewInteractiveRegions) {
				if (isWithinButton(mouseX, mouseY, region.x(), region.y(), region.width(), region.height()) && handlePreviewAction(region.actionId())) {
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (button == 0 && this.previewScrollbarDragging) {
			setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && this.previewScrollbarDragging) {
			this.previewScrollbarDragging = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (this.previewViewportX >= 0
				&& mouseX >= this.previewViewportX
				&& mouseX <= this.previewViewportX + this.previewViewportWidth
				&& mouseY >= this.previewViewportY
				&& mouseY <= this.previewViewportY + this.previewViewportHeight) {
			this.previewScrollOffset = clamp(this.previewScrollOffset - (int) (amount * 18), 0, this.previewScrollMax);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	protected void openFilterMenu(FilterMenu menu, double mouseX, double mouseY) {
		UiSoundHelper.playButtonClick();
		this.filterPopup.openAtPointer((int) mouseX, (int) mouseY, this.width, this.height, buildFilterEntries(menu));
	}

	protected List<PopupMenu.MenuEntry> buildFilterEntries(FilterMenu menu) {
		List<PopupMenu.MenuEntry> entries = new ArrayList<>();
		switch (menu) {
			case SORT -> {
				for (SortMode mode : SortMode.values()) {
					entries.add(new PopupMenu.MenuEntry("sort:" + mode.name(), mode.label));
				}
			}
			case GROUP -> {
				for (GroupMode mode : GroupMode.values()) {
					entries.add(new PopupMenu.MenuEntry("group:" + mode.name(), mode.label));
				}
			}
			case SHOW -> {
				for (ViewMode mode : ViewMode.values()) {
					entries.add(new PopupMenu.MenuEntry("view:" + mode.name(), mode.label));
				}
			}
			case LIMIT -> {
				for (int limit : new int[]{12, 24, 48, 96}) {
					entries.add(new PopupMenu.MenuEntry("limit:" + limit, "Max " + limit));
				}
			}
		}
		return entries;
	}

	protected void applyFilterSelection(String actionId) {
		if (actionId == null) {
			return;
		}
		if (actionId.startsWith("sort:")) {
			this.sortMode = SortMode.valueOf(actionId.substring("sort:".length()));
		} else if (actionId.startsWith("group:")) {
			this.groupMode = GroupMode.valueOf(actionId.substring("group:".length()));
		} else if (actionId.startsWith("view:")) {
			this.viewMode = ViewMode.valueOf(actionId.substring("view:".length()));
		} else if (actionId.startsWith("limit:")) {
			try {
				this.resultLimit = Math.max(12, Math.min(96, Integer.parseInt(actionId.substring("limit:".length()))));
				this.remoteProjectSearchCache.clear();
				this.lastVisibleRemoteProjects = null;
				ensureRemoteProjectsLoaded(currentSearchQuery());
			} catch (NumberFormatException ignored) {
			}
		}
		refreshProjects();
	}

	protected List<PopupMenu.MenuEntry> buildVersionEntries() {
		if (this.selectedInfo == null) {
			return List.of();
		}
		List<InstallChoice> choices = getCompatibleInstallChoices(this.selectedInfo);
		List<PopupMenu.MenuEntry> entries = new ArrayList<>();
		InstallChoice selectedChoice = getSelectedInstallChoice(this.selectedInfo);
		for (InstallChoice choice : choices) {
			entries.add(new PopupMenu.MenuEntry("version:" + choice.id(), choice.label()));
		}
		return entries;
	}

	protected void applyVersionSelection(String actionId) {
		if (this.selectedInfo == null || actionId == null || !actionId.startsWith("version:")) {
			return;
		}
		String id = actionId.substring("version:".length());
		for (InstallChoice choice : getCompatibleInstallChoices(this.selectedInfo)) {
			if (Objects.equals(choice.id(), id)) {
				this.selectedInstallChoiceCache.put(selectionKey(this.selectedInfo) + ":" + this.previewSourceId, choice);
				break;
			}
		}
		updateSelectionButtons();
	}

	protected List<InstallChoice> getCompatibleInstallChoices(ContentProjectInfo info) {
		if (info == null || info.slug == null || info.slug.isBlank()) {
			return List.of();
		}
		return this.compatibleInstallChoicesCache.computeIfAbsent(selectionKey(info) + ":" + this.previewSourceId, ignored -> switch (this.previewSourceId) {
			case "LOCAL" -> List.of();
			case "AUTO", "MODRINTH" -> {
				List<InstallChoice> modrinthChoices = fetchCompatibleChoices(info);
				yield "AUTO".equals(this.previewSourceId) && modrinthChoices.isEmpty() ? fetchCompatibleInstallChoicesFromAdditionalProviders(info) : modrinthChoices;
			}
			default -> fetchCompatibleInstallChoicesForSource(info, this.previewSourceId);
		});
	}

	protected List<InstallChoice> fetchCompatibleChoices(ContentProjectInfo info) {
		try {
			URI uri = buildModrinthVersionUri(info.slug);
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
			List<ContentVersionInfo> versions = Arrays.stream(new Gson().fromJson(response.body(), ContentVersionInfo[].class)).toList();
			List<InstallChoice> choices = new ArrayList<>();
			for (int i = 0; i < versions.size(); i++) {
				ContentVersionInfo versionInfo = versions.get(i);
				ContentFileInfo primary = selectPrimaryFile(versionInfo.files);
				if (primary == null || primary.url == null || primary.url.isBlank()) {
					continue;
				}
				String label = blank(versionInfo.version_number, "Unknown") + "  |  " + trimPreview(blank(versionInfo.name, blank(versionInfo.version_type, "Build")), 120);
				choices.add(createInstallChoice("modrinth:" + i, label, primary, blank(versionInfo.version_number, "Unknown"), versionInfo, "Modrinth"));
			}
			return choices;
		} catch (Exception ignored) {
			return List.of();
		}
	}

	protected InstallChoice getSelectedInstallChoice(ContentProjectInfo info) {
		if (info == null) {
			return null;
		}
		String key = selectionKey(info) + ":" + this.previewSourceId;
		InstallChoice selected = this.selectedInstallChoiceCache.get(key);
		if (selected != null) {
			return selected;
		}
		List<InstallChoice> choices = getCompatibleInstallChoices(info);
		if (choices.isEmpty()) {
			return null;
		}
		InstallChoice choice = choices.get(0);
		this.selectedInstallChoiceCache.put(key, choice);
		return choice;
	}

	protected void setSelectedInfo(ContentProjectInfo info) {
		this.selectedInfo = info;
		this.previewScrollOffset = 0;
		updateSelectionButtons();
	}

	protected boolean isSelected(ContentProjectInfo info) {
		return matchesSelectedInfo(info);
	}

	protected boolean isGroupingEnabled() {
		return this.groupMode != GroupMode.NONE;
	}

	protected String getGroupLabel(ContentProjectInfo info) {
		return this.groupMode == GroupMode.AUTHOR ? blank(info.author, "Unknown") : blank(info.sourceLabel, "Modrinth");
	}

	protected void updateSelectionButtons() {
		boolean hasSelection = this.selectedInfo != null;
		boolean installed = hasSelection && this.selectedInfo.installed;
		InstallChoice selectedChoice = hasSelection ? getSelectedInstallChoice(this.selectedInfo) : null;
		ContentRemotePreviewData remotePreview = hasSelection ? currentRemotePreview(this.selectedInfo) : null;
		boolean updateAvailable = hasSelection && installed && hasDetectedUpdate(this.selectedInfo, selectedChoice, remotePreview);
		if (this.versionSelectorButton != null) {
			this.versionSelectorButton.active = hasSelection && !getCompatibleInstallChoices(this.selectedInfo).isEmpty();
			this.versionSelectorButton.setMessage(Text.of(getVersionButtonLabel()));
		}
		if (this.websiteButton != null) {
			this.websiteButton.active = hasSelection;
		}
		if (this.reloadButton != null) {
			this.reloadButton.active = this.reloadRequired;
			this.reloadButton.setMessage(Text.of(getReloadButtonLabel()));
		}
		if (this.deleteButton != null) {
			this.deleteButton.active = installed;
		}
		if (this.updateButton != null) {
			this.updateButton.active = updateAvailable;
		}
		if (this.installOrRemoveButton != null) {
			this.installOrRemoveButton.active = hasSelection;
			this.installOrRemoveButton.setMessage(Text.of(installed ? "Uninstall" : "Install"));
		}
		if (this.openFolderButton != null) {
			this.openFolderButton.active = getContentDirectory() != null;
		}
	}

	protected void syncWebsiteButtonState() {
		if (this.websiteButton != null) {
			this.websiteButton.active = this.selectedInfo != null;
		}
	}

	protected boolean hasDetectedUpdate(ContentProjectInfo info, InstallChoice selectedChoice, ContentRemotePreviewData remotePreview) {
		if (info == null || !info.installed) {
			return false;
		}
		if (remotePreview != null && remotePreview.updateAvailable()) {
			return true;
		}
		String targetVersion = selectedChoice != null ? blank(selectedChoice.versionNumber(), "") : "";
		if (targetVersion.isBlank() && remotePreview != null) {
			targetVersion = blank(remotePreview.versionNumber(), blank(remotePreview.versionTitle(), ""));
		}
		if (targetVersion.isBlank()) {
			return false;
		}
		for (String localVersion : localUpdateVersionCandidates(info)) {
			if (isVersionLower(localVersion, targetVersion)) {
				return true;
			}
		}
		return false;
	}

	protected List<String> localUpdateVersionCandidates(ContentProjectInfo info) {
		if (info == null) {
			return List.of();
		}
		List<String> candidates = new ArrayList<>();
		addVersionCandidate(candidates, info.installedVersion);
		addVersionCandidate(candidates, localVersionSummary(info));
		addVersionCandidate(candidates, info.fileName);
		File installedFile = findSelectedInstalledFile();
		if (installedFile != null) {
			addVersionCandidate(candidates, installedFile.getName());
		}
		return candidates;
	}

	protected void addVersionCandidate(List<String> candidates, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		String trimmed = value.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (lower.equals("local") || lower.equals("unknown") || lower.equals("not installed") || lower.equals("unavailable")) {
			return;
		}
		for (String existing : candidates) {
			if (existing.equalsIgnoreCase(trimmed)) {
				return;
			}
		}
		candidates.add(trimmed);
	}

	protected String getVersionButtonLabel() {
		if (this.selectedInfo == null) {
			return "Version  Auto";
		}
		InstallChoice choice = getSelectedInstallChoice(this.selectedInfo);
		return choice == null ? "Version  Auto" : "Version  " + trimPreview(choice.versionNumber(), 96);
	}

	protected boolean installSelectedProject() {
		if (this.selectedInfo == null) {
			return false;
		}
		File previousInstalledFile = this.selectedInfo.installed ? findSelectedInstalledFile() : null;
		InstallChoice choice = getSelectedInstallChoice(this.selectedInfo);
		if (choice != null && choice.file() != null) {
			File installed = downloadProjectFile(choice.file(), getContentDirectory(), blank(this.selectedInfo.slug, this.selectedInfo.title), getLocalFileExtension());
			if (installed != null) {
				deletePreviousInstalledFile(previousInstalledFile, installed);
				return true;
			}
		}
		if ("CURSEFORGE".equals(this.previewSourceId)) {
			File installed = installCurseForgeFile(this.selectedInfo);
			if (installed != null) {
				deletePreviousInstalledFile(previousInstalledFile, installed);
				return true;
			}
		}
		File installed = installProjectFile(blank(this.selectedInfo.slug, this.selectedInfo.title));
		if (installed != null) {
			deletePreviousInstalledFile(previousInstalledFile, installed);
			return true;
		}
		return false;
	}

	protected File installProjectFile(String slug) {
		if (this.client == null) {
			return null;
		}
		try {
			URI uri = buildModrinthVersionUri(slug);
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
			List<ContentVersionInfo> versions = Arrays.stream(new Gson().fromJson(response.body(), ContentVersionInfo[].class)).toList();
			if (versions.isEmpty()) {
				return null;
			}
			ContentVersionInfo versionInfo = versions.get(0);
			ContentFileInfo primaryFile = selectPrimaryFile(versionInfo.files);
			if (primaryFile == null) {
				return null;
			}
			return downloadProjectFile(primaryFile, getContentDirectory(), slug, getLocalFileExtension());
		} catch (Exception exception) {
			return null;
		}
	}

	protected boolean removeInstalledSelection() {
		if (this.selectedInfo == null) {
			return false;
		}
		File installedFile = findSelectedInstalledFile();
		if (installedFile != null && installedFile.exists()) {
			FileUtils.deleteQuietly(installedFile);
			boolean deleted = !installedFile.exists();
			if (deleted) {
				this.selectedInfo.installed = false;
				this.selectedInfo.fileName = "";
				this.selectedInfo.installedVersion = "";
				this.selectedInfo.disabled = false;
				clearProviderCachesForSelection(this.selectedInfo);
			}
			return deleted;
		}
		return false;
	}

	protected void confirmRemoveInstalledSelection(String actionLabel) {
		if (this.selectedInfo == null) {
			return;
		}
		File installedFile = findSelectedInstalledFile();
		if (installedFile == null || !installedFile.exists()) {
			refreshProjects();
			return;
		}
		String title = actionLabel == null || actionLabel.isBlank() ? "Delete" : actionLabel;
		String displayName = this.selectedInfo.title == null || this.selectedInfo.title.isBlank() ? installedFile.getName() : this.selectedInfo.title;
		if (this.client == null) {
			if (removeInstalledSelection()) {
				markReloadRequired();
			}
			refreshProjects();
			return;
		}
		this.client.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed && removeInstalledSelection()) {
				markReloadRequired();
			}
			refreshProjects();
			if (this.client != null) {
				this.client.setScreen(this);
			}
		}, Text.literal(title + " Content"), Text.literal("Are you sure you want to " + title.toLowerCase(Locale.ROOT) + " " + displayName + "?"), Text.literal(title), Text.literal("Cancel")));
	}

	protected File findSelectedInstalledFile() {
		if (this.selectedInfo == null) {
			return null;
		}
		File exact = findInstalledFileByName(this.selectedInfo.fileName);
		return exact != null ? exact : findInstalledFile(this.selectedInfo.slug, this.selectedInfo.title);
	}

	protected File findInstalledFileByName(String fileName) {
		File directory = getContentDirectory();
		if (directory == null || fileName == null || fileName.isBlank()) {
			return null;
		}
		File exact = new File(directory, fileName);
		if (exact.exists()) {
			return exact;
		}
		File[] files = directory.listFiles();
		if (files == null) {
			return null;
		}
		for (File file : files) {
			if (file.getName().equalsIgnoreCase(fileName)) {
				return file;
			}
		}
		return null;
	}

	protected File findInstalledFile(String slug, String title) {
		File directory = getContentDirectory();
		if (directory == null || ((slug == null || slug.isBlank()) && (title == null || title.isBlank()))) {
			return null;
		}
		File[] files = directory.listFiles();
		if (files == null) {
			return null;
		}
		String lowerSlug = slug == null ? "" : slug.toLowerCase(Locale.ROOT);
		String normalizedSlug = normalizeMatchKey(slug);
		String normalizedTitle = normalizeMatchKey(title);
		for (File file : files) {
			String lowerName = file.getName().toLowerCase(Locale.ROOT);
			String normalizedName = normalizeMatchKey(file.getName());
			if ((!lowerSlug.isBlank() && (lowerName.equals(lowerSlug)
					|| lowerName.equals(lowerSlug + getLocalFileExtension())
					|| lowerName.equals(lowerSlug + getLocalFileExtension() + ".disabled")
					|| lowerName.startsWith(lowerSlug + "-")
					|| lowerName.startsWith(lowerSlug + "+")))
					|| (!normalizedSlug.isBlank() && normalizedName.contains(normalizedSlug))
					|| (!normalizedTitle.isBlank() && normalizedName.contains(normalizedTitle))) {
				return file;
			}
		}
		return null;
	}

	protected ContentFileInfo selectPrimaryFile(List<ContentFileInfo> files) {
		if (files == null || files.isEmpty()) {
			return null;
		}
		for (ContentFileInfo file : files) {
			if (file != null && Boolean.TRUE.equals(file.primary)) {
				return file;
			}
		}
		return files.get(0);
	}

	protected InstallChoice createInstallChoice(String id, String label, ContentFileInfo file, String versionNumber, ContentVersionInfo version) {
		return createInstallChoice(id, label, file, versionNumber, version, "Modrinth");
	}

	protected InstallChoice createInstallChoice(String id, String label, ContentFileInfo file, String versionNumber, ContentVersionInfo version, String provider) {
		return new InstallChoice(id, label, file, versionNumber, version, provider);
	}

	protected File downloadProjectFile(ContentFileInfo file, File directory, String slug, String fallbackExtension) {
		if (file == null || file.url == null || file.url.isBlank() || directory == null) {
			return null;
		}
		if (!directory.exists() && !directory.mkdirs()) {
			return null;
		}
		String fileName = file.filename != null && !file.filename.isBlank() ? file.filename : slug + fallbackExtension;
		File target = new File(directory, fileName);
		try {
			FileUtils.copyURLToFile(new java.net.URL(file.url), target);
			return target.exists() ? target : null;
		} catch (IOException exception) {
			return null;
		}
	}

	protected void deletePreviousInstalledFile(File previousInstalledFile, File newInstalledFile) {
		if (previousInstalledFile == null || newInstalledFile == null || !previousInstalledFile.exists()) {
			return;
		}
		try {
			if (previousInstalledFile.getCanonicalFile().equals(newInstalledFile.getCanonicalFile())) {
				return;
			}
		} catch (IOException ignored) {
			if (previousInstalledFile.getAbsolutePath().equalsIgnoreCase(newInstalledFile.getAbsolutePath())) {
				return;
			}
		}
		FileUtils.deleteQuietly(previousInstalledFile);
	}

	protected ContentVersionInfo getLatestVersionInfo(String slug) {
		if (slug == null || slug.isBlank()) {
			return null;
		}
		ContentVersionInfo cached = this.latestVersionCache.get(slug);
		if (cached != null) {
			return cached;
		}
		try {
			URI uri = buildModrinthVersionUri(slug);
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
			List<ContentVersionInfo> versions = Arrays.stream(new Gson().fromJson(response.body(), ContentVersionInfo[].class)).toList();
			ContentVersionInfo latest = versions.isEmpty() ? null : versions.get(0);
			if (latest != null) {
				this.latestVersionCache.put(slug, latest);
			}
			return latest;
		} catch (Exception ignored) {
			return null;
		}
	}

	protected Identifier resolveListIconIdentifier(ContentProjectInfo info) {
		Identifier remote = ContentRemoteIconResolver.resolve(info.icon_url, selectionKey(info));
		return remote != null ? remote : getFallbackIcon();
	}

	protected void openSelectedWebsite() {
		if (this.selectedInfo == null) {
			return;
		}
		Util.getOperatingSystem().open(getRemoteProjectUrl(this.selectedInfo));
	}

	protected void openContentFolder() {
		File directory = getContentDirectory();
		if (directory == null) {
			return;
		}
		if (!directory.exists()) {
			directory.mkdirs();
		}
		Util.getOperatingSystem().open(directory.toURI());
	}

	protected void openContentFolderPopup() {
		if (this.openFolderButton == null) {
			return;
		}
		File directory = getContentDirectory();
		if (directory == null) {
			return;
		}
		if (!directory.exists()) {
			directory.mkdirs();
		}
		this.pendingFolderOpenDirectory = directory;
		this.folderOpenPopup.toggleAtPointer(this.lastMouseX, this.lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
	}

	protected String blank(String text, String fallback) {
		return text == null || text.isBlank() ? fallback : text;
	}

	protected String selectionKey(ContentProjectInfo info) {
		return sanitizeAssetKey(blank(info.slug, info.title));
	}

	protected String sanitizeAssetKey(String raw) {
		if (raw == null || raw.isBlank()) {
			return "unknown";
		}
		return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	protected String normalizeMatchKey(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
	}

	protected String trimPreview(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		String trimmed = this.textRenderer.trimToWidth(text, maxWidth);
		if (trimmed.length() == text.length()) {
			return trimmed;
		}
		return this.textRenderer.trimToWidth(text, Math.max(8, maxWidth - this.textRenderer.getWidth("..."))) + "...";
	}

	protected int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	protected boolean isWithinButton(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	protected boolean isVersionLower(String installedVersion, String remoteVersion) {
		if (installedVersion == null || installedVersion.isBlank() || remoteVersion == null || remoteVersion.isBlank()) {
			return false;
		}
		if (installedVersion.equalsIgnoreCase(remoteVersion)) {
			return false;
		}
		String[] localParts = installedVersion.replaceAll("[^0-9.]", ".").split("\\.+");
		String[] remoteParts = remoteVersion.replaceAll("[^0-9.]", ".").split("\\.+");
		int max = Math.max(localParts.length, remoteParts.length);
		for (int i = 0; i < max; i++) {
			int local = parseVersionPart(localParts, i);
			int remote = parseVersionPart(remoteParts, i);
			if (local < remote) {
				return true;
			}
			if (local > remote) {
				return false;
			}
		}
		return remoteVersion.compareToIgnoreCase(installedVersion) > 0;
	}

	private int parseVersionPart(String[] parts, int index) {
		if (parts == null || index >= parts.length || parts[index] == null || parts[index].isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(parts[index]);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	protected String getSortButtonLabel() {
		return "Sort: " + this.sortMode.label;
	}

	protected String getGroupButtonLabel() {
		return "Group: " + this.groupMode.label;
	}

	protected String getViewButtonLabel() {
		return "Show: " + this.viewMode.label;
	}

	protected String getLimitButtonLabel() {
		return "Max: " + this.resultLimit;
	}

	protected void renderSelectedPreview(DrawContext context) {
		if (this.selectedInfo == null) {
			return;
		}
		ContentVersionInfo latestVersion = getLatestVersionInfo(this.selectedInfo.slug);
		String version = blank(this.selectedInfo.installedVersion, this.selectedInfo.installed ? localVersionSummary(this.selectedInfo) : "Not installed");
		String description = blank(this.selectedInfo.description, "No description available.");
		File physicalFile = findInstalledFile(this.selectedInfo.slug, this.selectedInfo.title);
		String fileName = physicalFile == null ? "Unavailable" : physicalFile.getName();
		String filePath = physicalFile == null ? blank(getLocalContextLabel(), "Unavailable") : physicalFile.getPath().replace("\\", "/");
		InstallChoice selectedChoice = getSelectedInstallChoice(this.selectedInfo);
		String remoteVersion = selectedChoice != null ? blank(selectedChoice.versionNumber(), "") : latestVersion == null ? "" : blank(latestVersion.version_number, "");
		String versionLabel = !remoteVersion.isBlank() && !remoteVersion.equals(version) ? version + "  ->  " + remoteVersion : version;
		List<ContentPreviewSection> sections = buildPreviewSections(this.selectedInfo, latestVersion, version, description, fileName, filePath, selectedChoice);
		ContentPreviewModel previewModel = new ContentPreviewModel(
				getPreviewIconIdentifier(this.selectedInfo),
				blank(this.selectedInfo.title, "Unknown"),
				getIdLabel(),
				blank(this.selectedInfo.slug, "unknown"),
				versionLabel,
				blank(this.selectedInfo.author, "Unknown"),
				getPreviewSourceLabel(this.selectedInfo),
				getPreviewSourceTooltipLines(this.selectedInfo),
				buildHeaderChips(this.selectedInfo, latestVersion),
				buildCategoryChips(this.selectedInfo, latestVersion),
				sections
		);
		ContentPreviewRenderState renderState = ContentPreviewRenderer.render(context, this.textRenderer, this.width, this.height, previewModel, this.previewScrollOffset);
		this.previewViewportX = renderState.viewportX();
		this.previewViewportY = renderState.viewportY();
		this.previewViewportWidth = renderState.viewportWidth();
		this.previewViewportHeight = renderState.viewportHeight();
		this.previewScrollMax = renderState.scrollMax();
		this.previewSourceChipX = renderState.sourceChipX();
		this.previewSourceChipY = renderState.sourceChipY();
		this.previewSourceChipWidth = renderState.sourceChipWidth();
		this.previewVersionX = renderState.versionX();
		this.previewVersionY = renderState.versionY();
		this.previewVersionWidth = renderState.versionWidth();
		this.previewVersionSplitX = renderState.versionSplitX();
		this.previewTooltipRegions = renderState.tooltipRegions();
		this.previewInteractiveRegions = renderState.interactiveRegions();
		if (this.previewScrollOffset > this.previewScrollMax) {
			this.previewScrollOffset = this.previewScrollMax;
		}
	}

	protected List<ContentPreviewSection> buildPreviewSections(ContentProjectInfo info, ContentVersionInfo latestVersion, String localVersion, String description, String fileName, String filePath, InstallChoice selectedChoice) {
		List<ContentPreviewSection> sections = new ArrayList<>();
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		if (remoteData != null && remoteData.found()) {
			List<ContentPreviewSectionRow> remoteRows = new ArrayList<>();
			remoteRows.add(new ContentPreviewSectionRow("Provider", blank(remoteData.provider(), "Modrinth"), 0xFF76E6A0));
			if (!blank(remoteData.clientSide(), "").isBlank() || !blank(remoteData.serverSide(), "").isBlank()) {
				remoteRows.add(new ContentPreviewSectionRow("Sides", blank(remoteData.clientSide(), "unknown") + " client  |  " + blank(remoteData.serverSide(), "unknown") + " server", 0xFFD7E2EF));
			}
			remoteRows.add(new ContentPreviewSectionRow("Targets", formatTargets(remoteData), 0xFFD6E5DA));
			remoteRows.add(new ContentPreviewSectionRow("Type", getDisplayProjectTypeLabel(blank(remoteData.projectType(), blank(info.project_type, getProjectTypeFacet()))), 0xFFDCE4EE));
			remoteRows.add(new ContentPreviewSectionRow("License", blank(remoteData.licenseName(), blank(info.license, "unknown")), 0xFFE4DAC8));
			if (!blank(remoteData.loaderRequirement(), "").isBlank()) {
				remoteRows.add(new ContentPreviewSectionRow("Loader", blank(remoteData.loaderRequirement(), "auto"), 0xFFD6E5DA));
			}
			remoteRows.add(new ContentPreviewSectionRow("Downloads", blank(remoteData.downloads(), blank(info.downloads, "0")), 0xFFDDE5EF));
			if (!blank(remoteData.followers(), "").isBlank()) {
				remoteRows.add(new ContentPreviewSectionRow("Followers", blank(remoteData.followers(), "0"), 0xFFD7DDF0));
			}
			remoteRows.add(new ContentPreviewSectionRow("Links", formatLinks(remoteData), 0xFFD3E3DE));
			remoteRows.add(new ContentPreviewSectionRow("Dates", formatDates(remoteData), 0xFFD9D5E8));
			remoteRows.add(new ContentPreviewSectionRow("Project", blank(remoteData.projectTitle(), info.title) + "  |  " + blank(remoteData.projectSlug(), info.slug), 0xFFF2F4F7));
			remoteRows.add(new ContentPreviewSectionRow("Author", blank(remoteData.authorName(), blank(info.author, "Unknown")), 0xFFD8E7DE));
			remoteRows.add(new ContentPreviewSectionRow("Version", remoteData.versionTitle() == null || remoteData.versionTitle().isBlank() ? blank(remoteData.versionSummary(), "") : remoteData.versionTitle(), 0xFFDCE4EE));
			List<String> remoteParagraphs = new ArrayList<>();
			if (!remoteData.exactVersion()) {
				remoteParagraphs.add("Closest available version info is being shown for this installed item.");
			}
			if (!remoteData.exactGameVersion() || !remoteData.exactLoaderMatch()) {
				remoteParagraphs.add("Compatibility was matched as closely as possible for this instance.");
			}
			sections.add(new ContentPreviewSection("Remote Data", remoteRows, remoteParagraphs));
		} else if (this.remotePreviewLoading.contains(remotePreviewCacheKey(info, this.previewSourceId))) {
			sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("Loading provider metadata...")));
		} else if ("LOCAL".equalsIgnoreCase(this.previewSourceId)) {
			sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("Remote provider preview is disabled. Local file metadata is being shown instead.")));
		} else {
			sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("No provider metadata match was found for this content item.")));
		}
		sections.addAll(buildProviderDetailSections(info, remoteData, latestVersion, localVersion, description, fileName, filePath, selectedChoice));
		if (info.installed) {
			List<ContentPreviewSectionRow> installedRows = new ArrayList<>();
			installedRows.add(new ContentPreviewSectionRow("State", installedLabel(info), info.disabled ? 0xFFD49B9B : 0xFF9BDEAE));
			installedRows.add(new ContentPreviewSectionRow("Version", localVersion, 0xFFD8DFE9));
			if (showLocalFileNamesInPreview()) {
				installedRows.add(new ContentPreviewSectionRow("File", installedFileDisplayName(fileName), 0xFFD8DFE9));
			}
			sections.add(new ContentPreviewSection("Installed Copy", installedRows, List.of()));
		}
		if (latestVersion != null) {
			sections.add(new ContentPreviewSection("Newest Version", List.of(
					new ContentPreviewSectionRow("Version", blank(latestVersion.version_number, "Unknown"), 0xFF9BDEAE),
					new ContentPreviewSectionRow("Title", blank(latestVersion.name, "Latest compatible build"), 0xFFDDE7F2),
					new ContentPreviewSectionRow("Published", blank(latestVersion.date_published, "Unknown"), 0xFFD9D5E8),
					new ContentPreviewSectionRow("Targets", formatTargets(latestVersion), 0xFFD6E5DA)
			), List.of()));
		}
		String previewDescription = remoteData != null && remoteData.found() && remoteData.body() != null && !remoteData.body().isBlank()
				? remoteData.body()
				: remoteData != null && remoteData.found() && remoteData.projectDescription() != null && !remoteData.projectDescription().isBlank()
				? remoteData.projectDescription()
				: description;
		sections.add(new ContentPreviewSection(remoteData != null && remoteData.found() ? "Description" : "Local Description", List.of(), List.of(previewDescription)));
		if (showLocalFileNamesInPreview()) {
			sections.add(new ContentPreviewSection("Local File", List.of(
					new ContentPreviewSectionRow("File", info.installed ? installedFileDisplayName(fileName) : fileName, uiColorContentBaseTitleText)
			), List.of(filePath)));
		}
		return sections;
	}

	protected void renderPreviewScrollbar(DrawContext context) {
		if (this.previewScrollMax <= 0 || this.previewViewportHeight <= 0) {
			return;
		}
		int scrollbarX = this.previewViewportX + this.previewViewportWidth - 2;
		int top = this.previewViewportY;
		int height = this.previewViewportHeight;
		context.fill(scrollbarX, top, scrollbarX + 3, top + height, 0x20374455);
		int thumbHeight = Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * height));
		int thumbTravel = Math.max(1, height - thumbHeight);
		int thumbY = top + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
		context.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0x8890A7C1);
	}

	protected boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
		int scrollbarX = this.previewViewportX + this.previewViewportWidth - 2;
		return mouseX >= scrollbarX - 3
				&& mouseX <= scrollbarX + 6
				&& mouseY >= this.previewViewportY
				&& mouseY <= this.previewViewportY + this.previewViewportHeight;
	}

	protected int previewScrollbarThumbHeight() {
		return Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
	}

	protected int previewScrollbarThumbY() {
		int thumbTravel = Math.max(1, this.previewViewportHeight - previewScrollbarThumbHeight());
		return this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
	}

	protected void setPreviewScrollFromThumbTop(int thumbTop) {
		int thumbHeight = previewScrollbarThumbHeight();
		int minTop = this.previewViewportY;
		int maxTop = this.previewViewportY + this.previewViewportHeight - thumbHeight;
		int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
		int travel = Math.max(1, maxTop - minTop);
		float ratio = (clampedTop - minTop) / (float) travel;
		this.previewScrollOffset = clamp(Math.round(ratio * this.previewScrollMax), 0, this.previewScrollMax);
	}

	protected int renderDetailChip(DrawContext context, int x, int y, String label, int background, List<Text> tooltipLines) {
		int width = this.textRenderer.getWidth(label) + 10;
		context.fill(x, y, x + width, y + 11, background);
		context.drawBorder(x, y, width, 11, 0xB06B7485);
		context.drawText(this.textRenderer, label, x + 5, y + 2, 0xFFE8EDF5, false);
		if (tooltipLines != null && !tooltipLines.isEmpty()) {
			this.previewTooltipRegions.add(new ContentPreviewTooltipRegion(x, y, width, 11, tooltipLines));
		}
		return x + width;
	}

	protected void renderPreviewSourceChip(DrawContext context) {
		String chipLabel = getPreviewSourceLabel(this.selectedInfo);
		context.fill(this.previewSourceChipX - 4, this.previewSourceChipY - 3, this.previewSourceChipX + this.previewSourceChipWidth, this.previewSourceChipY + 10, 0x27333D49);
		context.drawBorder(this.previewSourceChipX - 4, this.previewSourceChipY - 3, this.previewSourceChipWidth + 4, 13, 0x8E6A7684);
		context.drawText(this.textRenderer, "Source " + chipLabel, this.previewSourceChipX, this.previewSourceChipY, 0xFFD5DEE8, false);
	}

	protected int renderPreviewChip(DrawContext context, int x, int y, String label, int background) {
		int width = this.textRenderer.getWidth(label) + 10;
		context.fill(x, y, x + width, y + 11, background);
		context.drawBorder(x, y, width, 11, 0xB06B7485);
		context.drawText(this.textRenderer, label, x + 5, y + 2, 0xFFE8EDF5, false);
		return x + width;
	}

	protected void renderSectionRule(DrawContext context, int left, int right, int y, String title) {
		context.fill(left, y, right, y + 1, 0x50798596);
		context.drawText(this.textRenderer, title, left + 2, y + 3, 0xFFBFCAD8, false);
	}

	protected int renderPreviewInfoLine(DrawContext context, int x, int y, int panelWidth, String label, String value, int valueColor) {
		int labelWidth = PREVIEW_INFO_LABEL_WIDTH;
		int valueX = x + labelWidth;
		int valueWidth = Math.max(40, panelWidth - (valueX - x) - 18);
		int lineLeft = x - 2;
		int lineRight = x + panelWidth - 12;
		context.fill(lineLeft, y - 2, lineRight, y - 1, 0x20374455);
		context.drawText(this.textRenderer, label, x, y, 0xFF9FB1C4, false);
		context.fill(valueX - 7, y - 1, valueX - 6, y + this.textRenderer.fontHeight + 1, 0x38465567);
		List<MarkdownPreviewRenderer.Line> lines = MarkdownPreviewRenderer.wrap(blank(value, "none"), this.textRenderer, valueWidth);
		int currentY = y;
		for (MarkdownPreviewRenderer.Line line : lines) {
			int lineHeight = MarkdownPreviewRenderer.renderLine(context, this.textRenderer, new MarkdownPreviewRenderer.Line(line.rawText(), 0, valueColor, 0, MarkdownPreviewRenderer.Accent.NONE), valueX, currentY);
			currentY += lineHeight;
		}
		int rowBottom = Math.max(y + this.textRenderer.fontHeight + PREVIEW_INFO_ROW_PADDING, currentY + 1);
		context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
		return rowBottom + PREVIEW_INFO_ROW_PADDING;
	}

	protected ContentPreviewSectionRenderResult renderPreviewSections(DrawContext context, int panelX, int panelWidth, int startY, List<ContentPreviewSection> sections) {
		return ContentPreviewSectionRenderer.renderSections(context, this.textRenderer, panelX, panelWidth, startY, sections);
	}

	protected void renderPreviewHoverTooltip(DrawContext context, int mouseX, int mouseY) {
		if (MarkdownPreviewRenderer.renderLinkTooltip(context, this.textRenderer, mouseX, mouseY)) {
			return;
		}
		for (ContentPreviewTooltipRegion region : this.previewTooltipRegions) {
			if (mouseX >= region.x() && mouseX <= region.x() + region.width() && mouseY >= region.y() && mouseY <= region.y() + region.height()) {
				context.drawTooltip(this.textRenderer, region.lines(), mouseX, mouseY);
				return;
			}
		}
		if (isWithinButton(mouseX, mouseY, this.previewSourceChipX, this.previewSourceChipY, this.previewSourceChipWidth, 10)) {
			context.drawTooltip(this.textRenderer, getPreviewSourceTooltipLines(this.selectedInfo), mouseX, mouseY);
			return;
		}
		if (mouseX >= this.previewVersionX && mouseX <= this.previewVersionX + this.previewVersionWidth && mouseY >= this.previewVersionY && mouseY <= this.previewVersionY + this.textRenderer.fontHeight) {
			List<Text> lines = new ArrayList<>();
			lines.add(Text.literal("Version Source").styled(style -> style.withColor(0xFF96A9BC)));
			boolean remotePart = this.previewVersionSplitX > 0 && mouseX >= this.previewVersionSplitX;
			lines.add(Text.literal(remotePart ? getRemoteVersionSourceLabel(this.selectedInfo, getSelectedInstallChoice(this.selectedInfo)) : "Local copy").styled(style -> style.withColor(remotePart ? 0xFF8FC5FF : 0xFFE6EDF5)));
			context.drawTooltip(this.textRenderer, lines, mouseX, mouseY);
		}
	}

	protected String getRemoteVersionSourceLabel(ContentProjectInfo info, InstallChoice selectedChoice) {
		if (selectedChoice != null && selectedChoice.provider() != null && !selectedChoice.provider().isBlank()) {
			return selectedChoice.provider();
		}
		return getPreviewSourceLabel(info);
	}

	protected List<PopupMenu.MenuEntry> buildPreviewSourceEntries() {
		List<PopupMenu.MenuEntry> entries = new ArrayList<>();
		for (ContentPreviewSourceOption option : getAvailablePreviewSources(this.selectedInfo)) {
			entries.add(new PopupMenu.MenuEntry("source:" + option.id(), option.label()));
		}
		return entries;
	}

	protected void applyPreviewSourceSelection(String actionId) {
		if (actionId == null || !actionId.startsWith("source:")) {
			return;
		}
		String requested = actionId.substring("source:".length());
		boolean available = false;
		for (ContentPreviewSourceOption option : getAvailablePreviewSources(this.selectedInfo)) {
			if (Objects.equals(option.id(), requested)) {
				available = true;
				break;
			}
		}
		if (!available) {
			return;
		}
		this.previewSourceId = requested;
		clearProviderCachesForSelection(this.selectedInfo);
		this.previewTooltipRegions.clear();
		this.previewInteractiveRegions.clear();
		this.previewVersionSplitX = -1;
		updateSelectionButtons();
	}

	protected boolean handlePreviewAction(String actionId) {
		return false;
	}

	protected void clearProviderCachesForSelection(ContentProjectInfo info) {
		if (info == null) {
			return;
		}
		String selectionKey = selectionKey(info);
		this.selectedInstallChoiceCache.keySet().removeIf(key -> key.startsWith(selectionKey + ":"));
		this.compatibleInstallChoicesCache.keySet().removeIf(key -> key.startsWith(selectionKey + ":"));
		this.remotePreviewCache.keySet().removeIf(key -> key.startsWith(selectionKey + ":"));
		this.remotePreviewLoading.removeIf(key -> key.startsWith(selectionKey + ":"));
	}

	protected String remotePreviewCacheKey(ContentProjectInfo info, String sourceId) {
		return selectionKey(info) + ":" + blank(sourceId, "AUTO");
	}

	protected String sourceLabelForSourceId(String sourceId) {
		for (ContentPreviewSourceOption option : getAvailablePreviewSources(this.selectedInfo)) {
			if (Objects.equals(option.id(), sourceId)) {
				return option.label();
			}
		}
		return blank(sourceId, "Remote");
	}

	protected List<InstallChoice> fetchCompatibleInstallChoicesFromAdditionalProviders(ContentProjectInfo info) {
		for (ContentPreviewSourceOption option : getAdditionalPreviewSources(info)) {
			List<InstallChoice> choices = fetchCompatibleInstallChoicesForSource(info, option.id());
			if (!choices.isEmpty()) {
				return choices;
			}
		}
		return List.of();
	}

	protected ContentRemotePreviewData getOrFetchRemotePreview(ContentProjectInfo info) {
		if (info == null || "LOCAL".equalsIgnoreCase(this.previewSourceId)) {
			return null;
		}
		String cacheKey = remotePreviewCacheKey(info, this.previewSourceId);
		ContentRemotePreviewData cached = this.remotePreviewCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		if (this.remotePreviewLoading.add(cacheKey)) {
			CompletableFuture.supplyAsync(() -> {
				try {
					return fetchRemotePreview(info, this.previewSourceId);
				} catch (Exception exception) {
					return emptyRemotePreview(sourceLabelForSourceId(this.previewSourceId));
				}
			}).whenComplete((result, throwable) -> {
				this.remotePreviewCache.put(cacheKey, throwable == null && result != null ? result : emptyRemotePreview(sourceLabelForSourceId(this.previewSourceId)));
				this.remotePreviewLoading.remove(cacheKey);
				if (this.client != null) {
					this.client.execute(this::updateSelectionButtons);
				}
			});
		}
		return null;
	}

	protected ContentRemotePreviewData fetchRemotePreview(ContentProjectInfo info, String sourceId) throws Exception {
		return switch (blank(sourceId, "AUTO")) {
			case "LOCAL" -> null;
			case "MODRINTH" -> fetchModrinthRemotePreview(info);
			case "AUTO" -> {
				ContentRemotePreviewData modrinth = fetchModrinthRemotePreview(info);
				if (modrinth != null && modrinth.found()) {
					yield modrinth;
				}
				ContentRemotePreviewData additional = fetchRemotePreviewFromAdditionalProviders(info);
				yield additional == null ? emptyRemotePreview("Remote") : additional;
			}
			default -> fetchRemotePreviewForSource(info, sourceId);
		};
	}

	protected ContentRemotePreviewData fetchRemotePreviewFromAdditionalProviders(ContentProjectInfo info) throws Exception {
		for (ContentPreviewSourceOption option : getAdditionalPreviewSources(info)) {
			ContentRemotePreviewData preview = fetchRemotePreviewForSource(info, option.id());
			if (preview != null && preview.found()) {
				return preview;
			}
		}
		return emptyRemotePreview("Remote");
	}

	protected ContentRemotePreviewData fetchModrinthRemotePreview(ContentProjectInfo info) throws Exception {
		if (info == null) {
			return emptyRemotePreview("Modrinth");
		}
		String projectId = blank(info.slug, info.title);
		String projectTitle = blank(info.title, info.slug);
		JsonObject directProject = fetchModrinthProjectBySlug(info.slug);
		if (directProject != null) {
			String directSlug = blank(getString(directProject, "slug"), info.slug);
			JsonArray directVersions = new Gson().fromJson(
					HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(buildModrinthVersionUri(directSlug)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(),
					JsonArray.class
			);
			return buildModrinthRemotePreviewData(info, directProject, directVersions, directSlug, false);
		}
		String queryKey = blank(info.slug, info.title);
		List<JsonObject> hits = new ArrayList<>();
		hits.addAll(fetchModrinthHits(queryKey));
		if (info.title != null && !info.title.equalsIgnoreCase(queryKey)) {
			hits.addAll(fetchModrinthHits(info.title));
		}
		if (hits.isEmpty()) {
			return emptyRemotePreview("Modrinth");
		}
		JsonObject chosen = null;
		int bestScore = Integer.MIN_VALUE;
		for (JsonObject object : hits) {
			int score = scoreSearchHit(object, projectId, projectTitle);
			if (score > bestScore) {
				bestScore = score;
				chosen = object;
			}
		}
		if (chosen == null) {
			return emptyRemotePreview("Modrinth");
		}
		boolean approximateProject = bestScore < 100;
		String slug = getString(chosen, "slug");
		JsonObject project = new Gson().fromJson(HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(new URI("https://api.modrinth.com/v2/project/" + slug)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
		JsonArray versions = new Gson().fromJson(HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(buildModrinthVersionUri(slug)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(), JsonArray.class);
		return buildModrinthRemotePreviewData(info, project, versions, slug, approximateProject);
	}

	protected JsonObject fetchModrinthProjectBySlug(String slug) {
		if (slug == null || slug.isBlank()) {
			return null;
		}
		try {
			JsonObject project = new Gson().fromJson(
					HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(new URI("https://api.modrinth.com/v2/project/" + slug)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(),
					JsonObject.class
			);
			if (project == null) {
				return null;
			}
			String projectType = getString(project, "project_type");
			if (!projectType.isBlank() && !projectType.equalsIgnoreCase(getProjectTypeFacet())) {
				return null;
			}
			return project;
		} catch (Exception exception) {
			return null;
		}
	}

	protected ContentRemotePreviewData buildModrinthRemotePreviewData(ContentProjectInfo info, JsonObject project, JsonArray versions, String slug, boolean approximateProject) {
		JsonObject chosenVersion = null;
		boolean exactVersion = false;
		boolean exactGameVersion = false;
		boolean exactLoaderMatch = getModrinthLoaderFilters().isEmpty();
		String installedVersion = blank(info.installedVersion, "");
		if (versions != null) {
			for (JsonElement element : versions) {
				JsonObject object = element.getAsJsonObject();
				if (!installedVersion.isBlank() && installedVersion.equalsIgnoreCase(getString(object, "version_number"))) {
					chosenVersion = object;
					exactVersion = true;
					break;
				}
			}
			if (chosenVersion == null && !versions.isEmpty()) {
				chosenVersion = versions.get(0).getAsJsonObject();
			}
		}
		List<String> categories = readStringArray(project == null ? null : project.getAsJsonArray("categories"), 6);
		JsonObject license = project == null || !project.has("license") || !project.get("license").isJsonObject() ? null : project.getAsJsonObject("license");
		List<String> loaders = chosenVersion == null ? List.of() : readStringArray(chosenVersion.getAsJsonArray("loaders"), 10);
		List<String> gameVersions = chosenVersion == null ? List.of() : readStringArray(chosenVersion.getAsJsonArray("game_versions"), 10);
		String minecraftVersion = getMinecraftVersion();
		for (String gameVersion : gameVersions) {
			if (minecraftVersion.equalsIgnoreCase(gameVersion)) {
				exactGameVersion = true;
				break;
			}
		}
		if (gameVersions.isEmpty()) {
			exactGameVersion = true;
		}
		if (!getModrinthLoaderFilters().isEmpty()) {
			exactLoaderMatch = loaders.isEmpty() || hasConfiguredLoader(loaders);
		}
		boolean updateAvailable = chosenVersion != null
				&& !installedVersion.isBlank()
				&& exactGameVersion
				&& exactLoaderMatch
				&& !approximateProject
				&& isVersionLower(installedVersion, getString(chosenVersion, "version_number"));
		return new ContentRemotePreviewData(
				"Modrinth",
				getString(project, "title"),
				slug,
				getString(project, "description"),
				getString(project, "project_type"),
				getString(license, "name"),
				getString(project, "source_url"),
				getString(project, "issues_url"),
				getString(project, "wiki_url"),
				getString(project, "discord_url"),
				getString(project, "published"),
				getString(project, "updated"),
				blank(info.author, "Unknown"),
				chosenVersion == null ? "" : getString(chosenVersion, "name"),
				chosenVersion == null ? "" : getString(chosenVersion, "version_number"),
				chosenVersion == null ? "" : getString(chosenVersion, "version_type"),
				getString(project, "body"),
				getString(project, "downloads"),
				getString(project, "followers"),
				getString(project, "client_side"),
				getString(project, "server_side"),
				loaders.isEmpty() ? "" : String.join(", ", loaders),
				exactGameVersion,
				exactLoaderMatch,
				versions == null ? 0 : versions.size(),
				loaders,
				gameVersions,
				categories,
				exactVersion,
				approximateProject,
				updateAvailable,
				true
		);
	}

	protected URI buildModrinthVersionUri(String slug) throws Exception {
		String version = getMinecraftVersion();
		StringBuilder uri = new StringBuilder("https://api.modrinth.com/v2/project/")
				.append(slug)
				.append("/version?game_versions=%5B%22")
				.append(URLEncoder.encode(version, StandardCharsets.UTF_8))
				.append("%22%5D");
		List<String> loaders = getModrinthLoaderFilters();
		if (!loaders.isEmpty()) {
			uri.append("&loaders=%5B");
			for (int i = 0; i < loaders.size(); i++) {
				if (i > 0) {
					uri.append(",");
				}
				uri.append("%22").append(URLEncoder.encode(loaders.get(i), StandardCharsets.UTF_8)).append("%22");
			}
			uri.append("%5D");
		}
		return new URI(uri.toString());
	}

	protected String getMinecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
	}

	protected boolean hasConfiguredLoader(List<String> loaders) {
		if (loaders == null || loaders.isEmpty()) {
			return true;
		}
		boolean quiltLoaded = FabricLoader.getInstance().isModLoaded("quilt_loader");
		for (String configured : getModrinthLoaderFilters()) {
			for (String loader : loaders) {
				if (configured.equalsIgnoreCase(loader)) {
					return true;
				}
				if ("quilt".equalsIgnoreCase(configured) && quiltLoaded && "fabric".equalsIgnoreCase(loader)) {
					return true;
				}
			}
		}
		return false;
	}

	protected List<JsonObject> fetchModrinthHits(String queryText) throws Exception {
		String encodedQuery = URLEncoder.encode(blank(queryText, ""), StandardCharsets.UTF_8);
		URI uri = new URI("https://api.modrinth.com/v2/search?query=" + encodedQuery + "&limit=" + Math.min(this.resultLimit, 48) + "&facets=%5B%5B%22versions%3A" + getMinecraftVersion() + "%22%5D,%5B%22project_type%3A" + getProjectTypeFacet() + "%22%5D%5D");
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
		JsonObject root = new Gson().fromJson(response.body(), JsonObject.class);
		JsonArray hits = root == null ? null : root.getAsJsonArray("hits");
		List<JsonObject> results = new ArrayList<>();
		if (hits == null) {
			return results;
		}
		Set<String> seenProjects = ConcurrentHashMap.newKeySet();
		for (JsonElement element : hits) {
			JsonObject object = element.getAsJsonObject();
			String projectId = getString(object, "project_id");
			if (seenProjects.add(projectId.isBlank() ? getString(object, "slug") : projectId)) {
				results.add(object);
			}
		}
		return results;
	}

	protected int scoreSearchHit(JsonObject object, String projectId, String projectTitle) {
		String slug = getString(object, "slug");
		String remoteProjectId = getString(object, "project_id");
		String title = getString(object, "title");
		int score = 0;
		if (!projectId.isBlank() && slug.equalsIgnoreCase(projectId)) {
			score += 120;
		}
		if (!projectId.isBlank() && remoteProjectId.equalsIgnoreCase(projectId)) {
			score += 120;
		}
		if (!projectTitle.isBlank() && title.equalsIgnoreCase(projectTitle)) {
			score += 90;
		}
		if (!projectTitle.isBlank() && slug.equalsIgnoreCase(projectTitle)) {
			score += 80;
		}
		if (!projectId.isBlank() && title.equalsIgnoreCase(projectId)) {
			score += 80;
		}
		if (!projectId.isBlank() && slug.contains(projectId.toLowerCase(Locale.ROOT))) {
			score += 30;
		}
		if (!projectTitle.isBlank() && title.toLowerCase(Locale.ROOT).contains(projectTitle.toLowerCase(Locale.ROOT))) {
			score += 25;
		}
		return score;
	}

	protected String getString(JsonObject object, String key) {
		if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
			return "";
		}
		return object.get(key).getAsString();
	}

	protected List<String> readStringArray(JsonArray array, int limit) {
		List<String> values = new ArrayList<>();
		if (array == null) {
			return values;
		}
		for (JsonElement element : array) {
			values.add(element.getAsString());
			if (values.size() >= limit) {
				break;
			}
		}
		return values;
	}

	protected String formatTargets(ContentRemotePreviewData remoteData) {
		if (remoteData == null) {
			return "loaders unknown  |  versions unknown";
		}
		String loaderPart = remoteData.loaders() == null || remoteData.loaders().isEmpty() ? "targets unknown" : String.join(", ", remoteData.loaders());
		String versionPart = remoteData.gameVersions() == null || remoteData.gameVersions().isEmpty() ? "versions unknown" : String.join(", ", remoteData.gameVersions().subList(0, Math.min(3, remoteData.gameVersions().size())));
		return loaderPart + "  |  " + versionPart;
	}

	protected String formatTargets(ContentVersionInfo versionInfo) {
		if (versionInfo == null) {
			return "targets unknown  |  versions unknown";
		}
		String loaderPart = versionInfo.loaders == null || versionInfo.loaders.isEmpty() ? "targets unknown" : String.join(", ", versionInfo.loaders);
		String versionPart = versionInfo.game_versions == null || versionInfo.game_versions.isEmpty() ? "versions unknown" : String.join(", ", versionInfo.game_versions.subList(0, Math.min(3, versionInfo.game_versions.size())));
		return loaderPart + "  |  " + versionPart;
	}

	protected String formatDates(ContentRemotePreviewData remoteData) {
		return blank(remoteData == null ? "" : remoteData.publishedAt(), "unknown") + "  |  " + blank(remoteData == null ? "" : remoteData.updatedAt(), "unknown");
	}

	protected String formatLinks(ContentRemotePreviewData remoteData) {
		if (remoteData == null) {
			return "none";
		}
		List<String> links = new ArrayList<>();
		if (!blank(remoteData.sourceUrl(), "").isBlank()) {
			links.add("Source");
		}
		if (!blank(remoteData.issuesUrl(), "").isBlank()) {
			links.add("Issues");
		}
		if (!blank(remoteData.wikiUrl(), "").isBlank()) {
			links.add("Wiki");
		}
		if (!blank(remoteData.discordUrl(), "").isBlank()) {
			links.add("Discord");
		}
		return links.isEmpty() ? "none" : String.join("  |  ", links);
	}

	protected String joinCollection(Collection<String> values, int limit) {
		if (values == null || values.isEmpty()) {
			return "none";
		}
		return values.stream().limit(limit).reduce((left, right) -> left + ", " + right).orElse("none");
	}

	protected ContentRemotePreviewData emptyRemotePreview(String provider) {
		return new ContentRemotePreviewData(provider, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, false, false, false);
	}

	protected boolean isCurseForgeUsableForInfo(ContentProjectInfo info) {
		if (info == null || !supportsCurseForgeSource(info) || getCurseForgeApiKey().isBlank()) {
			return false;
		}
		String key = selectionKey(info);
		Boolean available = this.curseForgeAvailabilityCache.get(key);
		if (available != null) {
			return available;
		}
		queueCurseForgeAvailabilityCheck(info);
		return false;
	}

	protected void queueCurseForgeAvailabilityCheck(ContentProjectInfo info) {
		String key = selectionKey(info);
		if (this.curseForgeAvailabilityCache.containsKey(key) || !this.curseForgeAvailabilityLoading.add(key)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			boolean available;
			try {
				available = hasCurseForgeMatch(info);
			} catch (Exception exception) {
				available = false;
			}
			this.curseForgeAvailabilityCache.put(key, available);
			this.curseForgeAvailabilityLoading.remove(key);
		});
	}

	protected List<InstallChoice> fetchCompatibleCurseForgeChoices(ContentProjectInfo info) {
		if (info == null || !supportsCurseForgeSource(info) || getCurseForgeApiKey().isBlank()) {
			return List.of();
		}
		try {
			String apiKey = getCurseForgeApiKey();
			String mcVersion = getMinecraftVersion();
			String search = buildCurseForgeSearchQuery(info);
			URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=" + getCurseForgeGameId() + "&classId=" + getCurseForgeClassId() + "&searchFilter=" + search + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
			HttpRequest searchRequest = HttpRequest.newBuilder().uri(searchUri).header("x-api-key", apiKey).GET().build();
			HttpResponse<String> searchResponse = HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString());
			JsonArray data = new Gson().fromJson(searchResponse.body(), JsonObject.class).getAsJsonArray("data");
			if (data == null || data.isEmpty()) {
				return List.of();
			}
			JsonObject chosen = chooseBestCurseForgeProject(data, blank(info.slug, info.title), blank(info.title, info.slug));
			if (chosen == null) {
				return List.of();
			}
			JsonArray latestFiles = chosen.getAsJsonArray("latestFiles");
			if (latestFiles == null || latestFiles.isEmpty()) {
				return List.of();
			}
			List<InstallChoice> choices = new ArrayList<>();
			for (JsonElement element : latestFiles) {
				JsonObject file = element.getAsJsonObject();
				if (!matchesCurseForgeGameVersion(file, mcVersion)) {
					continue;
				}
				ContentFileInfo fileInfo = new ContentFileInfo();
				fileInfo.url = getString(file, "downloadUrl");
				fileInfo.filename = blank(getString(file, "fileName"), blank(info.slug, info.title) + getLocalFileExtension());
				fileInfo.primary = true;
				if (fileInfo.url == null || fileInfo.url.isBlank()) {
					continue;
				}
				String versionNumber = blank(getString(file, "displayName"), fileInfo.filename);
				String label = versionNumber + "  |  " + blank(mapCurseForgeReleaseType(file.has("releaseType") ? file.get("releaseType").getAsInt() : 0), "Build");
				choices.add(createInstallChoice("curseforge:" + choices.size(), label, fileInfo, versionNumber, null, "CurseForge"));
			}
			return choices;
		} catch (Exception exception) {
			return List.of();
		}
	}

	protected ContentRemotePreviewData fetchCurseForgePreview(ContentProjectInfo info) throws Exception {
		String apiKey = getCurseForgeApiKey();
		if (apiKey.isBlank() || !supportsCurseForgeSource(info)) {
			return emptyRemotePreview("CurseForge");
		}
		String mcVersion = getMinecraftVersion();
		String projectId = blank(info.slug, info.title);
		String projectName = blank(info.title, info.slug);
		URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=" + getCurseForgeGameId() + "&classId=" + getCurseForgeClassId() + "&searchFilter=" + buildCurseForgeSearchQuery(info) + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
		HttpRequest searchRequest = HttpRequest.newBuilder().uri(searchUri).header("x-api-key", apiKey).GET().build();
		JsonObject root = new Gson().fromJson(HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
		JsonArray data = root == null ? null : root.getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			return emptyRemotePreview("CurseForge");
		}
		JsonObject chosen = chooseBestCurseForgeProject(data, projectId, projectName);
		if (chosen == null) {
			return emptyRemotePreview("CurseForge");
		}
		JsonArray latestFiles = chosen.getAsJsonArray("latestFiles");
		JsonObject bestFile = null;
		boolean exactGameVersion = false;
		boolean exactLoaderMatch = false;
		List<String> matchedGameVersions = new ArrayList<>();
		List<String> matchedLoaders = new ArrayList<>();
		if (latestFiles != null) {
			for (JsonElement element : latestFiles) {
				JsonObject file = element.getAsJsonObject();
				if (matchesCurseForgeGameVersion(file, mcVersion)) {
					bestFile = file;
					matchedGameVersions = readCurseForgeGameVersions(file);
					matchedLoaders = readCurseForgeLoaders(file);
					exactGameVersion = true;
					exactLoaderMatch = matchedLoaders.isEmpty() || matchesAnyExpectedLoader(matchedLoaders);
					break;
				}
			}
			if (bestFile == null && latestFiles.size() > 0) {
				bestFile = latestFiles.get(0).getAsJsonObject();
				matchedGameVersions = readCurseForgeGameVersions(bestFile);
				matchedLoaders = readCurseForgeLoaders(bestFile);
				exactLoaderMatch = matchedLoaders.isEmpty() || matchesAnyExpectedLoader(matchedLoaders);
			}
		}
		String remoteVersion = bestFile == null ? "" : blank(getString(bestFile, "displayName"), getString(bestFile, "fileName"));
		boolean updateAvailable = !blank(info.installedVersion, "").isBlank() && !remoteVersion.isBlank() && isVersionLower(info.installedVersion, remoteVersion);
		String authorName = readCurseForgeFirstAuthor(chosen.getAsJsonArray("authors"));
		List<String> categories = readCurseForgeCategoryNames(chosen.getAsJsonArray("categories"), 6);
		JsonObject links = chosen.has("links") && chosen.get("links").isJsonObject() ? chosen.getAsJsonObject("links") : null;
		String changelogBody = "";
		if (bestFile != null && chosen.has("id")) {
			changelogBody = fetchCurseForgeFileChangelog(chosen.get("id").getAsInt(), bestFile.has("id") ? bestFile.get("id").getAsInt() : -1, apiKey);
		}
		String summary = getString(chosen, "summary");
		return new ContentRemotePreviewData(
				"CurseForge",
				getString(chosen, "name"),
				getString(chosen, "slug"),
				summary,
				blank(getString(chosen, "classSlug"), getProjectTypeFacet()),
				"CurseForge",
				getString(links, "sourceUrl"),
				getString(links, "issuesUrl"),
				getString(links, "wikiUrl"),
				"",
				bestFile == null ? "" : getString(bestFile, "fileDate"),
				getString(chosen, "dateModified"),
				authorName,
				remoteVersion,
				remoteVersion,
				bestFile != null && bestFile.has("releaseType") ? mapCurseForgeReleaseType(bestFile.get("releaseType").getAsInt()) : "",
				!changelogBody.isBlank() ? changelogBody : summary,
				String.valueOf(chosen.has("downloadCount") ? chosen.get("downloadCount").getAsLong() : 0L),
				String.valueOf(chosen.has("thumbsUpCount") ? chosen.get("thumbsUpCount").getAsInt() : 0),
				"",
				"",
				matchedLoaders.isEmpty() ? "auto" : String.join(", ", matchedLoaders),
				exactGameVersion,
				exactLoaderMatch,
				latestFiles == null ? 0 : latestFiles.size(),
				matchedLoaders,
				matchedGameVersions.isEmpty() ? List.of(mcVersion) : matchedGameVersions,
				categories,
				!blank(info.installedVersion, "").isBlank() && info.installedVersion.equalsIgnoreCase(remoteVersion),
				scoreCurseForgeHit(chosen, projectId, projectName) < 100,
				updateAvailable,
				true
		);
	}

	protected File installCurseForgeFile(ContentProjectInfo info) {
		try {
			String apiKey = getCurseForgeApiKey();
			if (apiKey.isBlank() || !supportsCurseForgeSource(info)) {
				return null;
			}
			String mcVersion = getMinecraftVersion();
			URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=" + getCurseForgeGameId() + "&classId=" + getCurseForgeClassId() + "&searchFilter=" + buildCurseForgeSearchQuery(info) + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
			HttpRequest searchRequest = HttpRequest.newBuilder().uri(searchUri).header("x-api-key", apiKey).GET().build();
			JsonObject root = new Gson().fromJson(HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
			JsonArray data = root == null ? null : root.getAsJsonArray("data");
			if (data == null || data.isEmpty()) {
				return null;
			}
			JsonObject chosen = chooseBestCurseForgeProject(data, blank(info.slug, info.title), blank(info.title, info.slug));
			if (chosen == null) {
				return null;
			}
			JsonArray latestFiles = chosen.getAsJsonArray("latestFiles");
			JsonObject bestFile = latestFiles == null || latestFiles.isEmpty() ? null : latestFiles.get(0).getAsJsonObject();
			if (bestFile == null) {
				return null;
			}
			ContentFileInfo fileInfo = new ContentFileInfo();
			fileInfo.url = getString(bestFile, "downloadUrl");
			fileInfo.filename = blank(getString(bestFile, "fileName"), blank(info.slug, info.title) + getLocalFileExtension());
			fileInfo.primary = true;
			return fileInfo.url == null || fileInfo.url.isBlank() ? null : downloadProjectFile(fileInfo, getContentDirectory(), blank(info.slug, info.title), getLocalFileExtension());
		} catch (Exception exception) {
			return null;
		}
	}

	protected boolean hasCurseForgeMatch(ContentProjectInfo info) throws Exception {
		String apiKey = getCurseForgeApiKey();
		if (apiKey.isBlank() || !supportsCurseForgeSource(info)) {
			return false;
		}
		String mcVersion = getMinecraftVersion();
		URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=" + getCurseForgeGameId() + "&classId=" + getCurseForgeClassId() + "&searchFilter=" + buildCurseForgeSearchQuery(info) + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
		HttpRequest searchRequest = HttpRequest.newBuilder().uri(searchUri).header("x-api-key", apiKey).GET().build();
		JsonObject root = new Gson().fromJson(HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
		JsonArray data = root == null ? null : root.getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			return false;
		}
		int bestScore = Integer.MIN_VALUE;
		for (JsonElement element : data) {
			bestScore = Math.max(bestScore, scoreCurseForgeHit(element.getAsJsonObject(), blank(info.slug, info.title), blank(info.title, info.slug)));
		}
		return bestScore >= 70;
	}

	protected JsonObject chooseBestCurseForgeProject(JsonArray data, String projectId, String projectName) {
		JsonObject chosen = null;
		int bestScore = Integer.MIN_VALUE;
		for (JsonElement element : data) {
			JsonObject object = element.getAsJsonObject();
			int score = scoreCurseForgeHit(object, projectId, projectName);
			if (score > bestScore) {
				bestScore = score;
				chosen = object;
			}
		}
		return chosen;
	}

	protected String fetchCurseForgeFileChangelog(int modId, int fileId, String apiKey) {
		if (modId <= 0 || fileId <= 0 || apiKey == null || apiKey.isBlank()) {
			return "";
		}
		try {
			URI uri = new URI("https://api.curseforge.com/v1/mods/" + modId + "/files/" + fileId + "/changelog");
			HttpRequest request = HttpRequest.newBuilder().uri(uri).header("x-api-key", apiKey).GET().build();
			JsonObject root = new Gson().fromJson(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
			return getString(root, "data");
		} catch (Exception exception) {
			return "";
		}
	}

	protected boolean matchesCurseForgeGameVersion(JsonObject file, String mcVersion) {
		JsonArray gameVersions = file == null ? null : file.getAsJsonArray("gameVersions");
		boolean gameMatch = false;
		boolean loaderMatch = getExpectedCurseForgeLoaders().isEmpty();
		if (gameVersions == null) {
			return false;
		}
		for (JsonElement element : gameVersions) {
			String value = element.getAsString();
			if (mcVersion.equalsIgnoreCase(value)) {
				gameMatch = true;
			}
			if (matchesExpectedCurseForgeLoader(value)) {
				loaderMatch = true;
			}
		}
		return gameMatch && loaderMatch;
	}

	protected List<String> readCurseForgeGameVersions(JsonObject file) {
		List<String> versions = new ArrayList<>();
		JsonArray gameVersions = file == null ? null : file.getAsJsonArray("gameVersions");
		if (gameVersions != null) {
			for (JsonElement element : gameVersions) {
				String value = element.getAsString();
				if (!matchesExpectedCurseForgeLoader(value)) {
					versions.add(value);
				}
			}
		}
		return versions;
	}

	protected List<String> readCurseForgeLoaders(JsonObject file) {
		List<String> loaders = new ArrayList<>();
		JsonArray gameVersions = file == null ? null : file.getAsJsonArray("gameVersions");
		if (gameVersions != null) {
			for (JsonElement element : gameVersions) {
				String value = element.getAsString();
				if (matchesExpectedCurseForgeLoader(value)) {
					loaders.add(value.toLowerCase(Locale.ROOT));
				}
			}
		}
		return loaders;
	}

	protected boolean matchesExpectedCurseForgeLoader(String value) {
		for (String loader : getExpectedCurseForgeLoaders()) {
			if (loader.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	protected boolean matchesAnyExpectedLoader(List<String> loaders) {
		if (loaders == null || loaders.isEmpty()) {
			return true;
		}
		for (String loader : loaders) {
			if (matchesExpectedCurseForgeLoader(loader)) {
				return true;
			}
		}
		return false;
	}

	protected List<String> getExpectedCurseForgeLoaders() {
		return List.of();
	}

	protected String readCurseForgeFirstAuthor(JsonArray authorsArray) {
		if (authorsArray == null || authorsArray.isEmpty()) {
			return "";
		}
		JsonObject authorObject = authorsArray.get(0).getAsJsonObject();
		return getString(authorObject, "name");
	}

	protected List<String> readCurseForgeCategoryNames(JsonArray categoriesArray, int limit) {
		List<String> categories = new ArrayList<>();
		if (categoriesArray == null) {
			return categories;
		}
		for (JsonElement element : categoriesArray) {
			JsonObject category = element.getAsJsonObject();
			String name = getString(category, "name");
			if (!name.isBlank()) {
				categories.add(name);
			}
			if (categories.size() >= limit) {
				break;
			}
		}
		return categories;
	}

	protected int scoreCurseForgeHit(JsonObject object, String projectId, String projectName) {
		String slug = getString(object, "slug");
		String name = getString(object, "name");
		int score = 0;
		if (!projectId.isBlank() && slug.equalsIgnoreCase(projectId)) {
			score += 120;
		}
		if (!projectName.isBlank() && name.equalsIgnoreCase(projectName)) {
			score += 90;
		}
		if (!projectName.isBlank() && slug.equalsIgnoreCase(projectName)) {
			score += 80;
		}
		if (!projectId.isBlank() && name.equalsIgnoreCase(projectId)) {
			score += 80;
		}
		if (!projectId.isBlank() && slug.contains(projectId.toLowerCase(Locale.ROOT))) {
			score += 30;
		}
		if (!projectName.isBlank() && name.toLowerCase(Locale.ROOT).contains(projectName.toLowerCase(Locale.ROOT))) {
			score += 25;
		}
		return score;
	}

	protected String mapCurseForgeReleaseType(int releaseType) {
		return switch (releaseType) {
			case 1 -> "Release";
			case 2 -> "Beta";
			case 3 -> "Alpha";
			default -> "Build";
		};
	}

	protected String buildCurseForgeSearchQuery(ContentProjectInfo info) {
		String projectId = blank(info.slug, info.title);
		String projectName = blank(info.title, info.slug);
		return URLEncoder.encode(projectId.equalsIgnoreCase(projectName) ? projectId : projectId + " " + projectName, StandardCharsets.UTF_8);
	}

	protected String getCurseForgeApiKey() {
		String env = System.getenv("CURSEFORGE_API_KEY");
		if (env != null && !env.isBlank()) {
			return env;
		}
		String property = System.getProperty("koil.curseforge.apiKey", "");
		return property == null ? "" : property;
	}
}
