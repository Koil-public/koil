package com.spirit.client.gui.datapack;

import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.Identifier;
import com.spirit.mixin.client.gui.revamp.accessor.WorldListWidgetWorldEntryAccessor;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

public class DatapackScreen extends AbstractModrinthContentScreen {
	private static final Identifier DATAPACK_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "code.png");
	private WorldListWidget.WorldEntry selectedWorld;
	private final WorldListWidget levelList;
	private final File fixedDatapacksDirectory;
	private ButtonWidget inDevButton;

	public DatapackScreen(Screen parent, WorldListWidget.WorldEntry selectedWorld, WorldListWidget levelList) {
		super(parent);
		this.selectedWorld = selectedWorld;
		this.levelList = levelList;
		this.fixedDatapacksDirectory = null;
	}

	public DatapackScreen(Screen parent, WorldListWidget.WorldEntry selectedWorld, WorldListWidget levelList, String initialQuery) {
		super(parent, initialQuery);
		this.selectedWorld = selectedWorld;
		this.levelList = levelList;
		this.fixedDatapacksDirectory = null;
	}

	public DatapackScreen(Screen parent, File datapacksDirectory) {
		super(parent);
		this.selectedWorld = null;
		this.levelList = null;
		this.fixedDatapacksDirectory = datapacksDirectory;
	}

	public DatapackScreen(Screen parent, File datapacksDirectory, String initialQuery) {
		super(parent, initialQuery);
		this.selectedWorld = null;
		this.levelList = null;
		this.fixedDatapacksDirectory = datapacksDirectory;
	}

	@Override
	protected String getProjectTypeFacet() {
		return "datapack";
	}

	@Override
	protected String getScreenTitle() {
		return "Download Data Packs";
	}

	@Override
	protected String getSearchPlaceholder() {
		return "Search data packs";
	}

	@Override
	protected Identifier getFallbackIcon() {
		return DATAPACK_ICON;
	}

	@Override
	protected File getContentDirectory() {
		if (this.client == null) {
			return null;
		}
		if (this.fixedDatapacksDirectory != null) {
			return this.fixedDatapacksDirectory;
		}
		if (this.levelList == null) {
			return null;
		}
		Optional<WorldListWidget.WorldEntry> selectedWorldOptional = this.levelList.getSelectedAsOptional();
		if (selectedWorldOptional.isPresent()) {
			this.selectedWorld = selectedWorldOptional.get();
		}
		if (this.selectedWorld == null || !this.selectedWorld.isAvailable()) {
			return null;
		}
		LevelSummary level = ((WorldListWidgetWorldEntryAccessor) (Object) this.selectedWorld).koil$getLevel();
		String folderName = level == null ? this.selectedWorld.getLevelDisplayName() : level.getName();
		Path path = this.client.getLevelStorage().getSavesDirectory().resolve(folderName).resolve("datapacks");
		return path.toFile();
	}

	public File getDatapacksDirectory() {
		return getContentDirectory();
	}

	@Override
	protected String getLocalFileExtension() {
		return ".zip";
	}

	@Override
	protected String getLocalContextLabel() {
		if (this.fixedDatapacksDirectory != null) {
			return "Folder: " + this.fixedDatapacksDirectory.getName();
		}
		return this.selectedWorld == null ? "" : "World: " + this.selectedWorld.getLevelDisplayName();
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
			this.cancelButton.setX(BrowserLayoutHelper.footerSmallButtonX(3));
			this.cancelButton.visible = true;
			this.cancelButton.active = true;
		}
		if (this.openFolderButton != null) {
			this.openFolderButton.setWidth(rightActionWidth);
			this.openFolderButton.setX(rightActionX);
			this.openFolderButton.setY(this.height - 52);
			this.openFolderButton.setMessage(net.minecraft.text.Text.of("Open Folder"));
		}
		if (this.inDevButton != null) {
			this.remove(this.inDevButton);
		}
		this.inDevButton = this.addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.of("InDev"), button -> {
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
	protected Screen createReloadScreen() {
		if (this.fixedDatapacksDirectory != null) {
			return new DatapackScreen(this.parent, this.fixedDatapacksDirectory, currentSearchQuery());
		}
		return new DatapackScreen(this.parent, this.selectedWorld, this.levelList, currentSearchQuery());
	}
}
