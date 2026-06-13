package com.spirit.client.gui.mod;

import com.spirit.client.gui.browser.ContentProjectInfo;

public class ModInfo extends ContentProjectInfo {

	public ModInfo(String title, String description, String author, String slug, String icon_url, String downloads, String license) {
		this(title, description, author, slug, icon_url, downloads, license, "Modrinth", false, false, "", "", false);
	}

	public ModInfo(String title, String description, String author, String slug, String icon_url, String downloads, String license, String sourceLabel, boolean localFile, boolean installed, String fileName) {
		this(title, description, author, slug, icon_url, downloads, license, sourceLabel, localFile, installed, fileName, "", false);
	}

	public ModInfo(String title, String description, String author, String slug, String icon_url, String downloads, String license, String sourceLabel, boolean localFile, boolean installed, String fileName, String installedVersion, boolean disabled) {
		this.title = title;
		this.description = description;
		this.author = author;
		this.slug = slug;
		this.icon_url = icon_url;
		this.downloads = downloads;
		this.license = license;
		this.sourceLabel = sourceLabel;
		this.localFile = localFile;
		this.installed = installed;
		this.fileName = fileName;
		this.installedVersion = installedVersion;
		this.disabled = disabled;
	}
}
