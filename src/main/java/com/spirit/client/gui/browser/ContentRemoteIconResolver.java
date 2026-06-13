package com.spirit.client.gui.browser;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.registerDynamicTexture;

public final class ContentRemoteIconResolver {
	private static final Map<String, Identifier> ICON_CACHE = new ConcurrentHashMap<>();
	private static final Set<String> LOADS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
	private static final Set<String> FAILED_KEYS = ConcurrentHashMap.newKeySet();

	private ContentRemoteIconResolver() {
	}

	public static Identifier resolve(String url, String cacheKey) {
		if (url == null || url.isBlank() || cacheKey == null || cacheKey.isBlank()) {
			return null;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return null;
		}
		Identifier cached = ICON_CACHE.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		if (FAILED_KEYS.contains(cacheKey) || !LOADS_IN_FLIGHT.add(cacheKey)) {
			return null;
		}
		CompletableFuture
				.supplyAsync(() -> {
					try {
						java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder().uri(new java.net.URI(url)).GET().build();
						java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
						byte[] bytes = response.body();
						if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) {
							return null;
						}
						return bytes;
					} catch (Exception ignored) {
						return null;
					}
				})
				.whenComplete((bytes, throwable) -> {
					LOADS_IN_FLIGHT.remove(cacheKey);
					if (throwable != null || bytes == null) {
						FAILED_KEYS.add(cacheKey);
						return;
					}
					client.execute(() -> {
						try {
							Identifier id = registerDynamicTexture("koil", "content_icon/" + cacheKey, bytes);
							if (id == null) {
								FAILED_KEYS.add(cacheKey);
								return;
							}
							ICON_CACHE.put(cacheKey, id);
						} catch (Exception exception) {
							SUBLOGGER.logW("Content Browser", "Failed to decode remote content icon for " + cacheKey + ": " + exception.getMessage());
							FAILED_KEYS.add(cacheKey);
						}
					});
				});
		return null;
	}
}
