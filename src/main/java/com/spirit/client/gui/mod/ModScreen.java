package com.spirit.client.gui.mod;

import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import com.spirit.client.gui.browser.ContentPreviewSectionRow;
import com.spirit.client.gui.browser.ContentProjectInfo;
import com.spirit.client.gui.browser.ContentRemoteIconResolver;
import com.spirit.client.gui.browser.ContentRemotePreviewData;
import com.spirit.client.gui.browser.ContentVersionInfo;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector.KoilLocalModJarInsight;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.Main.uiImageModIconDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalTextureAuto;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadImage;

public class ModScreen extends AbstractModrinthContentScreen {
	private static final Identifier MOD_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");
	private ButtonWidget inDevButton;

	private record InstalledMatch(boolean installed, boolean disabled, String version, String fileName) {
	}

	private final Map<String, File> installedFileCache = new ConcurrentHashMap<>();
	private final Map<String, Optional<ModContainer>> installedContainerCache = new ConcurrentHashMap<>();
	private final Set<String> installedIconReadyKeys = ConcurrentHashMap.newKeySet();
	private final Set<String> failedInstalledIconKeys = ConcurrentHashMap.newKeySet();

	public ModScreen(Screen parent) {
		super(parent);
	}

	public ModScreen(Screen parent, String initialQuery) {
		super(parent, initialQuery);
	}

	@Override
	protected String getProjectTypeFacet() {
		return "mod";
	}

	@Override
	protected String getScreenTitle() {
		return "Download Mods";
	}

	@Override
	protected String getSearchPlaceholder() {
		return "Search mods";
	}

	@Override
	protected Identifier getFallbackIcon() {
		return MOD_ICON;
	}

	@Override
	protected File getContentDirectory() {
		return FabricLoader.getInstance().getGameDir().resolve("mods").toFile();
	}

	@Override
	protected String getLocalFileExtension() {
		return ".jar";
	}

	@Override
	protected int getKoilWebsiteFooterOffset() {
		return 2;
	}

	@Override
	protected int getKoilDeleteFooterOffset() {
		return 2;
	}

	@Override
	protected int getKoilCancelFooterOffset() {
		return 2;
	}

	@Override
	protected List<String> getModrinthLoaderFilters() {
		return List.of("fabric", "quilt");
	}

	@Override
	protected int getCurseForgeClassId() {
		return 6;
	}

	@Override
	protected boolean supportsCurseForgeSource(ContentProjectInfo info) {
		return true;
	}

	@Override
	protected List<String> getExpectedCurseForgeLoaders() {
		return List.of("Fabric", "Quilt");
	}

