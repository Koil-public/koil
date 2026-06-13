package com.spirit.client.gui.browser;

public class ContentProjectInfo {
	public String title;
	public String description;
	public String author;
	public String slug;
	public String icon_url;
	public String downloads;
	public String license;
	public String project_type;
	public String sourceLabel;
	public boolean localFile;
	public boolean installed;
	public String fileName;
	public String installedVersion;
	public boolean disabled;

	public ContentProjectInfo() {
		this("", "", "", "", "", "", "", "", false, false, "", "", false);
	}

	public ContentProjectInfo(String title, String description, String author, String slug, String icon_url, String downloads, String license) {
		this(title, description, author, slug, icon_url, downloads, license, "Modrinth", false, false, "", "", false);
	}

	public ContentProjectInfo(
			String title,
			String description,
			String author,
			String slug,
			String icon_url,
			String downloads,
			String license,
			String sourceLabel,
			boolean localFile,
			boolean installed,
			String fileName,
			String installedVersion,
			boolean disabled
	) {
		this.title = title;
		this.description = description;
		this.author = author;
		this.slug = slug;
		this.icon_url = icon_url;
		this.downloads = downloads;
		this.license = license;
		this.project_type = "";
		this.sourceLabel = sourceLabel;
		this.localFile = localFile;
		this.installed = installed;
		this.fileName = fileName;
		this.installedVersion = installedVersion;
		this.disabled = disabled;
	}
}
