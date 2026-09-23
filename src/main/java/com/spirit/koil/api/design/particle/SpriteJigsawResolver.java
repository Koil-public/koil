package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detached jigsaw/template-pool assembler.
 *
 * <p>Minecraft's jigsaw generator normally requires a server world, chunk
 * generator, heightmaps and structure placement context. Koil instead assembles
 * the same structure-template/pool graph in logical Minecraft XYZ space, then
 * projects the complete native volume behind one selected edit plane. The edit
 * plane is the structure front face, while template Z expands contiguously
 * behind it. Sparse terrain slice presets never slice structure geometry.</p>
 *
 * <p>The resolver also rotates native block states with each jigsaw piece so
 * directional blocks remain consistent with transformed template geometry.</p>
 */
final class SpriteJigsawResolver {
    private static final int MAX_PIECES = 128;

    private SpriteJigsawResolver() { }

    static SpriteWorldgenGenerator.Result generateFromPool(SpriteEngine engine,
                                                            SpriteWorldgenGenerator.Request request,
                                                            Identifier poolId,
                                                            int maxDepth) {
        if (engine == null || request == null || poolId == null) {
            return new SpriteWorldgenGenerator.Result(0, 0, 0, "Missing jigsaw pool");
        }
        maxDepth = Math.max(0, Math.min(12, maxDepth));
        PoolChoice rootChoice = choosePool(poolId, request.seed());
        if (rootChoice == null || rootChoice.templateId == null) {
            return new SpriteWorldgenGenerator.Result(0, 0, 0,
                    "Jigsaw pool " + poolId + " contains no structure template");
        }
        TemplateData root = loadTemplate(rootChoice.templateId);
        if (root == null) {
            return new SpriteWorldgenGenerator.Result(0, 0, 0,
                    "Missing jigsaw start template " + rootChoice.templateId);
        }
        int rootRotation = Math.floorMod((int) mix(request.seed(), rootChoice.templateId.hashCode()), 4);
        int rootX = request.centerX() - root.rotatedSizeX(rootRotation) / 2;
        int rootZ = 1 - root.rotatedSizeZ(rootRotation); // current edit plane is the front face
        return assemble(engine, request, root, new Int3(rootX, request.surfaceY(), rootZ), rootRotation,
                maxDepth, rootChoice.processors, "Resolved full jigsaw pool " + poolId);
    }

    static SpriteWorldgenGenerator.Result generateTemplate(SpriteEngine engine,
                                                            SpriteWorldgenGenerator.Request request,
                                                            SpriteWorldgenCatalog.Entry entry) {
        if (engine == null || request == null || entry == null) {
            return new SpriteWorldgenGenerator.Result(0, 0, 0, "Missing structure template");
        }
        TemplateData data = loadTemplate(entry.id());
        if (data == null) {
            return new SpriteWorldgenGenerator.Result(0, 0, 0,
                    "Could not read structure template " + entry.id());
        }
        int rootX = request.centerX() - data.rotatedSizeX(0) / 2;
        int rootZ = 1 - data.rotatedSizeZ(0); // current edit plane is the front face
        // Directly selected templates can contain live jigsaw connectors too.
        // Resolve those connectors recursively just like a structure selected
        // through its start_pool. Raw jigsaw blocks are converted to final_state
        // during placement and never become the authored stopping point.
        return assemble(engine, request, data, new Int3(rootX, request.surfaceY(), rootZ), 0,
                7, null, "Projected native template " + entry.id());
    }

