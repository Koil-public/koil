package com.spirit.koil.api.registry.client;

import com.spirit.koil.api.registry.ActiveContentResourcePackSet;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ZipResourcePack;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

/** Client-only dynamic provider for active world datapack assets. */
public final class ActiveWorldContentResourcePackProvider implements ResourcePackProvider {
    private static final String FALLBACK_PROFILE = "koil_content/fallback";
    public static final ActiveWorldContentResourcePackProvider INSTANCE =
            new ActiveWorldContentResourcePackProvider();

    private volatile List<ActiveContentResourcePackSet.PackSource> sources = List.of();

    private ActiveWorldContentResourcePackProvider() {
    }

    public void replace(List<ActiveContentResourcePackSet.PackSource> nextSources) {
        sources = nextSources == null ? List.of() : List.copyOf(nextSources);
    }

    public List<ActiveContentResourcePackSet.PackSource> sources() {
        return sources;
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileConsumer) {
        List<ActiveContentResourcePackSet.PackSource> activeSources = sources;
        if (!activeSources.isEmpty()) {
            ResourcePackProfile fallback = ResourcePackProfile.create(
                    FALLBACK_PROFILE,
                    Text.literal("Content - active-world missing asset fallback"),
                    true,
                    DynamicContentFallbackResourcePack::new,
                    ResourceType.CLIENT_RESOURCES,
                    ResourcePackProfile.InsertionPosition.BOTTOM,
                    ResourcePackSource.BUILTIN
            );
            if (fallback != null) {
                profileConsumer.accept(fallback);
            }
        }
        for (ActiveContentResourcePackSet.PackSource source : activeSources) {
            ResourcePackProfile profile = ResourcePackProfile.create(
                    source.profileName(),
                    Text.literal("Content - " + source.displayName()),
                    true,
                    name -> open(name, source),
                    ResourceType.CLIENT_RESOURCES,
                    ResourcePackProfile.InsertionPosition.TOP,
                    ResourcePackSource.WORLD
            );
            if (profile != null) {
                profileConsumer.accept(profile);
            }
        }
    }

    private static ResourcePack open(
            String name,
            ActiveContentResourcePackSet.PackSource source
    ) {
        if (source.packed()) {
            return new ZipResourcePack(name, source.sourcePath().toFile(), true);
        }
        return new DirectoryResourcePack(name, source.sourcePath(), true);
    }
}
