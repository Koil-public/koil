package com.spirit.client.gui.resourcepack;

import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.nio.file.Path;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

public class ResourcepackScreen extends AbstractModrinthContentScreen {
	private static final Identifier RESOURCEPACK_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");
	private ButtonWidget inDevButton;

	public ResourcepackScreen(Screen parent) {
		super(parent);
	}

	public ResourcepackScreen(Screen parent, String initialQuery) {
		super(parent, initialQuery);
	}

	@Override
	protected String getProjectTypeFacet() {
		return "resourcepack";
	}

	@Override
	protected String getScreenTitle() {
		return "Download Resource Packs";
	}

	@Override
	protected String getSearchPlaceholder() {
		return "Search resource packs";
	}

	@Override
	protected Identifier getFallbackIcon() {
		return RESOURCEPACK_ICON;
	}

	@Override
	protected File getContentDirectory() {
		Path path = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
		return path.toFile();
	}

	public File getResourcepacksDirectory() {
		return getContentDirectory();
	}

	@Override
	protected String getLocalFileExtension() {
		return ".zip";
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
	protected Screen createReloadScreen() {
		return new ResourcepackScreen(this.parent, currentSearchQuery());
	}
}