    private static SpriteWorldgenGenerator.Result assemble(SpriteEngine engine,
                                                            SpriteWorldgenGenerator.Request request,
                                                            TemplateData root,
                                                            Int3 rootOrigin,
                                                            int rootRotation,
                                                            int maxDepth,
                                                            JsonElement rootProcessors,
                                                            String label) {
        Scene scene = engine.scene();
        ArrayDeque<Piece> queue = new ArrayDeque<>();
        queue.add(new Piece(root, rootOrigin, rootRotation, 0, -1, rootProcessors,
                mix(request.seed(), 0x51F15EEDL)));

        Set<String> visited = new HashSet<>();
        List<Box3> occupiedBoxes = new ArrayList<>();
        int blocks = 0;
        int pieces = 0;
        int connectorsExpanded = 0;
        SceneCellPos focus = null;

        while (!queue.isEmpty() && pieces < MAX_PIECES) {
            Piece piece = queue.removeFirst();
            String visit = piece.template.id + "@" + piece.origin.x + "," + piece.origin.y + ","
                    + piece.origin.z + ":" + piece.rotation;
            if (!visited.add(visit)) continue;
            Box3 pieceBox = boxFor(piece);
            boolean overlaps = false;
            for (Box3 occupied : occupiedBoxes) {
                if (pieceBox.intersects(occupied)) { overlaps = true; break; }
            }
            if (overlaps) continue;
            occupiedBoxes.add(pieceBox);
            pieces++;

            Placement placement = placePiece(scene, request, piece);
            blocks += placement.blocks;
            if (focus == null && placement.focus != null) focus = placement.focus;

            if (piece.depth >= maxDepth) continue;
            int connectorOrdinal = 0;
            for (int connectorIndex = 0; connectorIndex < piece.template.connectors.size(); connectorIndex++) {
                if (connectorIndex == piece.consumedConnector) continue;
                Connector parent = piece.template.connectors.get(connectorIndex);
                if (parent.pool == null || isEmptyPool(parent.pool)
                        || parent.target == null || parent.target.isBlank()) continue;

                Direction parentFront = rotateHorizontal(parent.front, piece.rotation);
                Int3 parentLocal = transform(parent.local, piece.template.sizeX, piece.template.sizeZ, piece.rotation);
                Int3 parentWorld = piece.origin.add(parentLocal);
                Int3 attach = parentWorld.add(vector(parentFront));

                long connectorSeed = mix(piece.seed,
                        connectorOrdinal++ * 0x9E3779B97F4A7C15L + parentWorld.hashCode());
                PoolChoice childChoice = choosePool(parent.pool, connectorSeed);
                if (childChoice == null || childChoice.templateId == null) continue;
                TemplateData child = loadTemplate(childChoice.templateId);
                if (child == null) continue;

                Attachment attachment = findAttachment(child, parent.target, parentFront, attach, connectorSeed);
                if (attachment == null) continue;
                Piece childPiece = new Piece(child, attachment.origin, attachment.rotation,
                        piece.depth + 1, attachment.connectorIndex, childChoice.processors, connectorSeed);
                String childVisit = child.id + "@" + childPiece.origin.x + "," + childPiece.origin.y + ","
                        + childPiece.origin.z + ":" + childPiece.rotation;
                if (visited.contains(childVisit)) continue;
                queue.addLast(childPiece);
                connectorsExpanded++;
            }
        }

        String suffix = queue.isEmpty() ? "" : " | piece limit " + MAX_PIECES + " reached";
        return new SpriteWorldgenGenerator.Result(blocks, 0, pieces,
                label + " | pieces " + pieces + " | connections " + connectorsExpanded + suffix,
                focus);
    }

    private static Placement placePiece(Scene scene, SpriteWorldgenGenerator.Request request, Piece piece) {
        int count = 0;
        SceneCellPos focus = null;
        for (TemplateBlock block : piece.template.blocks) {
            Int3 local = transform(block.local, piece.template.sizeX, piece.template.sizeZ, piece.rotation);
            Int3 world = piece.origin.add(local);
            int layer = structurePlane(request) + world.z;
            boolean jigsaw = block.state != null && block.state.getBlock() == Blocks.JIGSAW;
            BlockState state = jigsaw ? finalState(block.nbt) : block.state;
            if (state == null || state.getBlock() == Blocks.STRUCTURE_VOID) continue;
            SceneCellPos target = new SceneCellPos(world.x, world.y, layer);

            // Template AIR is authored volume when replacement is enabled. It
            // carves rooms/corridors instead of leaving terrain packed inside
            // the structure. STRUCTURE_VOID is the explicit preserve-world
            // marker and was handled above.
            if (state.isAir()) {
                if (request.replaceExisting()) clearCell(scene, target);
                continue;
            }

            state = applyProcessors(state, scene, target, piece.processors, piece.seed, world);
            if (state == null || state.getBlock() == Blocks.STRUCTURE_VOID) continue;
            state = rotateState(state, piece.rotation);
            if (state.isAir()) {
                if (request.replaceExisting()) clearCell(scene, target);
                continue;
            }
            if (!request.replaceExisting() && scene.blocks().occupied(target)) continue;
            if (request.replaceExisting()) clearCell(scene, target);
            scene.blocks().setSceneOwned(target, state);
            scene.blocks().putRuntimeData(target, "generated_source", piece.template.id.toString());
            scene.blocks().putRuntimeData(target, "generated_role", "structure");
            scene.blocks().putRuntimeData(target, "source_structure_z", Integer.toString(world.z));
            scene.blocks().putRuntimeData(target, "structure_plane", Integer.toString(structurePlane(request)));
            if (!jigsaw && block.nbt != null) {
                for (String key : block.nbt.getKeys()) {
                    scene.blocks().putBlockEntityData(target, key, String.valueOf(block.nbt.get(key)));
                }
            }
            if (focus == null) focus = target;
            count++;
        }
        return new Placement(count, focus);
    }


