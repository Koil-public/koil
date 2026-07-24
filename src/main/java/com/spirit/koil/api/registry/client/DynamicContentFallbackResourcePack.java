package com.spirit.koil.api.registry.client;

import com.spirit.koil.api.registry.DynamicContentHolderRegistry;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory missing-content presentation for stable holders. It prevents
 * inactive or missing-asset holders from requiring copied per-world assets.
 */
final class DynamicContentFallbackResourcePack extends AbstractFileResourcePack {
    private static final byte[] METADATA = bytes("""
            {
              "pack": {
                "pack_format": 15,
                "description": "Koil inactive Content fallbacks"
              }
            }
            """);
    private static final byte[] ITEM_MODEL = bytes("""
            {"parent": "minecraft:item/barrier"}
            """);
    private static final byte[] BLOCK_MODEL = bytes("""
            {"parent": "minecraft:block/magenta_glazed_terracotta"}
            """);
    private static final byte[] BLOCKSTATE = bytes("""
            {
              "multipart": [
                {"apply": {"model": "minecraft:block/magenta_glazed_terracotta"}}
              ]
            }
            """);

    DynamicContentFallbackResourcePack(String name) {
        super(name, true);
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        if (segments.length == 1 && "pack.mcmeta".equals(segments[0])) {
            return supplier(METADATA);
        }
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        if (type != ResourceType.CLIENT_RESOURCES || id == null) {
            return null;
        }
        ResourceEntry entry = resolve(id);
        return entry == null ? null : supplier(entry.contents());
    }

    @Override
    public void findResources(
            ResourceType type,
            String namespace,
            String prefix,
            ResourcePack.ResultConsumer consumer
    ) {
        if (type != ResourceType.CLIENT_RESOURCES || namespace == null || consumer == null) {
            return;
        }
        for (DynamicContentHolderRegistry.Holder holder : DynamicContentHolderRegistry.holders().values()) {
            Identifier id = Identifier.tryParse(holder.id());
            if (id == null || !namespace.equals(id.getNamespace())) {
                continue;
            }
            offer(consumer, new Identifier(namespace, "models/item/" + id.getPath() + ".json"), ITEM_MODEL, prefix);
            if (holder.block() != null) {
                offer(consumer, new Identifier(namespace, "models/block/" + id.getPath() + ".json"), BLOCK_MODEL, prefix);
                offer(consumer, new Identifier(namespace, "blockstates/" + id.getPath() + ".json"), BLOCKSTATE, prefix);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        if (type != ResourceType.CLIENT_RESOURCES) {
            return Set.of();
        }
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        for (String contentId : DynamicContentHolderRegistry.contentIds()) {
            Identifier id = Identifier.tryParse(contentId);
            if (id != null) {
                namespaces.add(id.getNamespace());
            }
        }
        return Set.copyOf(namespaces);
    }

    @Override
    public void close() {
    }

    private static ResourceEntry resolve(Identifier resourceId) {
        String path = resourceId.getPath();
        if (path.startsWith("models/item/") && path.endsWith(".json")) {
            String localId = path.substring("models/item/".length(), path.length() - ".json".length());
            return holder(resourceId.getNamespace(), localId, false) ? new ResourceEntry(ITEM_MODEL) : null;
        }
        if (path.startsWith("models/block/") && path.endsWith(".json")) {
            String localId = path.substring("models/block/".length(), path.length() - ".json".length());
            return holder(resourceId.getNamespace(), localId, true) ? new ResourceEntry(BLOCK_MODEL) : null;
        }
        if (path.startsWith("blockstates/") && path.endsWith(".json")) {
            String localId = path.substring("blockstates/".length(), path.length() - ".json".length());
            return holder(resourceId.getNamespace(), localId, true) ? new ResourceEntry(BLOCKSTATE) : null;
        }
        return null;
    }

    private static boolean holder(String namespace, String path, boolean requireBlock) {
        return DynamicContentHolderRegistry.holder(namespace + ":" + path)
                .filter(candidate -> !requireBlock || candidate.block() != null)
                .isPresent();
    }

    private static void offer(
            ResourcePack.ResultConsumer consumer,
            Identifier id,
            byte[] contents,
            String prefix
    ) {
        if (prefix == null || prefix.isEmpty() || id.getPath().startsWith(prefix)) {
            consumer.accept(id, supplier(contents));
        }
    }

    private static InputSupplier<InputStream> supplier(byte[] contents) {
        return () -> new ByteArrayInputStream(contents);
    }

    private static byte[] bytes(String value) {
        return value.stripIndent().stripLeading().getBytes(StandardCharsets.UTF_8);
    }

    private record ResourceEntry(byte[] contents) {
    }
}
