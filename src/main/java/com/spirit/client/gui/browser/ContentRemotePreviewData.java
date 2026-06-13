package com.spirit.client.gui.browser;

import java.util.List;

public record ContentRemotePreviewData(
		String provider,
		String projectTitle,
		String projectSlug,
		String projectDescription,
		String projectType,
		String licenseName,
		String sourceUrl,
		String issuesUrl,
		String wikiUrl,
		String discordUrl,
		String publishedAt,
		String updatedAt,
		String authorName,
		String versionTitle,
		String versionNumber,
		String versionSummary,
		String body,
		String downloads,
		String followers,
		String clientSide,
		String serverSide,
		String loaderRequirement,
		boolean exactGameVersion,
		boolean exactLoaderMatch,
		int versionCount,
		List<String> loaders,
		List<String> gameVersions,
		List<String> categories,
		boolean exactVersion,
		boolean approximateProject,
		boolean updateAvailable,
		boolean found
) {
}