	@Override
	protected List<ContentProjectInfo> buildVisibleProjects() {
		List<ContentProjectInfo> visible = new ArrayList<>();
		for (ContentProjectInfo info : fetchRemoteProjects().hits()) {
			InstalledMatch match = resolveInstalledMatch(info);
			visible.add(new ModInfo(
					info.title,
					info.description,
					info.author,
					info.slug,
					info.icon_url,
					info.downloads,
					info.license,
					"Modrinth",
					false,
					match.installed(),
					match.fileName(),
					match.version(),
					match.disabled()
			));
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

	@Override
	protected boolean matchesQuery(ContentProjectInfo info, String query) {
		return contains(info.title, query)
				|| contains(info.slug, query)
				|| contains(info.author, query)
				|| contains(info.description, query)
				|| contains(info.fileName, query);
	}

	@Override
	protected void sortVisibleProjects(List<ContentProjectInfo> visible) {
		Comparator<ContentProjectInfo> comparator = switch (this.sortMode) {
			case NAME -> Comparator.comparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case DOWNLOADS -> Comparator.comparingLong((ContentProjectInfo info) -> parseDownloads(info.downloads)).reversed().thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case AUTHOR -> Comparator.comparing((ContentProjectInfo info) -> blank(info.author, "").toLowerCase(Locale.ROOT)).thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
			case RELEVANCE -> Comparator.comparing((ContentProjectInfo info) -> !info.installed).thenComparing(info -> blank(info.title, "").toLowerCase(Locale.ROOT));
		};
		visible.sort(comparator);
	}

	@Override
	protected boolean handlePreviewAction(String actionId) {
		if (!"open_downloader_update".equals(actionId)) {
			return false;
		}
		ModInfo selected = selectedMod();
		if (this.client == null || selected == null) {
			return false;
		}
		UiSoundHelper.playButtonClick();
		this.client.setScreen(new ModScreen(this.parent, blank(selected.slug, selected.title)));
		return true;
	}

	@Override
	protected boolean installSelectedProject() {
		ModInfo selected = selectedMod();
		if (selected == null) {
			return false;
		}
		InstallChoice choice = getSelectedInstallChoice(selected);
		if (choice != null && choice.file() != null) {
			File installed = downloadProjectFile(choice.file(), getContentDirectory(), blank(selected.slug, selected.title), getLocalFileExtension());
			if (installed != null) {
				return true;
			}
		}
		return super.installSelectedProject();
	}

	@Override
	protected String getReloadButtonLabel() {
		return "Restart";
	}

	@Override
	protected void init() {
		super.init();
		if (!usesKoilChrome()) {
			return;
		}
		int rightActionX = BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X;
		int rightActionWidth = BrowserLayoutHelper.FOOTER_RIGHT_ACTION_WIDTH;
		if (this.reloadButton != null) {
			this.reloadButton.visible = false;
			this.reloadButton.active = false;
		}
		if (this.cancelButton != null) {
			this.cancelButton.setX(BrowserLayoutHelper.footerSmallButtonX(3) + getKoilCancelFooterOffset());
			this.cancelButton.visible = true;
			this.cancelButton.active = true;
		}
		if (this.openFolderButton != null) {
			this.openFolderButton.setWidth(rightActionWidth);
			this.openFolderButton.setX(rightActionX);
			this.openFolderButton.setY(this.height - 52);
			this.openFolderButton.setMessage(Text.of("Open Folder"));
		}
		if (this.inDevButton != null) {
			this.remove(this.inDevButton);
		}
		this.inDevButton = this.addDrawableChild(ButtonWidget.builder(Text.of("InDev"), button -> {
		}).dimensions(rightActionX, this.height - 28, rightActionWidth, 20).build());
		this.inDevButton.active = false;
	}

	@Override
	protected void updateSelectionButtons() {
		super.updateSelectionButtons();
		if (this.reloadButton != null) {
			this.reloadButton.visible = false;
			this.reloadButton.active = false;
		}
		if (this.cancelButton != null) {
			this.cancelButton.visible = true;
			this.cancelButton.active = true;
		}
		if (this.inDevButton != null) {
			this.inDevButton.active = false;
		}
	}

	@Override
	protected void performReloadAction() {
		if (!this.reloadRequired || this.client == null) {
			return;
		}
		this.client.scheduleStop();
	}

	@Override
	protected File findInstalledFile(String slug, String title) {
		String key = sanitizeAssetKey(blank(slug, title));
		if (key.isBlank()) {
			return null;
		}
		File cached = this.installedFileCache.get(key);
		if (cached != null) {
			return cached;
		}
		File file = null;
		Optional<ModContainer> container = findInstalledContainerByNames(slug, title);
		if (container.isPresent()) {
			file = getPhysicalModFile(container.get());
		}
		if (file == null) {
			file = super.findInstalledFile(slug, title);
		}
		if (file != null) {
			this.installedFileCache.put(key, file);
		}
		return file;
	}

	@Override
	protected String localVersionSummary(ContentProjectInfo info) {
		return info == null ? "" : resolveInstalledVersion(info);
	}

	@Override
	protected boolean showLocalFileNamesInPreview() {
		return true;
	}

	@Override
	protected Identifier resolveListIconIdentifier(ContentProjectInfo info) {
		ModInfo mod = modInfo(info);
		if (mod == null) {
			return getFallbackIcon();
		}
		String localKey = sanitizeAssetKey(blank(mod.slug, mod.fileName == null ? mod.title : mod.fileName));
		Identifier local = localKey.isBlank() ? null : loadExternalTextureAuto(uiImageModIconDirectory, localKey, "webp", "png", "jpeg", "jpg");
		if (local != null) {
			return local;
		}
		Identifier installed = resolveInstalledIconIdentifier(mod);
		if (installed != null) {
			return installed;
		}
		Identifier remote = ContentRemoteIconResolver.resolve(mod.icon_url, selectionKey(mod));
		return remote != null ? remote : getFallbackIcon();
	}

	@Override
	protected Identifier getPreviewIconIdentifier(ContentProjectInfo info) {
		ModInfo mod = modInfo(info);
		if (mod == null) {
			return getFallbackIcon();
		}
		Identifier installed = resolveInstalledIconIdentifier(mod);
		if (installed != null) {
			return installed;
		}
		Identifier list = resolveListIconIdentifier(mod);
		return list != null ? list : getFallbackIcon();
	}

	@Override
	protected String getRemoteProjectUrl(ContentProjectInfo info) {
		ContentRemotePreviewData remoteData = currentRemotePreview(info);
		if (remoteData != null && remoteData.found() && "CurseForge".equalsIgnoreCase(remoteData.provider())) {
			return "https://www.curseforge.com/minecraft/mc-mods/" + blank(remoteData.projectSlug(), info.slug);
		}
		return "https://modrinth.com/mod/" + blank(remoteData == null ? info.slug : blank(remoteData.projectSlug(), info.slug), "");
	}

	@Override
	protected void openSelectedWebsite() {
		if (this.selectedInfo == null) {
			return;
		}
		Util.getOperatingSystem().open(getRemoteProjectUrl(this.selectedInfo));
	}

	@Override
	protected List<ContentPreviewSectionRow> buildLocalMetadataRows(ContentProjectInfo info, ContentRemotePreviewData remoteData, ContentVersionInfo latestVersion, String localVersion, String description, String fileName, String filePath, InstallChoice selectedChoice) {
		ModInfo mod = modInfo(info);
		ModContainer localContainer = mod == null ? null : findLocalModContainer(mod);
		KoilLocalModJarInsight localJarInsight = mod == null ? KoilLocalModJarInspector.inspect(null) : inspectLocalJar(mod);
		return List.of(
				new ContentPreviewSectionRow("Type", localContainer == null ? "unknown" : blank(localContainer.getMetadata().getType(), "unknown"), 0xFFD5DDE7),
				new ContentPreviewSectionRow("Environment", localContainer == null ? "unknown" : String.valueOf(localContainer.getMetadata().getEnvironment()), 0xFFCCD7E6),
				new ContentPreviewSectionRow("Licenses", localContainer == null ? "none" : joinCollection(localContainer.getMetadata().getLicense(), 4), 0xFFE5D9C2),
				new ContentPreviewSectionRow("Provides", localContainer == null ? "none" : joinCollection(localContainer.getMetadata().getProvides(), 4), 0xFFD2E0CF),
				new ContentPreviewSectionRow("Contributors", localContainer == null ? "none" : joinPeople(localContainer.getMetadata().getContributors(), 4), 0xFFD8DFE9),
				new ContentPreviewSectionRow("Depends", localContainer == null ? "0" : String.valueOf(localContainer.getMetadata().getDependencies().size()), 0xFFD9D4E5),
				new ContentPreviewSectionRow("Contact", localContainer == null ? "none" : formatContactInfo(localContainer), 0xFFC9DDD9),
				new ContentPreviewSectionRow("Metadata File", blank(localJarInsight.metadataFile(), "unknown"), 0xFFD8DFE9),
				new ContentPreviewSectionRow("Entrypoints", String.valueOf(localJarInsight.entrypointCount()), 0xFFDCE4EE),
				new ContentPreviewSectionRow("Mixins", String.valueOf(localJarInsight.mixinCount()), 0xFFD6E5DA),
				new ContentPreviewSectionRow("Access Wideners", String.valueOf(localJarInsight.accessWidenerCount()), 0xFFD9D4E5),
				new ContentPreviewSectionRow("Loader Hints", blank(localJarInsight.loaderHints(), "none"), 0xFFC9DDD9)
		);
	}

	private ModInfo selectedMod() {
		return modInfo(this.selectedInfo);
	}

	private ModInfo modInfo(ContentProjectInfo info) {
		return info instanceof ModInfo mod ? mod : info == null ? null : new ModInfo(info.title, info.description, info.author, info.slug, info.icon_url, info.downloads, info.license, info.sourceLabel, info.localFile, info.installed, info.fileName, info.installedVersion, info.disabled);
	}

	private InstalledMatch resolveInstalledMatch(ContentProjectInfo info) {
		File installedFile = findInstalledFile(info.slug, info.title);
		boolean disabled = installedFile != null && installedFile.getName().toLowerCase(Locale.ROOT).endsWith(".disabled");
		String installedVersion = resolveInstalledVersion(info);
		return new InstalledMatch(installedFile != null, disabled, installedVersion, installedFile == null ? "" : installedFile.getName());
	}

	private String resolveInstalledVersion(ContentProjectInfo info) {
		String slug = blank(info.slug, "").trim();
		String title = blank(info.title, "").trim();
		for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
			String id = container.getMetadata().getId();
			String name = container.getMetadata().getName();
			if ((!slug.isBlank() && id.equalsIgnoreCase(slug))
					|| (!title.isBlank() && name.equalsIgnoreCase(title))
					|| (!slug.isBlank() && name.equalsIgnoreCase(slug))) {
				return container.getMetadata().getVersion().getFriendlyString();
			}
		}
		return "";
	}

	private Identifier getInstalledIconIdentifier(ContentProjectInfo info) {
		String key = sanitizeAssetKey(blank(info.slug, info.title));
		return key.isBlank() ? null : new Identifier("koil_modscreen_installed_icon_" + key);
	}

	private Identifier resolveInstalledIconIdentifier(ContentProjectInfo info) {
		if (info == null || this.client == null) {
			return null;
		}
		Identifier installedId = getInstalledIconIdentifier(info);
		if (installedId == null) {
			return null;
		}
		String key = installedId.toString();
		if (this.installedIconReadyKeys.contains(key)) {
			return installedId;
		}
		if (this.failedInstalledIconKeys.contains(key)) {
			return null;
		}
		try {
			NativeImageBackedTexture installedIcon = loadInstalledModIcon(info);
			if (installedIcon == null) {
				this.failedInstalledIconKeys.add(key);
				return null;
			}
			this.client.getTextureManager().registerTexture(installedId, installedIcon);
			this.installedIconReadyKeys.add(key);
			return installedId;
		} catch (Exception ignored) {
			this.failedInstalledIconKeys.add(key);
			return null;
		}
	}

	private NativeImageBackedTexture loadInstalledModIcon(ContentProjectInfo info) {
		Optional<ModContainer> container = findInstalledContainerByNames(info.slug, info.title);
		if (container.isEmpty()) {
			return null;
		}
		try {
			Optional<String> iconPath = container.get().getMetadata().getIconPath(64);
			if (iconPath.isEmpty()) {
				iconPath = container.get().getMetadata().getIconPath(32);
			}
			if (iconPath.isEmpty() || container.get().getRootPaths().isEmpty()) {
				return null;
			}
			Path root = container.get().getRootPaths().get(0);
			Path iconFile = root.resolve(iconPath.get());
			return Files.exists(iconFile) ? loadImage(iconFile.toFile()) : null;
		} catch (Exception exception) {
			return null;
		}
	}

	private Optional<ModContainer> findInstalledContainerByNames(String slug, String title) {
		String key = sanitizeAssetKey(blank(slug, title));
		Optional<ModContainer> cached = this.installedContainerCache.get(key);
		if (cached != null) {
			return cached;
		}
		String lowerSlug = blank(slug, "").toLowerCase(Locale.ROOT);
		String lowerTitle = blank(title, "").toLowerCase(Locale.ROOT);
		Optional<ModContainer> resolved = Optional.empty();
		for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
			String id = blank(container.getMetadata().getId(), "").toLowerCase(Locale.ROOT);
			String name = blank(container.getMetadata().getName(), "").toLowerCase(Locale.ROOT);
			if (!lowerSlug.isBlank() && (id.equals(lowerSlug) || name.equals(lowerSlug))) {
				resolved = Optional.of(container);
				break;
			}
			if (!lowerTitle.isBlank() && (id.equals(lowerTitle) || name.equals(lowerTitle))) {
				resolved = Optional.of(container);
				break;
			}
		}
		this.installedContainerCache.put(key, resolved);
		return resolved;
	}

