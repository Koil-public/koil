package com.spirit.koil.api.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;
import com.spirit.koil.api.f3.F3DataLine;
import com.spirit.koil.api.f3.F3Mode;
import com.spirit.koil.api.f3.F3TargetInspector;
import com.spirit.koil.api.f3.F3TargetSnapshot;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementCriterion;
import net.minecraft.block.BlockState;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bounded, read-only access to data already synchronized to the active
 * Minecraft client. The service never queries server internals or mutates the
 * world, and every snapshot is captured on the client thread.
 */
public final class MinecraftKnowledgeService {
    private static final int MAXIMUM_RESULTS = 32;
    private static final int MAXIMUM_ALTERNATIVES = 12;
    private static final int MAXIMUM_RESOURCE_BYTES = 32 * 1024;
    private static final int MAXIMUM_GRAPH_ALTERNATIVES = 16;
    private static final int MAXIMUM_GRAPH_TAGS = 32;
    private static final int MAXIMUM_CRITERION_JSON_CHARACTERS = 6_000;
    private static final int MAXIMUM_IDENTIFIER_REFERENCES = 48;
    private static final int MAXIMUM_STATIC_INDEX_FACTS = 16_384;

    /*
     * Deep mod-mechanic discovery remains bounded because this code can run
     * against very large modpacks. The semantic layer may continue an
     * investigation iteratively instead of demanding an unbounded first scan.
     */
    private static final int MAXIMUM_ARTIFACT_EVIDENCE = 48;
    private static final int MAXIMUM_ACTIVE_RESOURCES_SCANNED = 384;
    private static final int MAXIMUM_MOD_FILES_SCANNED = 2_048;
    private static final int MAXIMUM_MOD_CLASSES_SCANNED = 768;
    private static final int MAXIMUM_ARTIFACT_BYTES = 64 * 1024;
    private static final int MAXIMUM_CLASS_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_ARTIFACT_EXCERPT = 1_600;
    private static final int MAXIMUM_MATCHED_TERMS = 16;
    private static final int MAXIMUM_ARTIFACT_REFERENCES = 64;
    private static final int MAXIMUM_TOTAL_SCAN_BYTES = 12 * 1024 * 1024;