    private static Box3 boxFor(Piece piece) {
        int width = Math.floorMod(piece.rotation, 2) == 0 ? piece.template.sizeX : piece.template.sizeZ;
        int depth = Math.floorMod(piece.rotation, 2) == 0 ? piece.template.sizeZ : piece.template.sizeX;
        return new Box3(piece.origin.x, piece.origin.y, piece.origin.z,
                piece.origin.x + Math.max(1, width) - 1,
                piece.origin.y + Math.max(1, piece.template.sizeY) - 1,
                piece.origin.z + Math.max(1, depth) - 1);
    }

    private static Attachment findAttachment(TemplateData child, String expectedName,
                                             Direction parentFront, Int3 attach, long seed) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < child.connectors.size(); i++) {
            Connector connector = child.connectors.get(i);
            if (expectedName.equals(connector.name)) candidates.add(i);
        }
        if (candidates.isEmpty()) return null;
        int start = Math.floorMod((int) seed, candidates.size());
        for (int offset = 0; offset < candidates.size(); offset++) {
            int index = candidates.get((start + offset) % candidates.size());
            Connector connector = child.connectors.get(index);
            for (int rotation = 0; rotation < 4; rotation++) {
                Direction childFront = rotateHorizontal(connector.front, rotation);
                if (childFront != parentFront.getOpposite()) continue;
                Int3 transformed = transform(connector.local, child.sizeX, child.sizeZ, rotation);
                Int3 origin = attach.subtract(transformed);
                return new Attachment(index, rotation, origin);
            }
        }
        return null;
    }

    private static TemplateData loadTemplate(Identifier id) {
        if (id == null) return null;
        SpriteWorldgenCatalog.Entry entry = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.STRUCTURE_TEMPLATE, id).orElse(null);
        if (entry == null) return null;
        NbtCompound root = SpriteWorldgenCatalog.readStructure(entry).orElse(null);
        if (root == null) return null;
        NbtList palette = root.getList("palette", NbtElement.COMPOUND_TYPE);
        if (palette.isEmpty()) {
            NbtList palettes = root.getList("palettes", NbtElement.LIST_TYPE);
            if (!palettes.isEmpty()) palette = palettes.getList(0);
        }
        NbtList sourceBlocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        int[] size = structureSize(root);
        int sx = size.length > 0 ? Math.max(1, size[0]) : 1;
        int sy = size.length > 1 ? Math.max(1, size[1]) : 1;
        int sz = size.length > 2 ? Math.max(1, size[2]) : 1;
        List<TemplateBlock> blocks = new ArrayList<>();
        List<Connector> connectors = new ArrayList<>();
        for (int i = 0; i < sourceBlocks.size(); i++) {
            NbtCompound value = sourceBlocks.getCompound(i);
            NbtList p = value.getList("pos", NbtElement.INT_TYPE);
            if (p.size() < 3) continue;
            int stateIndex = value.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) continue;
            NbtCompound paletteState = palette.getCompound(stateIndex);
            BlockState state = stateFromPalette(paletteState);
            NbtCompound nbt = value.contains("nbt", NbtElement.COMPOUND_TYPE) ? value.getCompound("nbt") : null;
            Int3 local = new Int3(p.getInt(0), p.getInt(1), p.getInt(2));
            blocks.add(new TemplateBlock(local, state, nbt));
            if (state != null && state.getBlock() == Blocks.JIGSAW && nbt != null) {
                String orientation = paletteState.contains("Properties", NbtElement.COMPOUND_TYPE)
                        ? paletteState.getCompound("Properties").getString("orientation") : "north_up";
                Direction front = orientationFront(orientation);
                connectors.add(new Connector(local, nbt.getString("name"), nbt.getString("target"),
                        identifier(nbt.getString("pool")), nbt.getString("final_state"), front));
            }
        }
        return new TemplateData(id, sx, sy, sz, blocks, connectors);
    }

    private static PoolChoice choosePool(Identifier poolId, long seed) {
        if (poolId == null || isEmptyPool(poolId)) return null;
        SpriteWorldgenCatalog.Entry pool = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.TEMPLATE_POOL, poolId).orElse(null);
        if (pool == null) return null;
        JsonObject root = SpriteWorldgenCatalog.readJson(pool).orElse(null);
        if (root == null) return null;
        JsonArray elements = root.has("elements") && root.get("elements").isJsonArray() ? root.getAsJsonArray("elements") : null;
        if (elements == null || elements.isEmpty()) return chooseFallback(root, seed);
        int total = 0;
        List<WeightedElement> options = new ArrayList<>();
        for (JsonElement raw : elements) {
            if (!raw.isJsonObject()) continue;
            JsonObject wrapper = raw.getAsJsonObject();
            int weight = Math.max(1, wrapper.has("weight") ? wrapper.get("weight").getAsInt() : 1);
            JsonElement element = wrapper.get("element");
            PoolChoice choice = poolChoiceFromElement(element, seed ^ total);
            if (choice == null || choice.templateId == null) continue;
            total += weight;
            options.add(new WeightedElement(choice.templateId, choice.processors, weight));
        }
        if (options.isEmpty() || total <= 0) return chooseFallback(root, seed);
        int roll = Math.floorMod((int) mix(seed, poolId.hashCode()), total);
        for (WeightedElement option : options) {
            if (roll < option.weight) return new PoolChoice(option.templateId, option.processors);
            roll -= option.weight;
        }
        WeightedElement last = options.get(options.size() - 1);
        return new PoolChoice(last.templateId, last.processors);
    }

    private static PoolChoice chooseFallback(JsonObject root, long seed) {
        Identifier fallback = root == null ? null : identifier(jsonString(root, "fallback", ""));
        if (fallback == null || isEmptyPool(fallback)) return null;
        return choosePool(fallback, mix(seed, 0xFA11BACCL));
    }

    private static PoolChoice poolChoiceFromElement(JsonElement element, long seed) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String type = jsonString(object, "element_type", "").toLowerCase(Locale.ROOT);
            if (type.contains("empty_pool_element")) return null;
            Identifier location = identifier(jsonString(object, "location", ""));
            if (location != null) return new PoolChoice(location, object.get("processors"));
            if (object.has("elements") && object.get("elements").isJsonArray()) {
                JsonArray list = object.getAsJsonArray("elements");
                if (list.isEmpty()) return null;
                int start = Math.floorMod((int) seed, list.size());
                for (int i = 0; i < list.size(); i++) {
                    PoolChoice nested = poolChoiceFromElement(list.get((start + i) % list.size()), mix(seed, i + 1L));
                    if (nested != null) return nested;
                }
            }
            for (var child : object.entrySet()) {
                if (child.getKey().equals("processors")) continue;
                PoolChoice nested = poolChoiceFromElement(child.getValue(), mix(seed, child.getKey().hashCode()));
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            JsonArray list = element.getAsJsonArray();
            for (int i = 0; i < list.size(); i++) {
                PoolChoice nested = poolChoiceFromElement(list.get(i), mix(seed, i + 1L));
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /**
     * Applies the data-pack processors attached to a jigsaw pool element. Koil
     * keeps these detached: state-only processors are evaluated directly, while
     * world-height processors are intentionally left to the scene placement
     * origin because there is no hidden StructureWorldAccess.
     */
    private static BlockState applyProcessors(BlockState input, Scene scene, SceneCellPos target,
                                              JsonElement processorRef, long seed, Int3 world) {
        BlockState state = input;
        for (JsonObject processor : resolveProcessors(processorRef)) {
            if (state == null || state.isAir()) return Blocks.AIR.getDefaultState();
            String type = jsonString(processor, "processor_type", "").toLowerCase(Locale.ROOT);
            long localSeed = mix(seed, (((long) world.x) << 42) ^ (((long) world.y) << 21) ^ world.z ^ type.hashCode());
            if (type.endsWith(":nop") || type.endsWith(":jigsaw_replacement") || type.endsWith(":gravity")) continue;
            if (type.endsWith(":block_ignore")) {
                JsonElement blocks = processor.get("blocks");
                if (matchesBlockList(state, blocks)) return Blocks.AIR.getDefaultState();
                continue;
            }
            if (type.endsWith(":block_rot")) {
                float integrity = jsonFloat(processor, "integrity", 1.0F);
                if (hash01(localSeed) > Math.max(0.0F, Math.min(1.0F, integrity))) return Blocks.AIR.getDefaultState();
                continue;
            }
            if (type.endsWith(":rule")) {
                JsonArray rules = processor.has("rules") && processor.get("rules").isJsonArray()
                        ? processor.getAsJsonArray("rules") : null;
                if (rules == null) continue;
                int ruleIndex = 0;
                for (JsonElement rawRule : rules) {
                    if (!rawRule.isJsonObject()) { ruleIndex++; continue; }
                    JsonObject rule = rawRule.getAsJsonObject();
                    long ruleSeed = mix(localSeed, ruleIndex++ * 0x9E3779B97F4A7C15L);
                    if (!matchesRulePredicate(state, rule.get("input_predicate"), ruleSeed)) continue;
                    if (!matchesLocationPredicate(scene, target, rule.get("location_predicate"), ruleSeed)) continue;
                    BlockState output = stateFromJson(rule.get("output_state"));
                    if (output != null) state = output;
                    break;
                }
            }
        }
        return state;
    }

    private static List<JsonObject> resolveProcessors(JsonElement reference) {
        List<JsonObject> out = new ArrayList<>();
        if (reference == null || reference.isJsonNull()) return out;
        JsonElement value = reference;
        if (reference.isJsonPrimitive() && reference.getAsJsonPrimitive().isString()) {
            Identifier id = identifier(reference.getAsString());
            if (id == null || isEmptyPool(id)) return out;
            SpriteWorldgenCatalog.Entry entry = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.PROCESSOR_LIST, id).orElse(null);
            JsonObject root = entry == null ? null : SpriteWorldgenCatalog.readJson(entry).orElse(null);
            if (root == null) return out;
            value = root;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("processors") && object.get("processors").isJsonArray()) value = object.get("processors");
            else if (object.has("processor_type")) { out.add(object); return out; }
        }
        if (value.isJsonArray()) for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonObject()) out.add(element.getAsJsonObject());
        }
        return out;
    }

    private static boolean matchesRulePredicate(BlockState state, JsonElement element, long seed) {
        if (element == null || !element.isJsonObject()) return true;
        JsonObject predicate = element.getAsJsonObject();
        String type = jsonString(predicate, "predicate_type", "").toLowerCase(Locale.ROOT);
        if (type.endsWith(":always_true") || type.isBlank()) return true;
        Identifier blockId = identifier(jsonString(predicate, "block", ""));
        if (type.endsWith(":block_match")) return blockId != null && Registries.BLOCK.getId(state.getBlock()).equals(blockId);
        if (type.endsWith(":random_block_match")) {
            if (blockId == null || !Registries.BLOCK.getId(state.getBlock()).equals(blockId)) return false;
            float probability = jsonFloat(predicate, "probability", 1.0F);
            return hash01(seed) < Math.max(0.0F, Math.min(1.0F, probability));
        }
        if (type.endsWith(":blockstate_match") || type.endsWith(":random_blockstate_match")) {
            BlockState expected = stateFromJson(predicate.get("block_state"));
            if (expected == null || !state.equals(expected)) return false;
            if (type.endsWith(":random_blockstate_match")) {
                float probability = jsonFloat(predicate, "probability", 1.0F);
                return hash01(seed) < Math.max(0.0F, Math.min(1.0F, probability));
            }
            return true;
        }
        if (type.endsWith(":tag_match")) {
            Identifier tagId = identifier(jsonString(predicate, "tag", ""));
            return tagId != null && state.isIn(TagKey.of(RegistryKeys.BLOCK, tagId));
        }
        return false;
    }

    private static boolean matchesLocationPredicate(Scene scene, SceneCellPos target, JsonElement element, long seed) {
        if (element == null || element.isJsonNull()) return true;
        if (!element.isJsonObject()) return false;
        JsonObject predicate = element.getAsJsonObject();
        String type = jsonString(predicate, "predicate_type", "").toLowerCase(Locale.ROOT);
        if (type.endsWith(":always_true") || type.isBlank()) return true;
        BlockState existing = scene == null || target == null ? Blocks.AIR.getDefaultState() : scene.blocks().getBlockState(target);
        return matchesRulePredicate(existing, element, seed);
    }

    private static boolean matchesBlockList(BlockState state, JsonElement element) {
        if (state == null || element == null) return false;
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (element.isJsonPrimitive()) {
            Identifier expected = identifier(element.getAsString());
            return expected != null && expected.equals(id);
        }
        if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) {
            if (matchesBlockList(state, child)) return true;
        }
        return false;
    }

    private static BlockState stateFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String name = jsonString(object, "Name", jsonString(object, "name", ""));
        Identifier id = identifier(name);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        JsonObject props = object.has("Properties") && object.get("Properties").isJsonObject()
                ? object.getAsJsonObject("Properties")
                : object.has("properties") && object.get("properties").isJsonObject()
                ? object.getAsJsonObject("properties") : null;
        if (props != null) for (var entry : props.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                state = MinecraftStateCodec.withProperty(state, entry.getKey(), entry.getValue().getAsString());
            }
        }
        return state;
    }

    private static float jsonFloat(JsonObject object, String key, float fallback) {
        if (object == null || !object.has(key)) return fallback;
        try { return object.get(key).getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static float hash01(long seed) {
        long value = mix(seed, 0xA0761D6478BD642FL);
        return ((value >>> 40) & 0xFFFFFFL) / (float) 0x1000000L;
    }

    private static BlockState finalState(NbtCompound nbt) {
        if (nbt == null) return Blocks.AIR.getDefaultState();
        String value = nbt.getString("final_state");
        if (value == null || value.isBlank()) return Blocks.AIR.getDefaultState();
        return stateFromString(value);
    }

    private static BlockState stateFromString(String value) {
        if (value == null || value.isBlank()) return Blocks.AIR.getDefaultState();
        String raw = value.trim();
        int bracket = raw.indexOf('[');
        String idText = bracket >= 0 ? raw.substring(0, bracket) : raw;
        Identifier id = identifier(idText);
        if (id == null || !Registries.BLOCK.containsId(id)) return Blocks.AIR.getDefaultState();
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        if (bracket >= 0 && raw.endsWith("]")) {
            String properties = raw.substring(bracket + 1, raw.length() - 1);
            for (String assignment : properties.split(",")) {
                int eq = assignment.indexOf('=');
                if (eq <= 0) continue;
                state = MinecraftStateCodec.withProperty(state,
                        assignment.substring(0, eq).trim(), assignment.substring(eq + 1).trim());
            }
        }
        return state;
    }

    private static BlockState stateFromPalette(NbtCompound nbt) {
        if (nbt == null) return Blocks.AIR.getDefaultState();
        Identifier id = identifier(nbt.getString("Name"));
        if (id == null || !Registries.BLOCK.containsId(id)) return Blocks.AIR.getDefaultState();
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        if (nbt.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            NbtCompound props = nbt.getCompound("Properties");
            for (String key : props.getKeys()) state = MinecraftStateCodec.withProperty(state, key, props.getString(key));
        }
        return state;
    }

    /** Structures are full native volumes anchored on the selected edit plane. */
    private static int structurePlane(SpriteWorldgenGenerator.Request request) {
        return request == null ? 0 : request.editPlane();
    }


    private static BlockState rotateState(BlockState state, int rotation) {
        if (state == null) return null;
        BlockRotation blockRotation = switch (Math.floorMod(rotation, 4)) {
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
        try {
            return state.rotate(blockRotation);
        } catch (RuntimeException ignored) {
            // Modded blocks are allowed to reject an unexpected transform. Keep
            // their authored state rather than aborting the entire structure.
            return state;
        }
    }

    private static Int3 transform(Int3 p, int sizeX, int sizeZ, int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> new Int3(p.z, p.y, sizeX - 1 - p.x);
            case 2 -> new Int3(sizeX - 1 - p.x, p.y, sizeZ - 1 - p.z);
            case 3 -> new Int3(sizeZ - 1 - p.z, p.y, p.x);
            default -> p;
        };
    }

    private static Direction orientationFront(String orientation) {
        if (orientation == null || orientation.isBlank()) return Direction.NORTH;
        String front = orientation.toLowerCase(Locale.ROOT).split("_")[0];
        return switch (front) {
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> Direction.NORTH;
        };
    }

    private static Direction rotateHorizontal(Direction direction, int rotation) {
        if (direction == null || direction.getAxis().isVertical()) return direction == null ? Direction.NORTH : direction;
        Direction result = direction;
        for (int i = 0; i < Math.floorMod(rotation, 4); i++) result = result.rotateYClockwise();
        return result;
    }

    private static Int3 vector(Direction direction) {
        if (direction == null) return new Int3(0, 0, -1);
        return new Int3(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static void clearCell(Scene scene, SceneCellPos pos) {
        if (scene.blocks().occupied(pos)) scene.blocks().remove(pos);
        if (!scene.fluids().getFluidState(pos).isEmpty()) scene.fluids().remove(pos);
    }

    private static int[] structureSize(NbtCompound nbt) {
        if (nbt == null) return new int[0];
        int[] packed = nbt.getIntArray("size");
        if (packed.length >= 3) return packed;
        NbtList listed = nbt.getList("size", NbtElement.INT_TYPE);
        if (listed.size() >= 3) return new int[]{listed.getInt(0), listed.getInt(1), listed.getInt(2)};
        return packed;
    }

    private static boolean isEmptyPool(Identifier id) {
        return id != null && id.getNamespace().equals("minecraft") && id.getPath().equals("empty");
    }

    private static Identifier identifier(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new Identifier(value.trim()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String jsonString(JsonObject object, String key, String fallback) {
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try { return object.get(key).getAsString(); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static long mix(long seed, long salt) {
        long x = seed ^ salt ^ 0x9E3779B97F4A7C15L;
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private record Int3(int x, int y, int z) {
        Int3 add(Int3 other) { return new Int3(x + other.x, y + other.y, z + other.z); }
        Int3 subtract(Int3 other) { return new Int3(x - other.x, y - other.y, z - other.z); }
    }
    private record Connector(Int3 local, String name, String target, Identifier pool, String finalState, Direction front) { }
    private record TemplateBlock(Int3 local, BlockState state, NbtCompound nbt) { }
    private record TemplateData(Identifier id, int sizeX, int sizeY, int sizeZ,
                                List<TemplateBlock> blocks, List<Connector> connectors) {
        int rotatedSizeX(int rotation) { return Math.floorMod(rotation, 2) == 0 ? sizeX : sizeZ; }
        int rotatedSizeZ(int rotation) { return Math.floorMod(rotation, 2) == 0 ? sizeZ : sizeX; }
    }
    private record Piece(TemplateData template, Int3 origin, int rotation, int depth, int consumedConnector, JsonElement processors, long seed) { }
    private record Attachment(int connectorIndex, int rotation, Int3 origin) { }
    private record Placement(int blocks, SceneCellPos focus) { }
    private record PoolChoice(Identifier templateId, JsonElement processors) { }
    private record WeightedElement(Identifier templateId, JsonElement processors, int weight) { }
    private record Box3(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean intersects(Box3 other) {
            return other != null && minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }
}
