package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import com.spirit.koil.api.registry.definition.DefinitionParser;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.spirit.Main.SUBLOGGER;

/**
 * Registers the union of startup-discovered physical holders before vanilla registry freeze.
 * The holders are globally stable; their visibility and safe behavior remain active-world scoped.
 */
public final class DynamicContentHolderRegistry {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Map<String, Holder> HOLDERS = new LinkedHashMap<>();
    private static final Map<Item, String> CONTENT_IDS_BY_ITEM = new IdentityHashMap<>();
    private static final Map<Block, String> CONTENT_IDS_BY_BLOCK = new IdentityHashMap<>();
    private static volatile Map<Item, String> publishedItemIds = Map.of();
    private static volatile Map<Block, String> publishedBlockIds = Map.of();
    private static volatile Set<String> publishedContentIds = Set.of();

    private DynamicContentHolderRegistry() {
    }

    public static synchronized void initialize(WorldContentIndex index) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        Map<String, ContentDefinition> definitions = canonicalDefinitions(index);
        definitions.values().stream()
                .filter(definition -> "block".equals(definition.type()))
                .sorted(Comparator.comparing(ContentDefinition::id))
                .forEach(DynamicContentHolderRegistry::registerBlock);
        definitions.values().stream()
                .filter(definition -> "item".equals(definition.type()))
                .sorted(Comparator
                        .comparing((ContentDefinition definition) ->
                                "sword".equals(DefinitionValueReader.behaviorProfile(definition)))
                        .thenComparing(ContentDefinition::id))
                .forEach(DynamicContentHolderRegistry::registerItem);

        publishedItemIds = Collections.unmodifiableMap(new IdentityHashMap<>(CONTENT_IDS_BY_ITEM));
        publishedBlockIds = Collections.unmodifiableMap(new IdentityHashMap<>(CONTENT_IDS_BY_BLOCK));
        publishedContentIds = Set.copyOf(HOLDERS.keySet());
        ItemGroupEvents.MODIFY_ENTRIES_ALL.register(DynamicContentHolderRegistry::addActiveCreativeEntries);
        SUBLOGGER.logI("Content Registry", "Registered " + HOLDERS.size() + " startup physical Content holder(s).");
    }

    public static synchronized Map<String, Holder> holders() {
        return Map.copyOf(HOLDERS);
    }

    public static synchronized Optional<Holder> holder(String id) {
        return Optional.ofNullable(HOLDERS.get(id));
    }

    public static synchronized boolean hasHolder(String id) {
        return HOLDERS.containsKey(id);
    }

    public static Optional<String> contentId(Item item) {
        return Optional.ofNullable(publishedItemIds.get(item));
    }

    public static Optional<String> contentId(Block block) {
        return Optional.ofNullable(publishedBlockIds.get(block));
    }

    public static Set<String> contentIds() {
        return publishedContentIds;
    }

    /**
     * Returns active Content luminance for a managed dynamic block, or {@code -1}
     * when vanilla/modded block-state behavior must remain untouched.
     */
    public static int dynamicLuminance(BlockState state) {
        if (state != null && state.getBlock() instanceof DynamicContentBlock dynamicBlock) {
            return dynamicBlock.runtimeLuminance(state);
        }
        return -1;
    }

    public static synchronized List<String> restartRequiredDefinitions() {
        return DynamicRegistryManager.instance().activeDefinitions().keySet().stream()
                .filter(id -> !HOLDERS.containsKey(id))
                .sorted()
                .toList();
    }

    private static Map<String, ContentDefinition> canonicalDefinitions(WorldContentIndex index) {
        Map<String, ContentDefinition> definitions = new LinkedHashMap<>();
        for (WorldContentIndex.WorldEntry world : index.worlds()) {
            for (WorldContentIndex.PackEntry pack : world.packs()) {
                for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                    if (definition.activatable()
                            && ("item".equals(definition.type()) || "block".equals(definition.type()))) {
                        definitions.putIfAbsent(definition.id(), DefinitionParser.parse(definition));
                    }
                }
            }
        }
        return definitions;
    }

    private static void registerBlock(ContentDefinition definition) {
        Identifier id = Identifier.tryParse(definition.id());
        if (id == null || Registries.BLOCK.containsId(id) || Registries.ITEM.containsId(id)) {
            SUBLOGGER.logW("Content Registry", "Cannot create block holder for occupied/invalid id " + definition.id());
            return;
        }
        DynamicContentBlock block = Registry.register(Registries.BLOCK, id, new DynamicContentBlock(definition));
        BlockItem item = Registry.register(Registries.ITEM, id, new DynamicContentBlockItem(block, definition));
        HOLDERS.put(definition.id(), new Holder(definition.id(), definition.type(), item, block, definition));
        CONTENT_IDS_BY_ITEM.put(item, definition.id());
        CONTENT_IDS_BY_BLOCK.put(block, definition.id());
    }

    private static void registerItem(ContentDefinition definition) {
        Identifier id = Identifier.tryParse(definition.id());
        if (id == null || Registries.ITEM.containsId(id)) {
            SUBLOGGER.logW("Content Registry", "Cannot create item holder for occupied/invalid id " + definition.id());
            return;
        }
        Item item = "sword".equals(DefinitionValueReader.behaviorProfile(definition))
                ? new DynamicContentSwordItem(definition)
                : new DynamicContentItem(definition);
        Registry.register(Registries.ITEM, id, item);
        HOLDERS.put(definition.id(), new Holder(definition.id(), definition.type(), item, null, definition));
        CONTENT_IDS_BY_ITEM.put(item, definition.id());
    }

    private static synchronized void addActiveCreativeEntries(
            ItemGroup group,
            net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries entries
    ) {
        Identifier groupId = Registries.ITEM_GROUP.getId(group);
        if (groupId == null) {
            return;
        }
        List<CreativeEntry> active = new ArrayList<>();
        for (Holder holder : HOLDERS.values()) {
            WorldContentIndex.DefinitionEntry definition =
                    DynamicRegistryManager.instance().activeDefinitions().get(holder.id());
            if (definition == null || DefinitionValueReader.creativeHidden(definition)) {
                continue;
            }
            Identifier tabId = Identifier.tryParse(DefinitionValueReader.creativeTab(definition));
            if (groupId.equals(tabId)) {
                active.add(new CreativeEntry(holder.item(), DefinitionValueReader.creativeOrder(definition), holder.id()));
            }
        }
        active.stream()
                .sorted(Comparator.comparingInt(CreativeEntry::order).thenComparing(CreativeEntry::id))
                .forEach(entry -> entries.add(entry.item()));
    }

    public record Holder(
            String id,
            String type,
            Item item,
            Block block,
            ContentDefinition bootstrapDefinition
    ) {
    }

    private record CreativeEntry(Item item, int order, String id) {
    }
}
