package com.spirit.client.gui.browser;

import java.util.List;

public class ContentVersionInfo {
	public String name;
	public String version_number;
	public String date_published;
	public String changelog;
	public String version_type;
	public List<String> loaders;
	public List<String> game_versions;
	public List<ContentFileInfo> files;

	public ContentVersionInfo() {
	}

	public ContentVersionInfo(List<ContentFileInfo> files) {
		this.files = files;
	}
}