	private ModContainer findLocalModContainer(ModInfo info) {
		return findInstalledContainerByNames(info.slug, info.title).orElse(null);
	}

	private KoilLocalModJarInsight inspectLocalJar(ModInfo info) {
		return KoilLocalModJarInspector.inspect(findInstalledFile(info.slug, info.title));
	}

	private String joinPeople(Collection<Person> values, int limit) {
		if (values == null || values.isEmpty()) {
			return "none";
		}
		return values.stream().map(Person::getName).limit(limit).reduce((left, right) -> left + ", " + right).orElse("none");
	}

	private String formatContactInfo(ModContainer mod) {
		Map<String, String> contact = mod.getMetadata().getContact().asMap();
		if (contact == null || contact.isEmpty()) {
			return "none";
		}
		return contact.entrySet().stream().limit(3).map(Map.Entry::getKey).reduce((left, right) -> left + "  |  " + right).orElse("none");
	}

	private File getPhysicalModFile(ModContainer mod) {
		if (mod == null || mod.getOrigin() == null || mod.getOrigin().getPaths().isEmpty()) {
			return null;
		}
		Path rootPath = mod.getOrigin().getPaths().get(0);
		File rootFile = rootPath.toFile();
		if (rootFile.isFile()) {
			return rootFile;
		}
		try {
			if (rootFile.getPath().contains("!")) {
				String raw = rootFile.getPath();
				int bang = raw.indexOf('!');
				if (bang > 0) {
					return new File(raw.substring(0, bang));
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