    private static final Set<String> TEXT_ARTIFACT_SUFFIXES = Set.of(
            ".json", ".json5", ".mcmeta", ".properties", ".lang", ".txt",
            ".md", ".toml", ".cfg", ".conf", ".yaml", ".yml", ".xml",
            ".accesswidener", ".mixins", ".js", ".ts", ".ktl"
    );

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "(?<![a-z0-9_.-])([a-z0-9_.-]+:[a-z0-9_./-]+)(?![a-z0-9_./-])",
            Pattern.CASE_INSENSITIVE
    );

    private MinecraftKnowledgeService() {
    }

    public static CompletableFuture<Result> query(String query, String value, String registry, int requestedLimit) {
        return query(query, value, registry, requestedLimit, List.of());
    }

    public static CompletableFuture<Result> query(
            String query,
            String value,
            String registry,
            int requestedLimit,
            List<String> requestedFields
    ) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.complete(new Result(false, new JsonObject(), "Minecraft client is unavailable."));
            return result;
        }
        String kind = normalize(query);
        String needle = value == null ? "" : value.strip();
        String registryKind = normalize(registry);
        int limit = Math.max(1, Math.min(MAXIMUM_RESULTS, requestedLimit));
        List<String> fields = normalizeFields(requestedFields);
        client.execute(() -> {
            try {
                result.complete(switch (kind) {
                    case "catalog", "capabilities" -> catalog(client);
                    case "player" -> player(client, limit, fields);
                    case "target" -> target(client, fields);
                    case "registry" -> registry(client, registryKind, needle, limit);
                    case "tag", "tags" -> tag(client, registryKind, needle, limit);
                    case "resource", "json", "resource_json" -> resource(client, needle, limit, fields);
                    case "evidence", "artifact", "artifacts", "references" ->
                            artifactEvidence(client, needle, registryKind, limit);
                    case "mod", "mods", "mod_info" -> mods(needle, limit);
                    case "recipe", "recipes" -> recipes(client, needle, limit);
                    case "advancement", "advancements" -> advancements(client, needle, limit);
                    case "block", "block_info" -> blockInfo(needle);
                    case "item", "item_info" -> itemInfo(needle);
                    case "entity", "entity_info", "creature" -> entityInfo(needle);
                    case "effect", "status_effect", "effect_info" -> effectInfo(needle);
                    case "enchantment", "enchantment_info" -> enchantmentInfo(needle);
                    case "biome", "biomes" -> dynamicRegistry(client, "biome", RegistryKeys.BIOME, needle, limit);
                    case "dimension", "dimensions" -> dynamicRegistry(
                            client,
                            "dimension_type",
                            RegistryKeys.DIMENSION_TYPE,
                            needle,
                            limit
                    );
                    case "structure", "structures" -> dynamicRegistry(
                            client,
                            "structure",
                            RegistryKeys.STRUCTURE,
                            needle,
                            limit
                    );
                    case "nbt", "snbt", "item_nbt" -> nbt(needle, limit);
                    default -> new Result(
                            false,
                            new JsonObject(),
                            "Unknown knowledge query. Use catalog, player, target, registry, tag, resource, evidence, mod, item, block, entity, effect, enchantment, biome, dimension, recipe, advancement, structure, command, or nbt."
                    );
                });
            } catch (RuntimeException failure) {
                result.complete(new Result(
                        false,
                        new JsonObject(),
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
                ));
            }
        });
        return result;
    }


    /**
     * Executes one semantic read against a client-thread-bound knowledge view.
     *
     * <p>The graph layer deliberately receives normalized records from this
     * service instead of Minecraft registry/recipe/advancement objects. This
     * keeps all direct game-data extraction in one authoritative adapter while
     * allowing semantic traversal to remain a separate concern.</p>
     */
    static <T> CompletableFuture<T> withKnowledgeView(Function<KnowledgeView, T> reader) {
        CompletableFuture<T> result = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.completeExceptionally(new IllegalStateException("Minecraft client is unavailable."));
            return result;
        }

        Runnable task = () -> {
            try {
                result.complete(reader.apply(new KnowledgeView(client)));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };

        if (client.isOnThread()) {
            task.run();
        } else {
            client.execute(task);
        }
        return result;
    }

    /**
     * Captures a bounded, client-authoritative static fact set for historical retrieval.
     * Player, target, inventory, screen, and world state are deliberately absent.
     */
    public static CompletableFuture<StaticKnowledgeSnapshot> staticSnapshotForIndexing() {
        return withKnowledgeView(view -> {
            LinkedHashMap<String, StaticKnowledgeFact> facts = new LinkedHashMap<>();
            for (RegistryEntryFact entry : view.registryEntries()) {
                addStaticFact(facts, new StaticKnowledgeFact("registry", entry.id(),
                        "Minecraft registry entry.\nKind: " + entry.kind() + "\nID: " + entry.id()
                                + "\nName: " + entry.name() + "\nSource: " + entry.source()));
            }
            for (RecipeFact recipe : view.recipes()) {
                String ingredients = recipe.ingredients().stream()
                        .map(ingredient -> ingredient.alternatives().stream().map(ItemAlternativeFact::id)
                                .reduce((left, right) -> left + ", " ).orElse("tag alternatives: " + String.join(", ", ingredient.sharedItemTags())))
                        .reduce((left, right) -> left + "; " + right).orElse("none");
                addStaticFact(facts, new StaticKnowledgeFact("recipe", recipe.id(),
                        "Minecraft recipe.\nID: " + recipe.id() + "\nOutput: " + recipe.outputId()
                                + " x" + recipe.outputCount() + "\nIngredients: " + ingredients));
            }
            for (ModFact mod : view.mods()) {
                addStaticFact(facts, new StaticKnowledgeFact("mod", mod.id(),
                        "Installed Minecraft mod.\nID: " + mod.id() + "\nName: " + mod.name()
                                + "\nVersion: " + mod.version() + "\nProvides: " + String.join(", ", mod.provides())));
            }
            List<StaticKnowledgeFact> snapshot = List.copyOf(facts.values());
            return new StaticKnowledgeSnapshot(digestStaticFacts(snapshot), snapshot);
        });
    }

    private static void addStaticFact(Map<String, StaticKnowledgeFact> facts, StaticKnowledgeFact fact) {
        if (facts.size() >= MAXIMUM_STATIC_INDEX_FACTS || fact.key().isBlank() || fact.text().isBlank()) return;
        facts.putIfAbsent(fact.kind() + '\u0000' + fact.key(), fact);
    }

    private static String digestStaticFacts(List<StaticKnowledgeFact> facts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (StaticKnowledgeFact fact : facts) {
                digest.update(fact.kind().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(fact.key().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(fact.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Client-thread-bound normalized fact view used only by the semantic graph.
     * Expensive collections are lazy and cached for one graph query.
     */
    static final class KnowledgeView {
        private final MinecraftClient client;
        private List<RegistryEntryFact> registryEntries;
        private List<RecipeFact> recipes;
        private List<AdvancementFact> advancements;
        private List<ModFact> mods;
        private JsonObject coverage;

        private KnowledgeView(MinecraftClient client) {
            this.client = client;
        }

        List<RegistryEntryFact> registryEntries() {
            if (registryEntries == null) {
                registryEntries = registryEntryFacts(client);
            }
            return registryEntries;
        }

        List<RecipeFact> recipes() {
            if (recipes == null) {
                recipes = recipeFacts(client);
            }
            return recipes;
        }

        List<AdvancementFact> advancements() {
            if (advancements == null) {
                advancements = advancementFacts(client);
            }
            return advancements;
        }

        List<ModFact> mods() {
            if (mods == null) {
                mods = modFacts();
            }
            return mods;
        }

        SubjectFact subject(String kind, String exactId) {
            return subjectFact(client, kind, exactId);
        }

        ModFact modForNamespace(String namespace) {
            if (namespace == null || namespace.isBlank()) {
                return null;
            }
            for (ModFact mod : mods()) {
                if (namespace.equals(mod.id()) || mod.provides().contains(namespace)) {
                    return mod;
                }
            }
            return null;
        }

        List<ArtifactEvidenceFact> artifactEvidence(
                String subject,
                String namespaceHint,
                int requestedLimit
        ) {
            return artifactEvidenceFacts(client, subject, namespaceHint, requestedLimit);
        }

        JsonObject coverage() {
            if (coverage == null) {
                coverage = coverageFacts(client);
            }
            return coverage.deepCopy();
        }

        boolean recipesAvailable() {
            return client.getNetworkHandler() != null && client.world != null;
        }

        boolean advancementsAvailable() {
            return client.getNetworkHandler() != null;
        }

        boolean dynamicRegistriesAvailable() {
            return client.getNetworkHandler() != null;
        }
    }

    static List<RegistryEntryFact> registryEntryFacts(MinecraftClient client) {
        LinkedHashMap<String, RegistryEntryFact> facts = new LinkedHashMap<>();

        addRegistryFacts(
                facts,
                "item",
                Registries.ITEM,
                "static_registry:minecraft:item",
                id -> {
                    Item item = Registries.ITEM.get(id);
                    ItemStack stack = item.getDefaultStack();
                    return stack.isEmpty() ? id.getPath() : stack.getName().getString();
                }
        );
        addRegistryFacts(
                facts,
                "block",
                Registries.BLOCK,
                "static_registry:minecraft:block",
                id -> Registries.BLOCK.get(id).getName().getString()
        );
        addRegistryFacts(
                facts,
                "entity_type",
                Registries.ENTITY_TYPE,
                "static_registry:minecraft:entity_type",
                id -> Registries.ENTITY_TYPE.get(id).getName().getString()
        );
        addRegistryFacts(
                facts,
                "status_effect",
                Registries.STATUS_EFFECT,
                "static_registry:minecraft:mob_effect",
                id -> Registries.STATUS_EFFECT.get(id).getName().getString()
        );
        addRegistryFacts(
                facts,
                "enchantment",
                Registries.ENCHANTMENT,
                "static_registry:minecraft:enchantment",
                id -> {
                    Enchantment enchantment = Registries.ENCHANTMENT.get(id);
                    return enchantment.getName(enchantment.getMinLevel()).getString();
                }
        );
        addRegistryFacts(
                facts,
                "sound_event",
                Registries.SOUND_EVENT,
                "static_registry:minecraft:sound_event",
                Identifier::toString
        );

        for (Identifier registryId : Registries.REGISTRIES.getIds()) {
            Registry<?> registry;
            try {
                registry = Registries.REGISTRIES.get(registryId);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (registry == null) {
                continue;
            }
            String kind = registryKind(registryId);
            String source = "static_registry:" + registryId;
            for (Identifier id : registry.getIds()) {
                putRegistryFact(facts, new RegistryEntryFact(
                        kind,
                        id.toString(),
                        id.getPath(),
                        source
                ));
            }
        }

        if (client != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().getRegistryManager().streamAllRegistries().forEach(entry -> {
                Identifier registryId = entry.key().getValue();
                String kind = registryKind(registryId);
                String source = "synchronized_registry:" + registryId;
                for (Identifier id : entry.value().getIds()) {
                    putRegistryFact(facts, new RegistryEntryFact(
                            kind,
                            id.toString(),
                            id.getPath(),
                            source
                    ));
                }
            });
        }

        return List.copyOf(facts.values());
    }

    private static <T> void addRegistryFacts(
            Map<String, RegistryEntryFact> target,
            String kind,
            Registry<T> registry,
            String source,
            Function<Identifier, String> displayName
    ) {
        for (Identifier id : registry.getIds()) {
            String name;
            try {
                name = displayName.apply(id);
            } catch (RuntimeException ignored) {
                name = id.getPath();
            }
            putRegistryFact(target, new RegistryEntryFact(
                    kind,
                    id.toString(),
                    cleanFactText(name, 400),
                    source
            ));
        }
    }

    private static void putRegistryFact(
            Map<String, RegistryEntryFact> target,
            RegistryEntryFact fact
    ) {
        String key = fact.kind() + "|" + fact.id();
        RegistryEntryFact existing = target.get(key);
        if (existing == null || registrySourcePriority(fact.source()) < registrySourcePriority(existing.source())) {
            target.put(key, fact);
        }
    }

    private static int registrySourcePriority(String source) {
        if (source == null) return 9;
        if (source.startsWith("static_registry:minecraft:item")
                || source.startsWith("static_registry:minecraft:block")
                || source.startsWith("static_registry:minecraft:entity_type")
                || source.startsWith("static_registry:minecraft:mob_effect")
                || source.startsWith("static_registry:minecraft:enchantment")
                || source.startsWith("static_registry:minecraft:sound_event")) {
            return 0;
        }
        if (source.startsWith("synchronized_registry:")) return 1;
        if (source.startsWith("static_registry:")) return 2;
        return 5;
    }

    static List<RecipeFact> recipeFacts(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null || client.world == null) {
            return List.of();
        }

        List<RecipeFact> facts = new ArrayList<>();
        for (Recipe<?> recipe : client.getNetworkHandler().getRecipeManager().values()) {
            ItemStack output = recipe.getOutput(client.world.getRegistryManager());
            List<IngredientFact> ingredients = new ArrayList<>();
            Map<String, Integer> exactTotals = new LinkedHashMap<>();
            boolean exactTotalsComplete = true;
            int slot = 0;

            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }

                ItemStack[] matching = ingredient.getMatchingStacks();
                List<ItemAlternativeFact> alternatives = new ArrayList<>();
                LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();

                for (int index = 0; index < matching.length && index < MAXIMUM_GRAPH_ALTERNATIVES; index++) {
                    ItemStack stack = matching[index];
                    String itemId = stackId(stack);
                    if (itemId.isBlank()) {
                        continue;
                    }
                    uniqueIds.add(itemId);
                    alternatives.add(new ItemAlternativeFact(
                            itemId,
                            stack.getName().getString(),
                            Math.max(1, stack.getCount())
                    ));
                }

                List<String> sharedTags = sharedItemTags(matching, MAXIMUM_GRAPH_TAGS);
                ingredients.add(new IngredientFact(
                        slot++,
                        matching.length,
                        List.copyOf(alternatives),
                        sharedTags,
                        matching.length > MAXIMUM_GRAPH_ALTERNATIVES
                ));

                if (matching.length <= MAXIMUM_GRAPH_ALTERNATIVES && uniqueIds.size() == 1) {
                    exactTotals.merge(uniqueIds.iterator().next(), 1, Integer::sum);
                } else {
                    exactTotalsComplete = false;
                }
            }

            facts.add(new RecipeFact(
                    recipe.getId().toString(),
                    idOf(Registries.RECIPE_TYPE, recipe.getType()),
                    idOf(Registries.RECIPE_SERIALIZER, recipe.getSerializer()),
                    recipe.getGroup(),
                    stackId(output),
                    output.isEmpty() ? "" : output.getName().getString(),
                    Math.max(0, output.getCount()),
                    List.copyOf(ingredients),
                    Map.copyOf(exactTotals),
                    exactTotalsComplete
            ));
        }

        facts.sort(Comparator.comparing(RecipeFact::id));
        return List.copyOf(facts);
    }

    static List<AdvancementFact> advancementFacts(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return List.of();
        }

        Map<Advancement, net.minecraft.advancement.AdvancementProgress> progress;
        try {
            progress = com.spirit.koil.api.automation.AutomationCompletionModeController
                    .advancementProgressSnapshot(client);
        } catch (RuntimeException ignored) {
            progress = Map.of();
        }

        List<AdvancementFact> facts = new ArrayList<>();
        for (Advancement advancement : client.getNetworkHandler()
                .getAdvancementHandler()
                .getManager()
                .getAdvancements()) {
            String title = "";
            String description = "";
            String frame = "";
            String icon = "";
            String background = "";
            boolean hidden = false;
            boolean showToast = false;
            boolean announceToChat = false;

            if (advancement.getDisplay() != null) {
                title = advancement.getDisplay().getTitle().getString();
                description = advancement.getDisplay().getDescription().getString();
                hidden = advancement.getDisplay().isHidden();
                frame = advancement.getDisplay().getFrame().getId();
                icon = stackId(advancement.getDisplay().getIcon());
                showToast = advancement.getDisplay().shouldShowToast();
                announceToChat = advancement.getDisplay().shouldAnnounceToChat();
                background = advancement.getDisplay().getBackground() == null
                        ? ""
                        : advancement.getDisplay().getBackground().toString();
            }

            List<String> children = new ArrayList<>();
            for (Advancement child : advancement.getChildren()) {
                children.add(child.getId().toString());
            }
            children.sort(String::compareTo);

            List<List<String>> requirements = new ArrayList<>();
            for (String[] group : advancement.getRequirements()) {
                requirements.add(List.of(group.clone()));
            }

            JsonObject criteria = new JsonObject();
            LinkedHashSet<String> identifierReferences = new LinkedHashSet<>();
            for (Map.Entry<String, AdvancementCriterion> entry : advancement.getCriteria().entrySet()) {
                try {
                    JsonElement json = entry.getValue().toJson();
                    String serialized = json.toString();
                    collectIdentifiers(serialized, identifierReferences);
                    if (serialized.length() <= MAXIMUM_CRITERION_JSON_CHARACTERS) {
                        criteria.add(entry.getKey(), json.deepCopy());
                    } else {
                        JsonObject bounded = new JsonObject();
                        bounded.addProperty("available", true);
                        bounded.addProperty("truncated", true);
                        bounded.addProperty("characterCount", serialized.length());
                        criteria.add(entry.getKey(), bounded);
                    }
                } catch (RuntimeException failure) {
                    JsonObject unavailable = new JsonObject();
                    unavailable.addProperty("available", false);
                    unavailable.addProperty("problem", failure.getClass().getSimpleName());
                    criteria.add(entry.getKey(), unavailable);
                }
            }

            boolean completed = progress.containsKey(advancement)
                    && progress.get(advancement) != null
                    && progress.get(advancement).isDone();

            facts.add(new AdvancementFact(
                    advancement.getId().toString(),
                    title,
                    description,
                    hidden,
                    frame,
                    icon,
                    showToast,
                    announceToChat,
                    background,
                    advancement.getParent() == null
                            ? ""
                            : advancement.getParent().getId().toString(),
                    children,
                    requirements,
                    advancement.getRequirementCount(),
                    completed,
                    criteria,
                    identifierReferences.stream()
                            .limit(MAXIMUM_IDENTIFIER_REFERENCES)
                            .toList(),
                    identifierReferences.size() > MAXIMUM_IDENTIFIER_REFERENCES
            ));
        }

        facts.sort(Comparator.comparing(AdvancementFact::id));
        return List.copyOf(facts);
    }

    static List<ModFact> modFacts() {
        List<ModFact> facts = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String description = mod.getMetadata().getDescription();
            facts.add(new ModFact(
                    mod.getMetadata().getId(),
                    mod.getMetadata().getName(),
                    mod.getMetadata().getVersion().getFriendlyString(),
                    mod.getMetadata().getEnvironment().toString(),
                    description == null ? "" : cleanFactText(description, 700),
                    mod.getMetadata().getAuthors().stream()
                            .limit(12)
                            .map(person -> person.getName())
                            .toList(),
                    mod.getMetadata().getLicense().stream().limit(12).toList(),
                    mod.getMetadata().getProvides().stream().limit(32).toList()
            ));
        }
        facts.sort(Comparator.comparing(ModFact::id));
        return List.copyOf(facts);
    }

    static SubjectFact subjectFact(MinecraftClient client, String requestedKind, String exactId) {
        Identifier id = Identifier.tryParse(exactId == null ? "" : exactId);
        if (id == null) {
            return null;
        }

        String kind = canonicalRegistryKind(requestedKind);
        JsonObject attributes = new JsonObject();
        List<String> tags = List.of();
        String itemFormId = "";
        String blockFormId = "";
        List<EnchantmentLinkFact> applicableEnchantments = List.of();
        List<String> acceptedItems = List.of();
        int acceptedItemCount = 0;
        boolean acceptedItemsTruncated = false;
        String name = id.getPath();
        String source = "registry";

        try {
            switch (kind) {
                case "item" -> {
                    if (!Registries.ITEM.containsId(id)) return null;
                    Item item = Registries.ITEM.get(id);
                    ItemStack stack = item.getDefaultStack();
                    name = stack.isEmpty() ? item.getTranslationKey(stack) : stack.getName().getString();
                    attributes.addProperty("translationKey", item.getTranslationKey(stack));
                    attributes.addProperty("maximumCount", item.getMaxCount());
                    attributes.addProperty("maximumDamage", item.getMaxDamage());
                    attributes.addProperty("damageable", item.isDamageable());
                    attributes.addProperty("fireproof", item.isFireproof());
                    attributes.addProperty("rarity", item.getRarity(stack).name().toLowerCase(Locale.ROOT));
                    attributes.addProperty("enchantable", item.isEnchantable(stack));
                    attributes.addProperty("enchantability", item.getEnchantability());
                    attributes.addProperty("useAction", item.getUseAction(stack).name().toLowerCase(Locale.ROOT));
                    attributes.addProperty("maximumUseTicks", item.getMaxUseTime(stack));
                    attributes.addProperty("food", item.isFood());
                    if (item.getFoodComponent() != null) {
                        JsonObject food = new JsonObject();
                        food.addProperty("hunger", item.getFoodComponent().getHunger());
                        food.addProperty("saturationModifier", item.getFoodComponent().getSaturationModifier());
                        food.addProperty("meat", item.getFoodComponent().isMeat());
                        food.addProperty("alwaysEdible", item.getFoodComponent().isAlwaysEdible());
                        food.addProperty("snack", item.getFoodComponent().isSnack());
                        attributes.add("foodData", food);
                    }
                    tags = tagsFor(Registries.ITEM, item, MAXIMUM_GRAPH_TAGS);

                    Block block = Block.getBlockFromItem(item);
                    Identifier relatedBlock = Registries.BLOCK.getId(block);
                    if (relatedBlock != null && !"minecraft:air".equals(relatedBlock.toString())) {
                        blockFormId = relatedBlock.toString();
                    }

                    List<Identifier> acceptedEnchantments = new ArrayList<>();
                    for (Identifier enchantmentId : Registries.ENCHANTMENT.getIds()) {
                        Enchantment enchantment = Registries.ENCHANTMENT.get(enchantmentId);
                        boolean accepted;
                        try {
                            accepted = enchantment.isAcceptableItem(stack);
                        } catch (RuntimeException ignored) {
                            accepted = false;
                        }
                        if (accepted) acceptedEnchantments.add(enchantmentId);
                    }
                    List<EnchantmentLinkFact> links = new ArrayList<>();
                    for (Identifier enchantmentId : acceptedEnchantments) {
                        Enchantment enchantment = Registries.ENCHANTMENT.get(enchantmentId);
                        List<String> conflicts = new ArrayList<>();
                        for (Identifier otherId : acceptedEnchantments) {
                            if (enchantmentId.equals(otherId)) continue;
                            Enchantment other = Registries.ENCHANTMENT.get(otherId);
                            boolean compatible;
                            try {
                                compatible = enchantment.canCombine(other);
                            } catch (RuntimeException ignored) {
                                compatible = true;
                            }
                            if (!compatible) conflicts.add(otherId.toString());
                        }
                        links.add(new EnchantmentLinkFact(
                                enchantmentId.toString(),
                                enchantment.getMinLevel(),
                                enchantment.getMaxLevel(),
                                enchantment.isTreasure(),
                                enchantment.isCursed(),
                                List.copyOf(conflicts)
                        ));
                    }
                    applicableEnchantments = List.copyOf(links);
                }
                case "block" -> {
                    if (!Registries.BLOCK.containsId(id)) return null;
                    Block block = Registries.BLOCK.get(id);
                    BlockState state = block.getDefaultState();
                    name = block.getName().getString();
                    attributes.addProperty("translationKey", block.getTranslationKey());
                    attributes.addProperty("blastResistance", block.getBlastResistance());
                    attributes.addProperty("luminance", state.getLuminance());
                    attributes.addProperty("air", state.isAir());
                    attributes.addProperty("defaultState", state.toString());
                    JsonObject properties = new JsonObject();
                    state.getProperties().forEach(property -> {
                        JsonArray values = new JsonArray();
                        property.getValues().forEach(value -> values.add(String.valueOf(value)));
                        properties.add(property.getName(), values);
                    });
                    attributes.add("properties", properties);
                    tags = tagsFor(Registries.BLOCK, block, MAXIMUM_GRAPH_TAGS);

                    Item item = block.asItem();
                    Identifier relatedItem = Registries.ITEM.getId(item);
                    if (relatedItem != null && !"minecraft:air".equals(relatedItem.toString())) {
                        itemFormId = relatedItem.toString();
                    }
                }
                case "entity_type" -> {
                    if (!Registries.ENTITY_TYPE.containsId(id)) return null;
                    EntityType<?> type = Registries.ENTITY_TYPE.get(id);
                    name = type.getName().getString();
                    attributes.addProperty("translationKey", type.getTranslationKey());
                    attributes.addProperty("spawnGroup", type.getSpawnGroup().getName());
                    attributes.addProperty("width", type.getWidth());
                    attributes.addProperty("height", type.getHeight());
                    attributes.addProperty("summonable", type.isSummonable());
                    attributes.addProperty("fireImmune", type.isFireImmune());
                    attributes.addProperty("saveable", type.isSaveable());
                    tags = tagsFor(Registries.ENTITY_TYPE, type, MAXIMUM_GRAPH_TAGS);
                }
                case "status_effect" -> {
                    if (!Registries.STATUS_EFFECT.containsId(id)) return null;
                    StatusEffect effect = Registries.STATUS_EFFECT.get(id);
                    name = effect.getName().getString();
                    attributes.addProperty("translationKey", effect.getTranslationKey());
                    attributes.addProperty("category", effect.getCategory().name().toLowerCase(Locale.ROOT));
                    attributes.addProperty("color", String.format(Locale.ROOT, "#%06X", effect.getColor() & 0x00FFFFFF));
                    attributes.addProperty("beneficial", effect.isBeneficial());
                    tags = tagsFor(Registries.STATUS_EFFECT, effect, MAXIMUM_GRAPH_TAGS);
                }
                case "enchantment" -> {
                    if (!Registries.ENCHANTMENT.containsId(id)) return null;
                    Enchantment enchantment = Registries.ENCHANTMENT.get(id);
                    name = enchantment.getName(enchantment.getMinLevel()).getString();
                    attributes.addProperty("translationKey", enchantment.getTranslationKey());
                    attributes.addProperty("rarity", enchantment.getRarity().name().toLowerCase(Locale.ROOT));
                    attributes.addProperty("target", enchantment.target.name().toLowerCase(Locale.ROOT));
                    attributes.addProperty("minimumLevel", enchantment.getMinLevel());
                    attributes.addProperty("maximumLevel", enchantment.getMaxLevel());
                    attributes.addProperty("treasure", enchantment.isTreasure());
                    attributes.addProperty("cursed", enchantment.isCursed());
                    JsonArray conflicts = new JsonArray();
                    for (Identifier otherId : Registries.ENCHANTMENT.getIds()) {
                        if (id.equals(otherId)) continue;
                        Enchantment other = Registries.ENCHANTMENT.get(otherId);
                        boolean compatible;
                        try {
                            compatible = enchantment.canCombine(other);
                        } catch (RuntimeException ignored) {
                            compatible = true;
                        }
                        if (!compatible) conflicts.add(otherId.toString());
                    }
                    attributes.add("conflictsWith", conflicts);
                    tags = tagsFor(Registries.ENCHANTMENT, enchantment, MAXIMUM_GRAPH_TAGS);

                    List<String> matches = new ArrayList<>();
                    int total = 0;
                    for (Identifier itemId : Registries.ITEM.getIds()) {
                        ItemStack stack = Registries.ITEM.get(itemId).getDefaultStack();
                        boolean accepted;
                        try {
                            accepted = enchantment.isAcceptableItem(stack);
                        } catch (RuntimeException ignored) {
                            accepted = false;
                        }
                        if (!accepted) continue;
                        total++;
                        if (matches.size() < 64) {
                            matches.add(itemId.toString());
                        }
                    }
                    acceptedItems = List.copyOf(matches);
                    acceptedItemCount = total;
                    acceptedItemsTruncated = total > matches.size();
                }
                default -> {
                    RegistryEntryFact generic = registryEntryFacts(client).stream()
                            .filter(entry -> canonicalRegistryKind(entry.kind()).equals(kind))
                            .filter(entry -> entry.id().equals(id.toString()))
                            .findFirst()
                            .orElse(null);
                    if (generic == null) {
                        return null;
                    }
                    name = generic.name();
                    source = generic.source();
                }
            }
        } catch (RuntimeException failure) {
            return null;
        }

        return new SubjectFact(
                kind,
                id.toString(),
                name,
                id.getNamespace(),
                source,
                attributes,
                tags,
                itemFormId,
                blockFormId,
                applicableEnchantments,
                acceptedItems,
                acceptedItemCount,
                acceptedItemsTruncated
        );
    }

    static boolean likelyMinecraftSubjectFromFacts(String prompt) {
        String normalized = normalizeSearchText(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAnyWord(
                normalized,
                "minecraft", "craft", "crafting", "recipe", "advancement",
                "biome", "dimension", "structure", "entity", "mob", "block",
                "item", "enchantment", "effect", "nbt", "snbt", "datapack",
                "modded", "registry", "tag"
        )) {
            return true;
        }

        List<String> tokens = semanticTokens(normalized);
        if (tokens.isEmpty()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        boolean clientThread = client != null && client.isOnThread();
        List<RegistryEntryFact> registryFacts = registryEntryFacts(clientThread ? client : null);

        for (String token : tokens) {
            Identifier parsed = Identifier.tryParse(token);
            if (parsed != null && registryFacts.stream().anyMatch(
                    fact -> fact.id().equals(parsed.toString())
            )) {
                return true;
            }

            if (token.length() < 3) {
                continue;
            }

            boolean exactRegistryMatch = registryFacts.stream().anyMatch(fact ->
                    idPathOf(fact.id()).equals(token)
                            || normalizeSearchText(fact.name()).equals(token)
                            || FuzzyTextMatcher.score(token, idPathOf(fact.id())) >= 825
                            || FuzzyTextMatcher.score(token, fact.name()) >= 825
            );
            if (exactRegistryMatch) {
                return true;
            }
        }

        if (clientThread && client.getNetworkHandler() != null) {
            String semanticSubject = String.join(" ", tokens);
            for (AdvancementFact advancement : advancementFacts(client)) {
                if (semanticContains(advancement.id(), semanticSubject)
                        || semanticContains(advancement.title(), semanticSubject)
                        || semanticContains(advancement.description(), semanticSubject)
                        || advancement.identifierReferences().stream()
                        .anyMatch(reference -> semanticContains(reference, semanticSubject))) {
                    return true;
                }
            }

            for (RecipeFact recipe : recipeFacts(client)) {
                if (semanticContains(recipe.id(), semanticSubject)
                        || semanticContains(recipe.outputId(), semanticSubject)
                        || semanticContains(recipe.outputName(), semanticSubject)) {
                    return true;
                }
            }
        }

        return false;
    }


    /**
     * Pulls bounded implementation evidence around a Minecraft/modded subject.
     *
     * <p>This deliberately combines two different evidence classes:</p>
     * <ul>
     *     <li>active client resources, which describe the resource stack
     *     actually visible to this client; and</li>
     *     <li>files packaged inside the installed mod, including bounded UTF-8
     *     text and class-file constant-pool strings.</li>
     * </ul>
     *
     * <p>Packaged artifacts are useful for discovering relationships that are
     * not represented by recipes/advancements/registries, but they are not
     * automatically treated as proof that a runtime branch executed. The
     * semantic layer preserves this provenance so Deep Thought can test or
     * verify important conclusions.</p>
     */
    static List<ArtifactEvidenceFact> artifactEvidenceFacts(
            MinecraftClient client,
            String query,
            String namespaceHint,
            int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(MAXIMUM_ARTIFACT_EVIDENCE, requestedLimit));
        List<String> terms = evidenceTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        String namespace = resolveEvidenceNamespace(query, namespaceHint);
        LinkedHashMap<String, ArtifactEvidenceFact> facts = new LinkedHashMap<>();

        scanActiveResources(client, terms, namespace, facts, limit);
        if (facts.size() < limit) {
            scanInstalledModArtifacts(terms, namespace, facts, limit);
        }

        List<ArtifactEvidenceFact> ordered = new ArrayList<>(facts.values());
        ordered.sort(
                Comparator.comparingInt(ArtifactEvidenceFact::score)
                        .reversed()
                        .thenComparing(ArtifactEvidenceFact::sourceKind)
                        .thenComparing(ArtifactEvidenceFact::location)
        );
        if (ordered.size() > limit) {
            return List.copyOf(ordered.subList(0, limit));
        }
        return List.copyOf(ordered);
    }

    private static Result artifactEvidence(
            MinecraftClient client,
            String query,
            String namespaceHint,
            int limit
    ) {
        List<ArtifactEvidenceFact> facts =
                artifactEvidenceFacts(client, query, namespaceHint, limit);
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("namespaceHint", namespaceHint == null ? "" : namespaceHint);
        output.addProperty("matchCount", facts.size());
        output.addProperty("bounded", true);
        output.addProperty(
                "evidenceBoundary",
                "active resources are client-visible evidence; installed-mod resources and class constants are packaged implementation clues and require runtime verification for behavior claims"
        );
        JsonArray rows = new JsonArray();
        facts.forEach(fact -> rows.add(artifactEvidenceJson(fact)));
        output.add("evidence", rows);
        return success(
                output,
                facts.isEmpty()
                        ? "No bounded client-visible or installed-mod artifact evidence matched the subject."
                        : "Bounded active-resource and installed-mod implementation evidence was collected for the subject."
        );
    }

    private static JsonObject artifactEvidenceJson(ArtifactEvidenceFact fact) {
        JsonObject out = new JsonObject();
        out.addProperty("sourceKind", fact.sourceKind());
        out.addProperty("owner", fact.owner());
        out.addProperty("location", fact.location());
        out.addProperty("contentKind", fact.contentKind());
        out.addProperty("authority", fact.authority());
        out.addProperty("score", fact.score());
        out.addProperty("active", fact.active());
        out.addProperty("truncated", fact.truncated());

        JsonArray matched = new JsonArray();
        fact.matchedTerms().forEach(matched::add);
        out.add("matchedTerms", matched);

        JsonArray references = new JsonArray();
        fact.identifierReferences().forEach(references::add);
        out.add("identifierReferences", references);

        out.addProperty("excerpt", fact.excerpt());
        return out;
    }

    private static void scanActiveResources(
            MinecraftClient client,
            List<String> terms,
            String namespace,
            Map<String, ArtifactEvidenceFact> target,
            int limit
    ) {
        if (client == null || client.getResourceManager() == null || target.size() >= limit) {
            return;
        }

        Map<Identifier, Resource> candidates;
        try {
            candidates = client.getResourceManager().findResources(
                    "",
                    id -> inspectableResourcePath(id.getPath())
                            && (
                            (!namespace.isBlank()
                                    && !"minecraft".equals(namespace)
                                    && namespace.equals(id.getNamespace()))
                                    || matchesAny(id.toString(), terms)
                    )
            );
        } catch (RuntimeException failure) {
            return;
        }

        int scanned = 0;
        for (Map.Entry<Identifier, Resource> entry : candidates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            if (target.size() >= limit || scanned++ >= MAXIMUM_ACTIVE_RESOURCES_SCANNED) {
                break;
            }

            Identifier id = entry.getKey();
            Resource resource = entry.getValue();
            String pathText = id.toString();
            boolean pathMatch = matchesAny(pathText, terms);

            try (InputStream input = resource.getInputStream()) {
                byte[] bytes = input.readNBytes(MAXIMUM_ARTIFACT_BYTES + 1);
                boolean truncated = bytes.length > MAXIMUM_ARTIFACT_BYTES;
                int length = Math.min(bytes.length, MAXIMUM_ARTIFACT_BYTES);
                String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
                List<String> matched = matchedTerms(pathText + "\n" + content, terms);
                if (!pathMatch && matched.isEmpty()) {
                    continue;
                }

                LinkedHashSet<String> references = new LinkedHashSet<>();
                collectArtifactIdentifiers(content, references);
                int score = 500
                        + (pathMatch ? 180 : 0)
                        + Math.min(180, matched.size() * 30)
                        + resourceSemanticBonus(id.getPath());

                ArtifactEvidenceFact fact = new ArtifactEvidenceFact(
                        "active_client_resource",
                        resource.getResourcePackName(),
                        pathText,
                        contentKind(id.getPath()),
                        "active_client_resource",
                        score,
                        matched,
                        references.stream().limit(MAXIMUM_ARTIFACT_REFERENCES).toList(),
                        excerptAroundMatch(content, matched),
                        true,
                        truncated
                );
                putArtifactEvidence(target, fact);
            } catch (Exception ignored) {
            }
        }
    }

    private static void scanInstalledModArtifacts(
            List<String> terms,
            String namespace,
            Map<String, ArtifactEvidenceFact> target,
            int limit
    ) {
        if (target.size() >= limit || namespace.isBlank() || "minecraft".equals(namespace)) {
            return;
        }

        List<ModContainer> containers = FabricLoader.getInstance()
                .getAllMods()
                .stream()
                .filter(mod -> namespace.equals(mod.getMetadata().getId())
                        || mod.getMetadata().getProvides().contains(namespace))
                .toList();
        if (containers.isEmpty()) {
            return;
        }

        for (ModContainer mod : containers) {
            if (target.size() >= limit) {
                break;
            }
            scanModContainer(mod, terms, target, limit);
        }
    }

    private static void scanModContainer(
            ModContainer mod,
            List<String> terms,
            Map<String, ArtifactEvidenceFact> target,
            int limit
    ) {
        int filesScanned = 0;
        int classesScanned = 0;
        long totalBytesScanned = 0L;

        for (Path root : mod.getRootPaths()) {
            if (root == null || target.size() >= limit) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(root)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()
                        && target.size() < limit
                        && filesScanned < MAXIMUM_MOD_FILES_SCANNED
                        && totalBytesScanned < MAXIMUM_TOTAL_SCAN_BYTES) {
                    Path path = iterator.next();
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    filesScanned++;

                    String relative;
                    try {
                        relative = root.relativize(path).toString().replace('\\', '/');
                    } catch (RuntimeException failure) {
                        relative = path.toString().replace('\\', '/');
                    }
                    String lower = relative.toLowerCase(Locale.ROOT);
                    long size;
                    try {
                        size = Files.size(path);
                    } catch (IOException failure) {
                        continue;
                    }

                    if (lower.endsWith(".class")) {
                        if (classesScanned++ >= MAXIMUM_MOD_CLASSES_SCANNED
                                || size <= 0L
                                || size > MAXIMUM_CLASS_BYTES) {
                            continue;
                        }
                        totalBytesScanned += size;
                        ArtifactEvidenceFact fact = classConstantEvidence(
                                mod,
                                path,
                                relative,
                                terms
                        );
                        if (fact != null) {
                            putArtifactEvidence(target, fact);
                        }
                        continue;
                    }

                    if (!inspectablePackagedPath(lower)
                            || size <= 0L
                            || size > MAXIMUM_ARTIFACT_BYTES) {
                        continue;
                    }

                    totalBytesScanned += size;
                    boolean pathMatch = matchesAny(lower, terms);
                    boolean alwaysUseful = alwaysUsefulModMetadata(lower);
                    if (!pathMatch
                            && !alwaysUseful
                            && !lower.startsWith("data/")
                            && !lower.startsWith("assets/")) {
                        continue;
                    }

                    String content;
                    try {
                        content = Files.readString(path, StandardCharsets.UTF_8);
                    } catch (Exception failure) {
                        continue;
                    }
                    if (content.length() > MAXIMUM_ARTIFACT_BYTES) {
                        content = content.substring(0, MAXIMUM_ARTIFACT_BYTES);
                    }

                    List<String> matched = matchedTerms(relative + "\n" + content, terms);
                    if (!pathMatch && matched.isEmpty() && !alwaysUseful) {
                        continue;
                    }

                    LinkedHashSet<String> references = new LinkedHashSet<>();
                    collectArtifactIdentifiers(content, references);
                    int score = 340
                            + (pathMatch ? 170 : 0)
                            + (alwaysUseful ? 60 : 0)
                            + Math.min(180, matched.size() * 30)
                            + resourceSemanticBonus(lower);

                    ArtifactEvidenceFact fact = new ArtifactEvidenceFact(
                            "installed_mod_resource",
                            mod.getMetadata().getId(),
                            relative,
                            contentKind(relative),
                            "packaged_mod_evidence",
                            score,
                            matched,
                            references.stream().limit(MAXIMUM_ARTIFACT_REFERENCES).toList(),
                            excerptAroundMatch(content, matched),
                            false,
                            false
                    );
                    putArtifactEvidence(target, fact);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static ArtifactEvidenceFact classConstantEvidence(
            ModContainer mod,
            Path path,
            String relative,
            List<String> terms
    ) {
        List<String> constants;
        try {
            constants = classUtf8Constants(path);
        } catch (Exception failure) {
            return null;
        }

        LinkedHashSet<String> matchedConstants = new LinkedHashSet<>();
        LinkedHashSet<String> matchedTermSet = new LinkedHashSet<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();

        for (String constant : constants) {
            List<String> matches = matchedTerms(constant, terms);
            if (!matches.isEmpty()) {
                matchedTermSet.addAll(matches);
                if (matchedConstants.size() < 18) {
                    matchedConstants.add(cleanFactText(constant, 500));
                }
            }
            collectArtifactIdentifiers(constant, references);
        }

        boolean pathMatch = matchesAny(relative, terms);
        if (!pathMatch && matchedTermSet.isEmpty()) {
            return null;
        }

        String excerpt = String.join(" | ", matchedConstants);
        if (excerpt.isBlank()) {
            excerpt = cleanFactText(relative, MAXIMUM_ARTIFACT_EXCERPT);
        }

        return new ArtifactEvidenceFact(
                "installed_mod_class_constants",
                mod.getMetadata().getId(),
                relative,
                "jvm_class",
                "bytecode_constant_reference",
                250
                        + (pathMatch ? 120 : 0)
                        + Math.min(240, matchedTermSet.size() * 40),
                matchedTermSet.stream().limit(MAXIMUM_MATCHED_TERMS).toList(),
                references.stream().limit(MAXIMUM_ARTIFACT_REFERENCES).toList(),
                cleanFactText(excerpt, MAXIMUM_ARTIFACT_EXCERPT),
                false,
                false
        );
    }

    /**
     * Reads only the JVM constant-pool UTF-8 entries. This is enough to expose
     * registry ids, translation keys, class/method descriptors, config keys,
     * resource paths, and many implementation clues without embedding a
     * decompiler or executing mod code.
     */
    private static List<String> classUtf8Constants(Path path) throws IOException {
        List<String> values = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != 0xCAFEBABE) {
                return List.of();
            }
            input.readUnsignedShort(); // minor
            input.readUnsignedShort(); // major
            int constantPoolCount = input.readUnsignedShort();

            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        String value = input.readUTF();
                        if (!value.isBlank() && values.size() < 2_048) {
                            values.add(value);
                        }
                    }
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown JVM constant-pool tag " + tag);
                }
            }
        }
        return List.copyOf(values);
    }

    private static void putArtifactEvidence(
            Map<String, ArtifactEvidenceFact> target,
            ArtifactEvidenceFact fact
    ) {
        if (fact == null) {
            return;
        }
        String key = fact.sourceKind() + "|" + fact.owner() + "|" + fact.location();
        ArtifactEvidenceFact existing = target.get(key);
        if (existing == null || fact.score() > existing.score()) {
            target.put(key, fact);
        }
    }

    private static List<String> evidenceTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT).strip();
        if (!normalized.isBlank()) {
            terms.add(normalized);
            terms.add(normalized.replace(' ', '_'));
        }

        Identifier id = Identifier.tryParse(query);
        if (id != null) {
            terms.add(id.toString().toLowerCase(Locale.ROOT));
            terms.add(id.getPath().toLowerCase(Locale.ROOT));
            terms.add(id.getPath().replace('/', '.').toLowerCase(Locale.ROOT));
        }

        for (String token : normalized.split("[^a-z0-9_./:-]+")) {
            String safe = token.strip();
            if (safe.length() >= 3
                    && !"minecraft".equals(safe)
                    && !"item".equals(safe)
                    && !"block".equals(safe)
                    && !"mod".equals(safe)) {
                terms.add(safe);
            }
            if (terms.size() >= MAXIMUM_MATCHED_TERMS) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    private static String resolveEvidenceNamespace(String query, String namespaceHint) {
        if (namespaceHint != null
                && namespaceHint.matches("[a-z0-9_.-]+")
                && !Set.of(
                "item", "block", "entity_type", "status_effect", "enchantment",
                "recipe", "advancement", "structure", "biome", "dimension_type"
        ).contains(namespaceHint.toLowerCase(Locale.ROOT))) {
            return namespaceHint.toLowerCase(Locale.ROOT);
        }

        Identifier id = Identifier.tryParse(query);
        if (id != null) {
            return id.getNamespace();
        }

        String normalized = normalize(query);
        for (ModFact mod : modFacts()) {
            if (normalized.equals(normalize(mod.id()))
                    || normalized.equals(normalize(mod.name()))) {
                return mod.id();
            }
        }
        return "";
    }

    private static boolean inspectableResourcePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return TEXT_ARTIFACT_SUFFIXES.stream().anyMatch(lower::endsWith)
                || lower.endsWith(".json");
    }

    private static boolean inspectablePackagedPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return TEXT_ARTIFACT_SUFFIXES.stream().anyMatch(lower::endsWith)
                || alwaysUsefulModMetadata(lower);
    }

    private static boolean alwaysUsefulModMetadata(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.equals("fabric.mod.json")
                || lower.endsWith(".mixins.json")
                || lower.endsWith(".accesswidener")
                || lower.equals("pack.mcmeta")
                || lower.startsWith("meta-inf/services/");
    }

    private static String contentKind(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".class")) return "jvm_class";
        if (lower.endsWith(".mixins.json")) return "mixin_configuration";
        if (lower.endsWith(".json") || lower.endsWith(".json5")) return "json";
        if (lower.endsWith(".mcmeta")) return "mcmeta";
        if (lower.endsWith(".properties") || lower.endsWith(".lang")) return "localization_or_properties";
        if (lower.endsWith(".toml") || lower.endsWith(".cfg") || lower.endsWith(".conf")) return "configuration";
        if (lower.endsWith(".accesswidener")) return "access_widener";
        if (lower.endsWith(".md") || lower.endsWith(".txt")) return "documentation";
        return "text_resource";
    }

    private static int resourceSemanticBonus(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.contains("/recipes/")) return 120;
        if (lower.contains("/advancements/")) return 110;
        if (lower.contains("/tags/")) return 100;
        if (lower.contains("/loot_tables/")) return 100;
        if (lower.contains("/predicates/")) return 90;
        if (lower.contains("/worldgen/")) return 90;
        if (lower.contains("/blockstates/")) return 80;
        if (lower.contains("/models/")) return 70;
        if (lower.contains("/lang/")) return 55;
        if (alwaysUsefulModMetadata(lower)) return 60;
        return 0;
    }

    private static boolean matchesAny(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term != null && term.length() >= 2 && normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> matchedTerms(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (String term : terms) {
            if (term != null && term.length() >= 2 && normalized.contains(term)) {
                matched.add(term);
            }
            if (matched.size() >= MAXIMUM_MATCHED_TERMS) {
                break;
            }
        }
        return List.copyOf(matched);
    }

    private static String excerptAroundMatch(String content, List<String> matchedTerms) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String normalized = content.toLowerCase(Locale.ROOT);
        int matchIndex = -1;
        for (String term : matchedTerms == null ? List.<String>of() : matchedTerms) {
            int index = normalized.indexOf(term.toLowerCase(Locale.ROOT));
            if (index >= 0 && (matchIndex < 0 || index < matchIndex)) {
                matchIndex = index;
            }
        }

        if (matchIndex < 0) {
            return cleanFactText(content, MAXIMUM_ARTIFACT_EXCERPT);
        }

        int radius = MAXIMUM_ARTIFACT_EXCERPT / 2;
        int start = Math.max(0, matchIndex - radius);
        int end = Math.min(content.length(), start + MAXIMUM_ARTIFACT_EXCERPT);
        return cleanFactText(content.substring(start, end), MAXIMUM_ARTIFACT_EXCERPT);
    }

    private static void collectArtifactIdentifiers(String text, Set<String> output) {
        if (text == null || text.isBlank() || output.size() >= MAXIMUM_ARTIFACT_REFERENCES) {
            return;
        }
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && output.size() < MAXIMUM_ARTIFACT_REFERENCES) {
            output.add(matcher.group(1));
        }
    }

    private static JsonObject coverageFacts(MinecraftClient client) {
        JsonObject coverage = new JsonObject();
        coverage.addProperty("readOnly", true);
        coverage.addProperty("clientVisibleOnly", true);
        coverage.addProperty("authoritativeFactLayer", "MinecraftKnowledgeService");
        coverage.addProperty("staticRegistries", true);
        coverage.addProperty("installedFabricMods", true);
        coverage.addProperty(
                "synchronizedRecipes",
                client != null && client.getNetworkHandler() != null && client.world != null
        );
        coverage.addProperty(
                "synchronizedAdvancements",
                client != null && client.getNetworkHandler() != null
        );
        coverage.addProperty(
                "synchronizedDynamicRegistries",
                client != null && client.getNetworkHandler() != null
        );
        coverage.addProperty("clientResources", client != null && client.getResourceManager() != null);
        coverage.addProperty("installedModPackagedResources", true);
        coverage.addProperty("installedModClassConstantReferences", true);
        coverage.addProperty(
                "implementationEvidenceBoundary",
                "Packaged mod files and class constant-pool matches are implementation clues, not proof that a server/runtime branch executed."
        );

        JsonArray excluded = new JsonArray();
        excluded.add("unsynchronized server loot tables");
        excluded.add("unsynchronized server predicates");
        excluded.add("unsynchronized server functions");
        excluded.add("plugin/server internals");
        excluded.add("arbitrary remote wiki knowledge");
        excluded.add("runtime-only behavior that leaves no client-visible, packaged-resource, registry, recipe, advancement, or bytecode-constant evidence");
        coverage.add("notAuthoritativelyAvailableHere", excluded);
        return coverage;
    }

    private static <T> List<String> tagsFor(Registry<T> registry, T value, int limit) {
        if (registry == null || value == null) {
            return List.of();
        }
        try {
            return registry.getEntry(value)
                    .streamTags()
                    .map(TagKey::id)
                    .map(Identifier::toString)
                    .sorted()
                    .limit(limit)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<String> sharedItemTags(ItemStack[] alternatives, int limit) {
        if (alternatives == null || alternatives.length == 0) {
            return List.of();
        }

        LinkedHashSet<String> intersection = null;
        for (ItemStack stack : alternatives) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> tags = new LinkedHashSet<>(
                    tagsFor(Registries.ITEM, stack.getItem(), MAXIMUM_GRAPH_TAGS)
            );
            if (intersection == null) {
                intersection = tags;
            } else {
                intersection.retainAll(tags);
            }
            if (intersection.isEmpty()) {
                return List.of();
            }
        }

        if (intersection == null || intersection.isEmpty()) {
            return List.of();
        }
        return intersection.stream().sorted().limit(limit).toList();
    }

    private static void collectIdentifiers(String text, Set<String> output) {
        if (text == null || text.isBlank() || output.size() >= MAXIMUM_IDENTIFIER_REFERENCES) {
            return;
        }
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && output.size() < MAXIMUM_IDENTIFIER_REFERENCES) {
            output.add(matcher.group(1));
        }
    }

    private static String registryKind(Identifier registryId) {
        if (registryId == null) return "registry";
        String path = registryId.getPath();
        return switch (path) {
            case "mob_effect" -> "status_effect";
            case "entity_type" -> "entity_type";
            case "dimension_type" -> "dimension_type";
            default -> path;
        };
    }

    private static String canonicalRegistryKind(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "items" -> "item";
            case "blocks" -> "block";
            case "entity", "entities" -> "entity_type";
            case "effect", "effects", "mob_effect" -> "status_effect";
            case "enchantments" -> "enchantment";
            case "sounds" -> "sound_event";
            case "dimensions", "dimension" -> "dimension_type";
            case "structures" -> "structure";
            case "biomes" -> "biome";
            default -> normalized;
        };
    }

    private static boolean registryHasExactPath(Registry<?> registry, String path) {
        if (registry == null || path == null || path.isBlank()) {
            return false;
        }
        for (Identifier id : registry.getIds()) {
            if (id.getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> semanticTokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : normalizeSearchText(value).split("[^a-z0-9_:.\\-/]+")) {
            if (!token.isBlank() && token.length() >= 2) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsAnyWord(String normalized, String... words) {
        String padded = " " + normalized.replaceAll("[^a-z0-9_:./-]+", " ") + " ";
        for (String word : words) {
            if (padded.contains(" " + word.toLowerCase(Locale.ROOT) + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String idPathOf(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon >= 0 && colon + 1 < id.length()
                ? id.substring(colon + 1)
                : id;
    }

    private static boolean semanticContains(String value, String semanticSubject) {
        if (value == null || semanticSubject == null || semanticSubject.isBlank()) {
            return false;
        }
        String haystack = normalizeSearchText(value);
        for (String token : semanticTokens(semanticSubject)) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String cleanFactText(String value, int maximum) {
        String safe = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (safe.length() <= maximum) return safe;
        return safe.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    static record RegistryEntryFact(String kind, String id, String name, String source) {
        RegistryEntryFact {
            kind = kind == null ? "" : kind;
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            source = source == null ? "" : source;
        }
    }

    static record ItemAlternativeFact(String id, String name, int count) {
        ItemAlternativeFact {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            count = Math.max(1, count);
        }
    }

    static record IngredientFact(
            int slot,
            int alternativeCount,
            List<ItemAlternativeFact> alternatives,
            List<String> sharedItemTags,
            boolean truncated
    ) {
        IngredientFact {
            slot = Math.max(0, slot);
            alternativeCount = Math.max(0, alternativeCount);
            alternatives = List.copyOf(alternatives == null ? List.of() : alternatives);
            sharedItemTags = List.copyOf(sharedItemTags == null ? List.of() : sharedItemTags);
        }
    }

    static record RecipeFact(
            String id,
            String type,
            String serializer,
            String group,
            String outputId,
            String outputName,
            int outputCount,
            List<IngredientFact> ingredients,
            Map<String, Integer> exactIngredientTotals,
            boolean exactIngredientTotalsComplete
    ) {
        RecipeFact {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            serializer = serializer == null ? "" : serializer;
            group = group == null ? "" : group;
            outputId = outputId == null ? "" : outputId;
            outputName = outputName == null ? "" : outputName;
            outputCount = Math.max(0, outputCount);
            ingredients = List.copyOf(ingredients == null ? List.of() : ingredients);
            exactIngredientTotals = Map.copyOf(
                    exactIngredientTotals == null ? Map.of() : exactIngredientTotals
            );
        }
    }

    static record AdvancementFact(
            String id,
            String title,
            String description,
            boolean hidden,
            String frame,
            String icon,
            boolean showToast,
            boolean announceToChat,
            String background,
            String parentId,
            List<String> childIds,
            List<List<String>> requirements,
            int requirementCount,
            boolean completed,
            JsonObject criteria,
            List<String> identifierReferences,
            boolean identifierReferencesTruncated
    ) {
        AdvancementFact {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            frame = frame == null ? "" : frame;
            icon = icon == null ? "" : icon;
            background = background == null ? "" : background;
            parentId = parentId == null ? "" : parentId;
            childIds = List.copyOf(childIds == null ? List.of() : childIds);
            List<List<String>> safeRequirements = new ArrayList<>();
            if (requirements != null) {
                for (List<String> requirement : requirements) {
                    safeRequirements.add(List.copyOf(requirement == null ? List.of() : requirement));
                }
            }
            requirements = List.copyOf(safeRequirements);
            requirementCount = Math.max(0, requirementCount);
            criteria = criteria == null ? new JsonObject() : criteria.deepCopy();
            identifierReferences = List.copyOf(
                    identifierReferences == null ? List.of() : identifierReferences
            );
        }
    }

    static record EnchantmentLinkFact(
            String id,
            int minimumLevel,
            int maximumLevel,
            boolean treasure,
            boolean cursed,
            List<String> conflictsWith
    ) {
        EnchantmentLinkFact {
            id = id == null ? "" : id;
            conflictsWith = List.copyOf(conflictsWith == null ? List.of() : conflictsWith);
        }
    }

    static record SubjectFact(
            String kind,
            String id,
            String name,
            String namespace,
            String source,
            JsonObject attributes,
            List<String> tags,
            String itemFormId,
            String blockFormId,
            List<EnchantmentLinkFact> applicableEnchantments,
            List<String> acceptedItemIds,
            int acceptedItemCount,
            boolean acceptedItemsTruncated
    ) {
        SubjectFact {
            kind = kind == null ? "" : kind;
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            namespace = namespace == null ? "" : namespace;
            source = source == null ? "" : source;
            attributes = attributes == null ? new JsonObject() : attributes.deepCopy();
            tags = List.copyOf(tags == null ? List.of() : tags);
            itemFormId = itemFormId == null ? "" : itemFormId;
            blockFormId = blockFormId == null ? "" : blockFormId;
            applicableEnchantments = List.copyOf(
                    applicableEnchantments == null ? List.of() : applicableEnchantments
            );
            acceptedItemIds = List.copyOf(acceptedItemIds == null ? List.of() : acceptedItemIds);
            acceptedItemCount = Math.max(0, acceptedItemCount);
        }
    }

    static record ModFact(
            String id,
            String name,
            String version,
            String environment,
            String description,
            List<String> authors,
            List<String> licenses,
            List<String> provides
    ) {
        ModFact {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            environment = environment == null ? "" : environment;
            description = description == null ? "" : description;
            authors = List.copyOf(authors == null ? List.of() : authors);
            licenses = List.copyOf(licenses == null ? List.of() : licenses);
            provides = List.copyOf(provides == null ? List.of() : provides);
        }
    }


    static record ArtifactEvidenceFact(
            String sourceKind,
            String owner,
            String location,
            String contentKind,
            String authority,
            int score,
            List<String> matchedTerms,
            List<String> identifierReferences,
            String excerpt,
            boolean active,
            boolean truncated
    ) {
        ArtifactEvidenceFact {
            sourceKind = sourceKind == null ? "" : sourceKind;
            owner = owner == null ? "" : owner;
            location = location == null ? "" : location;
            contentKind = contentKind == null ? "" : contentKind;
            authority = authority == null ? "" : authority;
            score = Math.max(0, score);
            matchedTerms = List.copyOf(matchedTerms == null ? List.of() : matchedTerms);
            identifierReferences = List.copyOf(
                    identifierReferences == null ? List.of() : identifierReferences
            );
            excerpt = excerpt == null ? "" : excerpt;
        }
    }

    /**
     * One client-thread snapshot for automation planning. This deliberately
     * reuses the active inventory, registries, and synchronized recipe manager
     * rather than reconstructing those facts in a second knowledge service.
     */
    public static CompletableFuture<PlannerSnapshot> plannerSnapshot(String exactItemId) {
        Identifier itemId = Identifier.tryParse(exactItemId == null ? "" : exactItemId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return CompletableFuture.completedFuture(
                    new PlannerSnapshot(false, false, false, 0, false, false, "")
            );
        }
        CompletableFuture<PlannerSnapshot> result = new CompletableFuture<>();
        client.execute(() -> {
            boolean playerAvailable = client.player != null && client.world != null;
            boolean itemExists = itemId != null && Registries.ITEM.containsId(itemId);
            int inventoryCount = 0;
            if (playerAvailable && itemExists) {
                for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
                    ItemStack stack = client.player.getInventory().getStack(slot);
                    if (!stack.isEmpty() && itemId.equals(Registries.ITEM.getId(stack.getItem()))) {
                        inventoryCount += stack.getCount();
                    }
                }
            }

            boolean recipeDataAvailable = client.getNetworkHandler() != null && client.world != null;
            boolean recipeKnown = recipeDataAvailable
                    && itemExists
                    && recipeFacts(client).stream()
                    .anyMatch(recipe -> itemId.toString().equals(recipe.outputId()));

            result.complete(new PlannerSnapshot(
                    true,
                    playerAvailable,
                    itemExists,
                    inventoryCount,
                    recipeDataAvailable,
                    recipeKnown,
                    client.world == null
                            ? ""
                            : client.world.getRegistryKey().getValue().toString()
            ));
        });
        return result;
    }

    /** Bounded direct-output crafting recipe detail for read-only automation planning. */
    public static CompletableFuture<PlannerRecipeSnapshot> plannerRecipeSnapshot(String exactOutputId) {
        Identifier outputId = Identifier.tryParse(exactOutputId == null ? "" : exactOutputId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return CompletableFuture.completedFuture(
                    new PlannerRecipeSnapshot(false, Map.of(), List.of())
            );
        }

        CompletableFuture<PlannerRecipeSnapshot> result = new CompletableFuture<>();
        client.execute(() -> {
            if (outputId == null
                    || client.player == null
                    || client.world == null
                    || client.getNetworkHandler() == null) {
                result.complete(new PlannerRecipeSnapshot(false, Map.of(), List.of()));
                return;
            }

            Map<String, Integer> inventory = plannerInventorySnapshot(client);
            List<PlannerRecipe> recipes = recipeFacts(client).stream()
                    .filter(MinecraftKnowledgeService::plannerCraftingRecipe)
                    .filter(recipe -> outputId.toString().equals(recipe.outputId()))
                    .map(MinecraftKnowledgeService::plannerRecipe)
                    .limit(MAXIMUM_RESULTS)
                    .toList();

            result.complete(new PlannerRecipeSnapshot(true, inventory, recipes));
        });
        return result;
    }

    /**
     * Captures one bounded crafting dependency catalog on the client thread.
     * This avoids repeatedly rescanning the synchronized recipe manager for
     * every dependency wave and keeps all planner inputs from one observation.
     */
    public static CompletableFuture<PlannerRecipeCatalogSnapshot> plannerRecipeCatalogSnapshot(
            String exactOutputId,
            int requestedDepth,
            int requestedItems
    ) {
        Identifier outputId = Identifier.tryParse(exactOutputId == null ? "" : exactOutputId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return CompletableFuture.completedFuture(PlannerRecipeCatalogSnapshot.unavailable());
        }
        int maxDepth = Math.max(1, Math.min(12, requestedDepth));
        int maxItems = Math.max(1, Math.min(128, requestedItems));
        CompletableFuture<PlannerRecipeCatalogSnapshot> result = new CompletableFuture<>();
        client.execute(() -> {
            if (outputId == null
                    || client.player == null
                    || client.world == null
                    || client.getNetworkHandler() == null) {
                result.complete(PlannerRecipeCatalogSnapshot.unavailable());
                return;
            }

            Map<String, Integer> inventory = plannerInventorySnapshot(client);
            String openProcessorType = plannerOpenProcessorRecipeType(client);
            Map<String, PlannerProcessorSource> nearbyProcessors = plannerNearbyProcessorSources(client, 20, 8);
            PlannerFuel fuel = plannerInventoryFuel(client);
            Map<String, Integer> cookingTimes = plannerCookingTimes(client);
            Map<String, List<PlannerRecipe>> byOutput = new LinkedHashMap<>();
            for (RecipeFact recipe : recipeFacts(client)) {
                if (recipe.outputId().isBlank()) continue;
                PlannerRecipe planned = null;
                if (plannerCraftingRecipe(recipe)) {
                    planned = plannerRecipe(recipe);
                } else if (fuel != null) {
                    PlannerProcessorSource processorSource = null;
                    if (!openProcessorType.isBlank() && openProcessorType.equals(recipe.type())) {
                        processorSource = PlannerProcessorSource.alreadyOpen(recipe.type());
                    } else {
                        processorSource = nearbyProcessors.get(recipe.type());
                    }
                    if (processorSource != null) {
                        int cookTicks = Math.max(1, cookingTimes.getOrDefault(recipe.id(), defaultCookTicks(recipe.type())));
                        planned = plannerProcessingRecipe(recipe, fuel, cookTicks, processorSource);
                    }
                }
                if (planned != null) {
                    byOutput.computeIfAbsent(recipe.outputId(), ignored -> new ArrayList<>()).add(planned);
                }
            }

            Map<String, List<PlannerRecipe>> catalog = new LinkedHashMap<>();
            Map<String, Integer> depths = new LinkedHashMap<>();
            List<String> queue = new ArrayList<>();
            queue.add(outputId.toString());
            depths.put(outputId.toString(), 0);
            int cursor = 0;
            boolean truncated = false;

            while (cursor < queue.size()) {
                String current = queue.get(cursor++);
                if (catalog.containsKey(current)) continue;
                if (catalog.size() >= maxItems) {
                    truncated = true;
                    break;
                }
                int depth = depths.getOrDefault(current, maxDepth);
                List<PlannerRecipe> recipes = byOutput.getOrDefault(current, List.of()).stream()
                        .sorted(Comparator.comparing(PlannerRecipe::id))
                        .limit(MAXIMUM_RESULTS)
                        .toList();
                catalog.put(current, recipes);
                if (depth >= maxDepth) {
                    if (!recipes.isEmpty()) truncated = true;
                    continue;
                }
                for (PlannerRecipe recipe : recipes) {
                    for (PlannerIngredient ingredient : recipe.ingredients()) {
                        for (String alternative : ingredient.alternatives()) {
                            if (alternative == null || alternative.isBlank() || depths.containsKey(alternative)) continue;
                            depths.put(alternative, depth + 1);
                            queue.add(alternative);
                        }
                    }
                }
            }
            if (cursor < queue.size()) truncated = true;

            Map<String, PlannerBlockSource> blockSources = plannerNearbyBlockSources(
                    client, depths.keySet(), 20, 8
            );
            Map<String, PlannerContainerSource> openContainerSources = plannerOpenContainerSources(
                    client, depths.keySet()
            );
            result.complete(new PlannerRecipeCatalogSnapshot(
                    true,
                    inventory,
                    catalog,
                    blockSources,
                    openContainerSources,
                    truncated,
                    catalog.size(),
                    maxDepth
            ));
        });
        return result;
    }


    /**
     * Returns item counts from the non-player portion of a storage screen that is
     * already open and synchronized. No container is opened speculatively here.
     */
    private static Map<String, PlannerContainerSource> plannerOpenContainerSources(
            MinecraftClient client, Set<String> candidateItems
    ) {
        if (client == null || client.player == null || client.currentScreen == null
                || client.player.currentScreenHandler == null
                || client.player.currentScreenHandler == client.player.playerScreenHandler
                || candidateItems == null || candidateItems.isEmpty()) return Map.of();

        String handlerName = client.player.currentScreenHandler.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        boolean storageHandler = handlerName.contains("container")
                || handlerName.contains("shulker")
                || handlerName.contains("hopper")
                || handlerName.contains("chest")
                || handlerName.contains("barrel");
        if (!storageHandler) return Map.of();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var slot : client.player.currentScreenHandler.slots) {
            if (slot == null || slot.inventory == client.player.getInventory() || !slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id == null) continue;
            String itemId = id.toString();
            if (!candidateItems.contains(itemId)) continue;
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return Map.of();

        Map<String, PlannerContainerSource> sources = new LinkedHashMap<>();
        counts.forEach((itemId, count) -> {
            if (count > 0) sources.put(itemId, new PlannerContainerSource(count, handlerName));
        });
        return Map.copyOf(sources);
    }

    private static Map<String, PlannerBlockSource> plannerNearbyBlockSources(
            MinecraftClient client, Set<String> candidateItems, int horizontalRadius, int verticalRadius
    ) {
        if (client == null || client.player == null || client.world == null
                || candidateItems == null || candidateItems.isEmpty()) return Map.of();

        Map<String, String> blockToItem = new LinkedHashMap<>();
        for (String itemId : candidateItems) {
            Identifier identifier = Identifier.tryParse(itemId == null ? "" : itemId);
            if (identifier == null || !Registries.ITEM.containsId(identifier)) continue;
            Item item = Registries.ITEM.get(identifier);
            if (!(item instanceof BlockItem blockItem)) continue;
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId == null || !itemId.equals(blockId.toString())) continue;
            blockToItem.put(blockId.toString(), itemId);
        }
        if (blockToItem.isEmpty()) return Map.of();

        int radius = Math.max(4, Math.min(32, horizontalRadius));
        int vertical = Math.max(2, Math.min(16, verticalRadius));
        BlockPos origin = client.player.getBlockPos();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Double> nearest = new LinkedHashMap<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -vertical; y <= vertical; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir() || state.hasBlockEntity() || state.getHardness(client.world, pos) < 0.0F
                            || !plannerCanHarvest(client, state)) continue;
                    Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                    if (blockId == null) continue;
                    String itemId = blockToItem.get(blockId.toString());
                    if (itemId == null) continue;
                    counts.merge(itemId, 1, Integer::sum);
                    double distance = pos.getSquaredDistance(origin);
                    nearest.merge(itemId, distance, Math::min);
                }
            }
        }

        Map<String, PlannerBlockSource> sources = new LinkedHashMap<>();
        counts.forEach((itemId, count) -> {
            if (count <= 0) return;
            Identifier itemIdentifier = Identifier.tryParse(itemId);
            if (itemIdentifier == null) return;
            Item item = Registries.ITEM.get(itemIdentifier);
            if (!(item instanceof BlockItem blockItem)) return;
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId == null) return;
            sources.put(itemId, new PlannerBlockSource(
                    blockId.toString(),
                    count,
                    Math.sqrt(Math.max(0.0D, nearest.getOrDefault(itemId, 0.0D))),
                    radius
            ));
        });
        return Map.copyOf(sources);
    }

    private static boolean plannerCanHarvest(MinecraftClient client, BlockState state) {
        if (client == null || client.player == null || state == null) return false;
        if (!state.isToolRequired()) return true;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack != null && !stack.isEmpty() && stack.isSuitableFor(state)) return true;
        }
        return false;
    }

    private static Map<String, Integer> plannerInventorySnapshot(MinecraftClient client) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        if (client == null || client.player == null) return inventory;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) inventory.merge(stackId(stack), stack.getCount(), Integer::sum);
        }
        return inventory;
    }

    private static boolean plannerCraftingRecipe(RecipeFact recipe) {
        return recipe != null && "minecraft:crafting".equals(recipe.type());
    }

    private static PlannerRecipe plannerRecipe(RecipeFact recipe) {
        return new PlannerRecipe(
                recipe.id(),
                recipe.outputCount(),
                plannerIngredients(recipe),
                "crafting",
                "",
                "",
                0,
                0,
                Integer.MAX_VALUE,
                "",
                1,
                0.5D,
                false
        );
    }

    private static PlannerRecipe plannerProcessingRecipe(
            RecipeFact recipe, PlannerFuel fuel, int cookTicks, PlannerProcessorSource processorSource
    ) {
        if (recipe == null || fuel == null || fuel.itemId().isBlank() || processorSource == null) return null;
        List<PlannerIngredient> ingredients = plannerIngredients(recipe);
        if (ingredients.isEmpty()) return null;
        long totalFuelTicks = (long) fuel.availableCount() * fuel.burnTicks();
        int maxBatches = (int) Math.min(Integer.MAX_VALUE, totalFuelTicks / Math.max(1, cookTicks));
        if (maxBatches <= 0) return null;
        return new PlannerRecipe(
                recipe.id(),
                recipe.outputCount(),
                ingredients,
                "processing",
                recipe.type(),
                fuel.itemId(),
                fuel.burnTicks(),
                cookTicks,
                maxBatches,
                processorSource.blockId(),
                processorSource.radius(),
                processorSource.stopDistance(),
                processorSource.openRequired()
        );
    }

    private static List<PlannerIngredient> plannerIngredients(RecipeFact recipe) {
        if (recipe == null) return List.of();
        return recipe.ingredients().stream()
                .map(ingredient -> new PlannerIngredient(
                        ingredient.alternatives().stream()
                                .map(ItemAlternativeFact::id)
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .toList(),
                        1
                ))
                .filter(ingredient -> !ingredient.alternatives().isEmpty())
                .toList();
    }

    private static Map<String, PlannerProcessorSource> plannerNearbyProcessorSources(
            MinecraftClient client, int horizontalRadius, int verticalRadius
    ) {
        if (client == null || client.player == null || client.world == null) return Map.of();
        int radius = Math.max(4, Math.min(32, horizontalRadius));
        int vertical = Math.max(2, Math.min(16, verticalRadius));
        BlockPos origin = client.player.getBlockPos();
        Map<String, PlannerProcessorSource> sources = new LinkedHashMap<>();
        Map<String, Double> nearest = new LinkedHashMap<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -vertical; y <= vertical; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (!(state.getBlock() instanceof AbstractFurnaceBlock)) continue;
                    Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                    if (blockId == null) continue;
                    String recipeType = processorRecipeTypeForBlock(blockId.toString());
                    double distance = pos.getSquaredDistance(origin);
                    if (nearest.containsKey(recipeType) && nearest.get(recipeType) <= distance) continue;
                    nearest.put(recipeType, distance);
                    sources.put(recipeType, new PlannerProcessorSource(
                            recipeType, blockId.toString(), Math.sqrt(Math.max(0.0D, distance)),
                            radius, 2.35D, true
                    ));
                }
            }
        }
        return Map.copyOf(sources);
    }

    private static String processorRecipeTypeForBlock(String blockId) {
        String normalized = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        if (normalized.contains("blast_furnace") || normalized.contains("blastfurnace")) return "minecraft:blasting";
        if (normalized.contains("smoker")) return "minecraft:smoking";
        return "minecraft:smelting";
    }

    private static String plannerOpenProcessorRecipeType(MinecraftClient client) {
        if (client == null || client.player == null
                || !(client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler processor)
                || processor.slots.size() < 3
                || processor.slots.get(0).hasStack()
                || processor.slots.get(1).hasStack()
                || processor.slots.get(2).hasStack()) return "";
        String handler = client.player.currentScreenHandler.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (handler.contains("blast")) return "minecraft:blasting";
        if (handler.contains("smoker")) return "minecraft:smoking";
        return "minecraft:smelting";
    }

    private static PlannerFuel plannerInventoryFuel(MinecraftClient client) {
        if (client == null || client.player == null) return null;
        Map<Item, Integer> fuelTimes = AbstractFurnaceBlockEntity.createFuelTimeMap();
        if (fuelTimes == null || fuelTimes.isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            Integer burn = fuelTimes.get(stack.getItem());
            if (burn == null || burn <= 0) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id != null) counts.merge(id.toString(), stack.getCount(), Integer::sum);
        }
        PlannerFuel best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null || !Registries.ITEM.containsId(id)) continue;
            int burn = Math.max(0, fuelTimes.getOrDefault(Registries.ITEM.get(id), 0));
            if (burn <= 0 || entry.getValue() <= 0) continue;
            PlannerFuel candidate = new PlannerFuel(entry.getKey(), entry.getValue(), burn);
            if (best == null
                    || (long) candidate.availableCount() * candidate.burnTicks() > (long) best.availableCount() * best.burnTicks()
                    || ((long) candidate.availableCount() * candidate.burnTicks() == (long) best.availableCount() * best.burnTicks()
                    && candidate.itemId().compareTo(best.itemId()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static Map<String, Integer> plannerCookingTimes(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) return Map.of();
        Map<String, Integer> times = new LinkedHashMap<>();
        for (Recipe<?> recipe : client.getNetworkHandler().getRecipeManager().values()) {
            if (recipe instanceof AbstractCookingRecipe cooking) {
                times.put(recipe.getId().toString(), Math.max(1, cooking.getCookTime()));
            }
        }
        return Map.copyOf(times);
    }

    private static int defaultCookTicks(String recipeType) {
        if ("minecraft:blasting".equals(recipeType) || "minecraft:smoking".equals(recipeType)) return 100;
        return 200;
    }

    private static Result catalog(MinecraftClient client) {
        JsonObject output = new JsonObject();
        output.addProperty("scope", "authoritative client-visible and synchronized data");
        output.addProperty("serverInternalsIncluded", false);
        output.addProperty("bounded", true);
        JsonArray categories = new JsonArray();
        addCategory(
                categories,
                "player",
                client.player != null && client.world != null,
                "active client player and world snapshot",
                List.of(
                        "name", "uuid", "entityId", "dimension", "biome", "position",
                        "facing", "yaw", "pitch", "gameMode", "health", "maximumHealth",
                        "food", "saturation", "armor", "experienceLevel", "onGround",
                        "sprinting", "sneaking", "swimming", "flying", "mainHand",
                        "offHand", "standingOn", "effects", "inventory", "lookingAt", "travelOptions"
                )
        );
        addCategory(
                categories,
                "target",
                client.player != null && client.world != null,
                "shared F3 developer target inspector",
                List.of(
                        "type", "title", "description", "registryId", "modOwner",
                        "position", "danger", "details", "tags"
                )
        );
        addCategory(
                categories,
                "registry",
                true,
                "static registries plus synchronized dynamic registries",
                List.of(
                        "item", "block", "entity_type", "status_effect", "enchantment",
                        "sound_event", "biome", "structure"
                )
        );
        addCategory(
                categories,
                "tag",
                true,
                "active static and synchronized registry tag membership",
                List.of("registry", "id", "members", "memberCount", "truncated")
        );
        addCategory(
                categories,
                "resource",
                client.getResourceManager() != null,
                "active client resource-pack JSON with bounded exact reads",
                List.of("id", "pack", "json", "topLevelKeys", "byteCount", "truncated")
        );
        addCategory(
                categories,
                "evidence",
                true,
                "bounded active-resource plus installed-mod packaged-resource and JVM constant-pool reference search",
                List.of(
                        "sourceKind", "owner", "location", "contentKind",
                        "authority", "score", "matchedTerms", "identifierReferences",
                        "excerpt", "active", "truncated"
                )
        );
        addCategory(
                categories,
                "mod",
                true,
                "installed Fabric Loader metadata",
                List.of("id", "name", "version", "environment", "description", "authors", "licenses", "provides")
        );
        addCategory(
                categories,
                "item",
                true,
                "active static item registry and default stack",
                List.of("id", "name", "translationKey", "maximumCount", "maximumDamage", "damageable", "fireproof", "rarity", "enchantable", "enchantability", "food")
        );
        addCategory(
                categories,
                "block",
                true,
                "active static block registry and default block state",
                List.of("id", "name", "item", "translationKey", "blastResistance", "luminance", "air", "properties", "defaultState")
        );
        addCategory(
                categories,
                "entity",
                true,
                "active static entity-type registry",
                List.of("id", "name", "translationKey", "spawnGroup", "width", "height", "summonable", "fireImmune", "saveable")
        );
        addCategory(
                categories,
                "effect",
                true,
                "active static status-effect registry",
                List.of("id", "name", "translationKey", "category", "color", "beneficial")
        );
        addCategory(
                categories,
                "enchantment",
                true,
                "active static enchantment registry",
                List.of("id", "name", "translationKey", "rarity", "target", "minimumLevel", "maximumLevel", "treasure", "cursed")
        );
        addCategory(
                categories,
                "dimension",
                client.getNetworkHandler() != null,
                "active synchronized dimension-type registry",
                List.of("identifier")
        );
        addCategory(
                categories,
                "recipe",
                client.getNetworkHandler() != null && client.world != null,
                "active synchronized recipe manager",
                List.of(
                        "id", "type", "serializer", "output", "outputName", "outputCount",
                        "ingredients", "ingredientSlotCount", "exactIngredientTotals",
                        "exactIngredientTotalsComplete"
                )
        );
        addCategory(
                categories,
                "advancement",
                client.getNetworkHandler() != null,
                "active synchronized advancement manager",
                List.of(
                        "id", "title", "description", "hidden", "frame",
                        "criteriaCount", "requirementGroups"
                )
        );
        addCategory(
                categories,
                "structure",
                client.getNetworkHandler() != null,
                "active synchronized structure registry",
                List.of("identifier")
        );
        addCategory(
                categories,
                "nbt",
                true,
                "Koil version-local item SNBT grammar and active registry identifiers",
                List.of("format", "guidance", "templates")
        );
        output.add("categories", categories);
        return success(
                output,
                "Minecraft knowledge capabilities and their current availability were listed."
        );
    }

    private static void addCategory(
            JsonArray categories,
            String id,
            boolean available,
            String source,
            List<String> fields
    ) {
        JsonObject category = new JsonObject();
        category.addProperty("id", id);
        category.addProperty("available", available);
        category.addProperty("source", source);
        JsonArray encodedFields = new JsonArray();
        fields.forEach(encodedFields::add);
        category.add("fields", encodedFields);
        categories.add(category);
    }

    private static Result player(MinecraftClient client, int limit, List<String> fields) {
        if (client.player == null || client.world == null) {
            return unavailable("A loaded player and world are required.");
        }
        JsonObject output = new JsonObject();
        BlockPos position = client.player.getBlockPos();
        output.addProperty("name", client.player.getName().getString());
        output.addProperty("uuid", client.player.getUuidAsString());
        output.addProperty("entityId", client.player.getId());
        output.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        output.addProperty(
                "biome",
                client.world.getBiome(position).getKey()
                        .map(key -> key.getValue().toString())
                        .orElse("unknown")
        );
        JsonObject coordinates = new JsonObject();
        coordinates.addProperty("x", client.player.getX());
        coordinates.addProperty("y", client.player.getY());
        coordinates.addProperty("z", client.player.getZ());
        coordinates.addProperty("blockX", position.getX());
        coordinates.addProperty("blockY", position.getY());
        coordinates.addProperty("blockZ", position.getZ());
        output.add("position", coordinates);
        output.addProperty("facing", client.player.getHorizontalFacing().asString());
        output.addProperty("yaw", client.player.getYaw());
        output.addProperty("pitch", client.player.getPitch());
        output.addProperty(
                "gameMode",
                client.interactionManager == null
                        ? "unknown"
                        : client.interactionManager.getCurrentGameMode().getName()
        );
        output.addProperty("health", client.player.getHealth());
        output.addProperty("maximumHealth", client.player.getMaxHealth());
        output.addProperty("food", client.player.getHungerManager().getFoodLevel());
        output.addProperty("saturation", client.player.getHungerManager().getSaturationLevel());
        output.addProperty("armor", client.player.getArmor());
        output.addProperty("experienceLevel", client.player.experienceLevel);
        output.addProperty("onGround", client.player.isOnGround());
        output.addProperty("sprinting", client.player.isSprinting());
        output.addProperty("sneaking", client.player.isSneaking());
        output.addProperty("swimming", client.player.isSwimming());
        output.addProperty("flying", client.player.getAbilities().flying);
        output.addProperty("mainHand", stackId(client.player.getMainHandStack()));
        output.addProperty("offHand", stackId(client.player.getOffHandStack()));
        BlockState standingOn = client.world.getBlockState(position.down());
        output.addProperty("standingOn", Registries.BLOCK.getId(standingOn.getBlock()).toString());

        JsonArray effects = new JsonArray();
        for (StatusEffectInstance effect : client.player.getStatusEffects()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", Registries.STATUS_EFFECT.getId(effect.getEffectType()).toString());
            encoded.addProperty("amplifier", effect.getAmplifier());
            encoded.addProperty("durationTicks", effect.getDuration());
            effects.add(encoded);
        }
        output.add("effects", effects);

        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < client.player.getInventory().size() && inventory.size() < limit; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject encoded = new JsonObject();
            encoded.addProperty("slot", slot);
            encoded.addProperty("id", stackId(stack));
            encoded.addProperty("name", stack.getName().getString());
            encoded.addProperty("count", stack.getCount());
            encoded.addProperty("damage", stack.getDamage());
            encoded.addProperty("maximumDamage", stack.getMaxDamage());
            inventory.add(encoded);
        }
        output.add("inventory", inventory);
        JsonObject travel = new JsonObject();
        travel.addProperty("riding", client.player.hasVehicle());
        travel.addProperty("vehicle", client.player.getVehicle() == null
                ? ""
                : Registries.ENTITY_TYPE.getId(client.player.getVehicle().getType()).toString());
        travel.addProperty("fallFlying", client.player.isFallFlying());
        travel.addProperty("inWater", client.player.isSubmergedInWater());
        String chestItem = stackId(client.player.getEquippedStack(EquipmentSlot.CHEST));
        travel.addProperty("chestItem", chestItem);
        travel.addProperty("elytraEquipped", "minecraft:elytra".equals(chestItem));
        int fireworks = 0;
        int boats = 0;
        int saddles = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            String id = stackId(stack);
            if ("minecraft:firework_rocket".equals(id)) fireworks += stack.getCount();
            if (id.endsWith("_boat") || id.endsWith("_raft")) boats += stack.getCount();
            if ("minecraft:saddle".equals(id)) saddles += stack.getCount();
        }
        travel.addProperty("fireworkRockets", fireworks);
        travel.addProperty("boatItems", boats);
        travel.addProperty("saddles", saddles);
        JsonArray availableModes = new JsonArray();
        availableModes.add("walk");
        availableModes.add("sprint");
        if (client.player.isSubmergedInWater() || client.player.isSwimming()) availableModes.add("swim");
        if (client.player.hasVehicle()) availableModes.add("mounted");
        if ("minecraft:elytra".equals(chestItem)) availableModes.add("elytra");
        if (boats > 0) availableModes.add("boat_available");
        travel.add("availableModes", availableModes);
        output.add("travelOptions", travel);
        output.add("lookingAt", targetJson(F3TargetInspector.inspect(client, F3Mode.DEVELOPER)));
        JsonObject selected = selectFields(output, fields);
        return success(
                selected,
                fields.isEmpty()
                        ? "Current player, world, inventory, effects, footing, and target data were captured."
                        : "Only the requested current-player fields were captured: " + String.join(", ", fields) + "."
        );
    }

    private static Result target(MinecraftClient client, List<String> fields) {
        if (client.player == null || client.world == null) {
            return unavailable("A loaded player and world are required.");
        }
        return success(
                selectFields(targetJson(F3TargetInspector.inspect(client, F3Mode.DEVELOPER)), fields),
                fields.isEmpty()
                        ? "The current crosshair target was inspected."
                        : "Only the requested crosshair-target fields were captured: "
                        + String.join(", ", fields) + "."
        );
    }

    private static JsonObject targetJson(F3TargetSnapshot target) {
        JsonObject output = new JsonObject();
        output.addProperty("type", target.type().name().toLowerCase(Locale.ROOT));
        output.addProperty("title", target.title());
        output.addProperty("description", target.subtitle());
        output.addProperty("registryId", target.registryId());
        output.addProperty("modOwner", target.modOwner());
        output.addProperty("position", target.position());
        output.addProperty("danger", target.danger());
        JsonArray details = new JsonArray();
        for (F3DataLine line : target.lines()) {
            if (line.label().isBlank() && line.value().isBlank()) {
                continue;
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("label", line.label());
            detail.addProperty("value", line.value());
            detail.addProperty("state", line.state());
            details.add(detail);
        }
        output.add("details", details);
        JsonArray tags = new JsonArray();
        target.tags().forEach(tags::add);
        output.add("tags", tags);
        return output;
    }

    private static Result recipes(MinecraftClient client, String query, int limit) {
        if (client.getNetworkHandler() == null || client.world == null) {
            return unavailable("A connection with synchronized recipes is required.");
        }

        String needle = normalize(query);
        List<RecipeFact> matches = recipeFacts(client).stream()
                .filter(recipe -> needle.isBlank()
                        || contains(recipe.id(), needle)
                        || contains(recipe.outputId(), needle)
                        || contains(recipe.outputName(), needle))
                .toList();

        JsonArray rows = new JsonArray();
        matches.stream()
                .limit(limit)
                .map(recipe -> recipeFactJson(recipe, MAXIMUM_ALTERNATIVES))
                .forEach(rows::add);

        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("recipes", rows);
        return success(output, "Recipes were read from the authoritative synchronized recipe fact layer.");
    }

    private static Result nbt(String query, int limit) {
        MinecraftNbtSuggestionService.Knowledge knowledge =
                MinecraftNbtSuggestionService.nbtKnowledge(query, limit);
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("format", knowledge.format());
        output.addProperty("guidance", knowledge.guidance());
        JsonArray templates = new JsonArray();
        for (MinecraftNbtSuggestionService.Template template : knowledge.templates()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("snbt", template.text());
            encoded.addProperty("description", template.description());
            templates.add(encoded);
        }
        output.add("templates", templates);
        return success(
                output,
                "Item NBT templates were read from Koil's version-local Minecraft syntax knowledge."
        );
    }

    private static Result advancements(MinecraftClient client, String query, int limit) {
        if (client.getNetworkHandler() == null) {
            return unavailable("A connection with synchronized advancements is required.");
        }

        String needle = normalize(query);
        List<AdvancementFact> matches = advancementFacts(client).stream()
                .filter(advancement -> needle.isBlank()
                        || contains(advancement.id(), needle)
                        || contains(advancement.title(), needle)
                        || contains(advancement.description(), needle))
                .toList();

        JsonArray rows = new JsonArray();
        for (AdvancementFact advancement : matches.stream().limit(limit).toList()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", advancement.id());
            if (!advancement.title().isBlank()) {
                encoded.addProperty("title", advancement.title());
                encoded.addProperty("description", advancement.description());
                encoded.addProperty("hidden", advancement.hidden());
                encoded.addProperty("frame", advancement.frame());
            }
            encoded.addProperty("criteriaCount", advancement.criteria().size());
            encoded.addProperty("requirementGroups", advancement.requirements().size());
            encoded.addProperty("completed", advancement.completed());
            rows.add(encoded);
        }

        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("advancements", rows);
        return success(output, "Advancements were read from the authoritative synchronized advancement fact layer.");
    }

    private static Result blockInfo(String query) {
        SubjectFact fact = subjectFact(MinecraftClient.getInstance(), "block", query);
        if (fact == null) {
            return unavailable(
                    "No active block registry entry matches '" + query
                            + "'. Use a namespaced id such as minecraft:stone."
            );
        }
        JsonObject output = subjectFactJson(fact);
        if (!fact.itemFormId().isBlank()) {
            output.addProperty("item", fact.itemFormId());
        }
        return success(output, "Block data was read from the authoritative Minecraft fact layer.");
    }

    private static Result itemInfo(String query) {
        SubjectFact fact = subjectFact(MinecraftClient.getInstance(), "item", query);
        if (fact == null) {
            return unavailable(
                    "No active item registry entry matches '" + query
                            + "'. Search item identifiers first when the namespace is unknown."
            );
        }
        return success(
                subjectFactJson(fact),
                "Item data was read from the authoritative Minecraft fact layer."
        );
    }

    private static Result entityInfo(String query) {
        SubjectFact fact = subjectFact(MinecraftClient.getInstance(), "entity_type", query);
        if (fact == null) {
            return unavailable(
                    "No active entity-type registry entry matches '" + query
                            + "'. Use a namespaced id such as minecraft:sheep."
            );
        }
        return success(
                subjectFactJson(fact),
                "Entity-type data was read from the authoritative Minecraft fact layer."
        );
    }

    private static Result effectInfo(String query) {
        SubjectFact fact = subjectFact(MinecraftClient.getInstance(), "status_effect", query);
        if (fact == null) {
            return unavailable(
                    "No active status-effect registry entry matches '" + query
                            + "'. Search status_effect identifiers first when the namespace is unknown."
            );
        }
        return success(
                subjectFactJson(fact),
                "Status-effect data was read from the authoritative Minecraft fact layer."
        );
    }

    private static Result enchantmentInfo(String query) {
        SubjectFact fact = subjectFact(MinecraftClient.getInstance(), "enchantment", query);
        if (fact == null) {
            return unavailable(
                    "No active enchantment registry entry matches '" + query
                            + "'. Search enchantment identifiers first when the namespace is unknown."
            );
        }
        return success(
                subjectFactJson(fact),
                "Enchantment data was read from the authoritative Minecraft fact layer."
        );
    }

    private static Result registry(MinecraftClient client, String kind, String query, int limit) {
        return switch (kind) {
            case "item", "items" -> staticRegistry("item", Registries.ITEM, query, limit);
            case "block", "blocks" -> staticRegistry("block", Registries.BLOCK, query, limit);
            case "entity", "entity_type", "entities" ->
                    staticRegistry("entity_type", Registries.ENTITY_TYPE, query, limit);
            case "effect", "effects", "status_effect" ->
                    staticRegistry("status_effect", Registries.STATUS_EFFECT, query, limit);
            case "enchantment", "enchantments" ->
                    staticRegistry("enchantment", Registries.ENCHANTMENT, query, limit);
            case "sound", "sounds" -> staticRegistry("sound_event", Registries.SOUND_EVENT, query, limit);
            case "biome", "biomes" -> dynamicRegistry(client, "biome", RegistryKeys.BIOME, query, limit);
            case "structure", "structures" ->
                    dynamicRegistry(client, "structure", RegistryKeys.STRUCTURE, query, limit);
            case "dimension", "dimensions", "dimension_type", "dimension_types" ->
                    dynamicRegistry(client, "dimension_type", RegistryKeys.DIMENSION_TYPE, query, limit);
            default -> unavailable(
                    "Unknown registry. Use item, block, entity_type, status_effect, enchantment, sound_event, biome, structure, or dimension_type."
            );
        };
    }

    private static Result tag(MinecraftClient client, String kind, String query, int limit) {
        Identifier id = Identifier.tryParse(query);
        if (id == null) {
            return unavailable("An exact namespaced tag id is required, such as minecraft:logs.");
        }
        return switch (kind) {
            case "item", "items" -> tagEntries("item", Registries.ITEM, RegistryKeys.ITEM, id, limit);
            case "block", "blocks" -> tagEntries("block", Registries.BLOCK, RegistryKeys.BLOCK, id, limit);
            case "entity", "entity_type", "entities" ->
                    tagEntries("entity_type", Registries.ENTITY_TYPE, RegistryKeys.ENTITY_TYPE, id, limit);
            case "fluid", "fluids" -> tagEntries("fluid", Registries.FLUID, RegistryKeys.FLUID, id, limit);
            case "biome", "biomes" -> {
                if (client.getNetworkHandler() == null) {
                    yield unavailable("A connection with synchronized biome tags is required.");
                }
                Registry<?> registry = client.getNetworkHandler().getRegistryManager().get(RegistryKeys.BIOME);
                yield tagEntriesUnchecked("biome", registry, RegistryKeys.BIOME, id, limit);
            }
            default -> unavailable("Unsupported tag registry. Use item, block, entity_type, fluid, or biome.");
        };
    }

    private static <T> Result tagEntries(
            String kind,
            Registry<T> registry,
            RegistryKey<? extends Registry<T>> registryKey,
            Identifier id,
            int limit
    ) {
        TagKey<T> tag = TagKey.of(registryKey, id);
        java.util.Optional<RegistryEntryList.Named<T>> entries = registry.getEntryList(tag);
        if (entries.isEmpty()) {
            return unavailable("No active " + kind + " tag matches '" + id + "'.");
        }
        List<String> members = entries.get().stream()
                .map(RegistryEntry::getKey)
                .flatMap(java.util.Optional::stream)
                .map(key -> key.getValue().toString())
                .sorted()
                .toList();
        JsonObject output = new JsonObject();
        output.addProperty("registry", kind);
        output.addProperty("id", id.toString());
        output.addProperty("memberCount", members.size());
        output.addProperty("truncated", members.size() > limit);
        JsonArray encoded = new JsonArray();
        members.stream().limit(limit).forEach(encoded::add);
        output.add("members", encoded);
        return success(output, "Tag membership was read from the active registry.");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Result tagEntriesUnchecked(
            String kind,
            Registry registry,
            RegistryKey registryKey,
            Identifier id,
            int limit
    ) {
        return tagEntries(kind, registry, registryKey, id, limit);
    }

    private static Result resource(
            MinecraftClient client,
            String query,
            int limit,
            List<String> fields
    ) {
        if (client.getResourceManager() == null) {
            return unavailable("The active client resource manager is unavailable.");
        }
        String needle = normalize(query);
        Map<Identifier, Resource> matches = client.getResourceManager().findResources(
                "",
                id -> id.getPath().endsWith(".json")
                        && (needle.isBlank() || contains(id.toString(), needle))
        );
        List<Identifier> ids = matches.keySet().stream().sorted().toList();
        Identifier exact = Identifier.tryParse(query);
        if (exact != null && matches.containsKey(exact)) {
            Resource selected = matches.get(exact);
            try (InputStream input = selected.getInputStream()) {
                byte[] bytes = input.readNBytes(MAXIMUM_RESOURCE_BYTES + 1);
                boolean truncated = bytes.length > MAXIMUM_RESOURCE_BYTES;
                int byteCount = truncated ? MAXIMUM_RESOURCE_BYTES : bytes.length;
                JsonObject output = new JsonObject();
                output.addProperty("id", exact.toString());
                output.addProperty("pack", selected.getResourcePackName());
                output.addProperty("byteCount", byteCount);
                output.addProperty("truncated", truncated);
                if (truncated) {
                    output.addProperty("contentAvailable", false);
                    return success(output, "The exact JSON resource exists but exceeds the bounded read limit; no partial JSON was presented as complete.");
                }
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(
                        new String(bytes, StandardCharsets.UTF_8)
                );
                if (!fields.isEmpty() && parsed.isJsonObject()) {
                    output.add("json", selectFields(parsed.getAsJsonObject(), fields));
                } else {
                    output.add("json", parsed);
                }
                JsonArray keys = new JsonArray();
                if (parsed.isJsonObject()) parsed.getAsJsonObject().keySet().forEach(keys::add);
                output.add("topLevelKeys", keys);
                return success(output, fields.isEmpty()
                        ? "The exact bounded JSON resource was read from the active client resource stack."
                        : "Only the requested top-level JSON fields were returned from the exact active resource.");
            } catch (Exception failure) {
                return unavailable("The exact active resource could not be decoded as bounded JSON: "
                        + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            }
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", ids.size());
        output.addProperty("truncated", ids.size() > limit);
        JsonArray resources = new JsonArray();
        ids.stream().limit(limit).forEach(id -> resources.add(id.toString()));
        output.add("resources", resources);
        return success(output, "Matching JSON resource identifiers were listed; read one exact id for content.");
    }

    private static Result mods(String query, int limit) {
        String needle = normalize(query);
        List<ModFact> matches = modFacts().stream()
                .filter(mod -> needle.isBlank()
                        || contains(mod.id(), needle)
                        || contains(mod.name(), needle))
                .toList();

        JsonArray rows = new JsonArray();
        for (ModFact mod : matches.stream().limit(limit).toList()) {
            rows.add(modFactJson(mod));
        }

        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("mods", rows);
        return success(
                output,
                "Installed mod metadata was read from the authoritative Fabric metadata fact layer."
        );
    }

    private static <T> Result staticRegistry(String kind, Registry<T> registry, String query, int limit) {
        return registryIds(kind, registry.getIds(), query, limit);
    }

    private static <T> Result dynamicRegistry(
            MinecraftClient client,
            String kind,
            net.minecraft.registry.RegistryKey<? extends Registry<? extends T>> key,
            String query,
            int limit
    ) {
        if (client.getNetworkHandler() == null) {
            return unavailable("An active connection is required for dynamic registry data.");
        }
        return client.getNetworkHandler().getRegistryManager()
                .getOptional(key)
                .<Result>map(registry -> registryIds(kind, registry.getIds(), query, limit))
                .orElseGet(() -> unavailable("The active connection did not provide the requested registry."));
    }

    private static Result registryIds(String kind, Iterable<Identifier> ids, String query, int limit) {
        MinecraftRegistrySuggestions.SearchResult matches =
                MinecraftRegistrySuggestions.search(ids, query, limit);
        JsonArray rows = new JsonArray();
        matches.candidates().stream()
                .map(candidate -> candidate.identifier().toString())
                .forEach(rows::add);
        JsonObject output = new JsonObject();
        output.addProperty("registry", kind);
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.matchCount());
        output.addProperty("truncated", matches.truncated());
        output.add("ids", rows);
        return success(output, "Registry identifiers were read from the active client connection.");
    }


    private static JsonObject recipeFactJson(RecipeFact recipe, int alternativeLimit) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", recipe.id());
        encoded.addProperty("type", recipe.type());
        encoded.addProperty("serializer", recipe.serializer());
        encoded.addProperty("group", recipe.group());
        encoded.addProperty("output", recipe.outputId());
        encoded.addProperty("outputName", recipe.outputName());
        encoded.addProperty("outputCount", recipe.outputCount());

        JsonArray ingredients = new JsonArray();
        for (IngredientFact ingredient : recipe.ingredients()) {
            JsonObject encodedIngredient = new JsonObject();
            encodedIngredient.addProperty("slot", ingredient.slot());
            encodedIngredient.addProperty("alternativeCount", ingredient.alternativeCount());
            encodedIngredient.addProperty(
                    "truncated",
                    ingredient.truncated() || ingredient.alternatives().size() > alternativeLimit
            );
            JsonArray alternatives = new JsonArray();
            ingredient.alternatives().stream().limit(alternativeLimit).forEach(alternative -> {
                JsonObject encodedAlternative = new JsonObject();
                encodedAlternative.addProperty("id", alternative.id());
                encodedAlternative.addProperty("name", alternative.name());
                encodedAlternative.addProperty("count", alternative.count());
                alternatives.add(encodedAlternative);
            });
            encodedIngredient.add("alternatives", alternatives);

            JsonArray sharedTags = new JsonArray();
            ingredient.sharedItemTags().forEach(sharedTags::add);
            encodedIngredient.add("sharedItemTags", sharedTags);
            ingredients.add(encodedIngredient);
        }
        encoded.add("ingredients", ingredients);
        encoded.addProperty("ingredientSlotCount", recipe.ingredients().size());
        encoded.addProperty(
                "exactIngredientTotalsComplete",
                recipe.exactIngredientTotalsComplete()
        );

        JsonArray totals = new JsonArray();
        recipe.exactIngredientTotals().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonObject total = new JsonObject();
                    total.addProperty("id", entry.getKey());
                    total.addProperty("count", entry.getValue());
                    totals.add(total);
                });
        encoded.add("exactIngredientTotals", totals);
        return encoded;
    }

    private static JsonObject subjectFactJson(SubjectFact fact) {
        JsonObject output = new JsonObject();
        output.addProperty("id", fact.id());
        output.addProperty("name", fact.name());
        for (Map.Entry<String, JsonElement> entry : fact.attributes().entrySet()) {
            output.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return output;
    }

    private static JsonObject modFactJson(ModFact mod) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", mod.id());
        encoded.addProperty("name", mod.name());
        encoded.addProperty("version", mod.version());
        encoded.addProperty("environment", mod.environment());
        encoded.addProperty("description", mod.description());

        JsonArray authors = new JsonArray();
        mod.authors().forEach(authors::add);
        encoded.add("authors", authors);

        JsonArray licenses = new JsonArray();
        mod.licenses().forEach(licenses::add);
        encoded.add("licenses", licenses);

        JsonArray provides = new JsonArray();
        mod.provides().forEach(provides::add);
        encoded.add("provides", provides);
        return encoded;
    }

    private static String stackId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static <T> String idOf(Registry<T> registry, T value) {
        Identifier id = value == null ? null : registry.getId(value);
        return id == null ? "" : id.toString();
    }

    private static boolean contains(String value, String normalizedNeedle) {
        return value != null && normalize(value).contains(normalizedNeedle);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .strip();
    }

    private static List<String> normalizeFields(List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : requestedFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String normalized = field.strip();
            if (normalized.length() <= 48) {
                fields.add(normalized);
            }
            if (fields.size() >= 24) {
                break;
            }
        }
        return List.copyOf(fields);
    }

    private static JsonObject selectFields(JsonObject source, List<String> fields) {
        if (source == null) {
            return new JsonObject();
        }
        if (fields == null || fields.isEmpty()) {
            return source;
        }
        JsonObject selected = new JsonObject();
        for (String field : fields) {
            if (source.has(field)) {
                selected.add(field, source.get(field).deepCopy());
            }
        }
        return selected;
    }

    private static Result success(JsonObject output, String detail) {
        return new Result(true, output, detail);
    }

    private static Result unavailable(String detail) {
        return new Result(false, new JsonObject(), detail);
    }

    public record Result(boolean available, JsonObject output, String detail) {
        public Result {
            output = output == null ? new JsonObject() : output.deepCopy();
            detail = detail == null ? "" : detail;
        }
    }

    public record PlannerSnapshot(
            boolean clientAvailable,
            boolean playerAvailable,
            boolean itemExists,
            int inventoryCount,
            boolean recipeDataAvailable,
            boolean recipeKnown,
            String dimension
    ) {
        public PlannerSnapshot {
            inventoryCount = Math.max(0, inventoryCount);
            dimension = dimension == null ? "" : dimension;
        }
    }

    /** Public, static-only fact transport for Koil's retrieval adapter. */
    public record StaticKnowledgeSnapshot(String fingerprint, List<StaticKnowledgeFact> facts) {
        public StaticKnowledgeSnapshot {
            fingerprint = fingerprint == null ? "" : fingerprint.strip();
            facts = List.copyOf(facts == null ? List.of() : facts);
        }
    }

    /** A registry, recipe, tag, mod, or resource fact; dynamic state is not a valid kind. */
    public record StaticKnowledgeFact(String kind, String key, String text) {
        public StaticKnowledgeFact {
            kind = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
            key = key == null ? "" : key.strip();
            text = text == null ? "" : text.strip();
        }
    }

    public record PlannerRecipeCatalogSnapshot(
            boolean available,
            Map<String, Integer> inventoryCounts,
            Map<String, List<PlannerRecipe>> catalog,
            Map<String, PlannerBlockSource> blockSources,
            Map<String, PlannerContainerSource> openContainerSources,
            boolean truncated,
            int capturedItems,
            int maxDepth
    ) {
        public PlannerRecipeCatalogSnapshot {
            inventoryCounts = Map.copyOf(inventoryCounts == null ? Map.of() : inventoryCounts);
            Map<String, List<PlannerRecipe>> normalized = new LinkedHashMap<>();
            if (catalog != null) {
                catalog.forEach((item, recipes) -> normalized.put(
                        item,
                        List.copyOf(recipes == null ? List.of() : recipes)
                ));
            }
            catalog = Map.copyOf(normalized);
            blockSources = Map.copyOf(blockSources == null ? Map.of() : blockSources);
            openContainerSources = Map.copyOf(openContainerSources == null ? Map.of() : openContainerSources);
            capturedItems = Math.max(0, capturedItems);
            maxDepth = Math.max(0, maxDepth);
        }

        public static PlannerRecipeCatalogSnapshot unavailable() {
            return new PlannerRecipeCatalogSnapshot(false, Map.of(), Map.of(), Map.of(), Map.of(), false, 0, 0);
        }
    }

    public record PlannerContainerSource(int observedCount, String handlerKind) {
        public PlannerContainerSource {
            observedCount = Math.max(0, observedCount);
            handlerKind = handlerKind == null ? "" : handlerKind;
        }
    }

    public record PlannerBlockSource(String blockId, int observedCount, double nearestDistance, int radius) {
        public PlannerBlockSource {
            blockId = blockId == null ? "" : blockId;
            observedCount = Math.max(0, observedCount);
            nearestDistance = Math.max(0.0D, nearestDistance);
            radius = Math.max(1, radius);
        }
    }

    public record PlannerRecipeSnapshot(boolean available, Map<String, Integer> inventoryCounts, List<PlannerRecipe> recipes) {
        public PlannerRecipeSnapshot {
            inventoryCounts = Map.copyOf(inventoryCounts == null ? Map.of() : inventoryCounts);
            recipes = List.copyOf(recipes == null ? List.of() : recipes);
        }
    }

    public record PlannerRecipe(
            String id,
            int outputCount,
            List<PlannerIngredient> ingredients,
            String operationKind,
            String processKind,
            String fuelId,
            int fuelBurnTicks,
            int cookTicks,
            int maxBatches,
            String processorBlockId,
            int processorRadius,
            double processorStopDistance,
            boolean processorOpenRequired
    ) {
        public PlannerRecipe {
            id = id == null ? "" : id;
            outputCount = Math.max(1, outputCount);
            ingredients = List.copyOf(ingredients == null ? List.of() : ingredients);
            operationKind = operationKind == null || operationKind.isBlank() ? "crafting" : operationKind;
            processKind = processKind == null ? "" : processKind;
            fuelId = fuelId == null ? "" : fuelId;
            fuelBurnTicks = Math.max(0, fuelBurnTicks);
            cookTicks = Math.max(0, cookTicks);
            maxBatches = Math.max(1, maxBatches);
            processorBlockId = processorBlockId == null ? "" : processorBlockId;
            processorRadius = Math.max(1, processorRadius);
            processorStopDistance = Math.max(0.5D, processorStopDistance);
        }

        public PlannerRecipe(String id, int outputCount, List<PlannerIngredient> ingredients) {
            this(id, outputCount, ingredients, "crafting", "", "", 0, 0, Integer.MAX_VALUE, "", 1, 0.5D, false);
        }
    }

    private record PlannerProcessorSource(
            String recipeType, String blockId, double distance, int radius, double stopDistance, boolean openRequired
    ) {
        private PlannerProcessorSource {
            recipeType = recipeType == null ? "" : recipeType;
            blockId = blockId == null ? "" : blockId;
            distance = Math.max(0.0D, distance);
            radius = Math.max(1, radius);
            stopDistance = Math.max(0.5D, stopDistance);
        }

        private static PlannerProcessorSource alreadyOpen(String recipeType) {
            return new PlannerProcessorSource(recipeType, "", 0.0D, 1, 0.5D, false);
        }
    }

    private record PlannerFuel(String itemId, int availableCount, int burnTicks) {
        private PlannerFuel {
            itemId = itemId == null ? "" : itemId;
            availableCount = Math.max(0, availableCount);
            burnTicks = Math.max(1, burnTicks);
        }
    }

    public record PlannerIngredient(List<String> alternatives, int count) {
        public PlannerIngredient {
            alternatives = List.copyOf(alternatives == null ? List.of() : alternatives);
            count = Math.max(1, count);
        }
    }
}
