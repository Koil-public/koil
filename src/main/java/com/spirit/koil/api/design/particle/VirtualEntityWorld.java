package com.spirit.koil.api.design.particle;

import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative virtual-entity simulation for Koil's sprite world.
 *
 * <p>Particles remain the rendering/input surface. This class owns entity identity,
 * health, age, status effects, queries, portal cooldowns, death/game events and
 * spawn requests at a deterministic 20 TPS. Blocks interact with this service
 * instead of writing health/cooldown fields independently.</p>
 */
public final class VirtualEntityWorld {
    public static final int TICKS_PER_SECOND = 20;
    private static final float TICK_SECONDS = 1.0F / TICKS_PER_SECOND;

    public enum Category {
        ITEM,
        PLAYER,
        LIVING,
        PROJECTILE,
        MINECART,
        PRIMED_TNT,
        GENERIC
    }

    public interface EntityNode {
        long id();
        boolean itemNode();
        float x();
        float y();
        float halfWidth();
        float halfHeight();
        float velocityX();
        float velocityY();
        void position(float x, float y);
        void velocity(float vx, float vy);
        int simulationLayer();
        void simulationLayer(int simulationLayer);
        boolean dragging();
        boolean removed();
        void remove();
        boolean hasTag(String tag);
        String data(String key);
        void data(String key, Object value);
        void playSound(SoundEvent event, float volume, float pitch);
    }

    public interface Host {
        void spawnEntity(String entityTypeId, float x, float y, int simulationLayer, Map<String, String> data);
    }

    public record EntitySnapshot(
            long id,
            UUID uuid,
            String entityTypeId,
            Category category,
            float x,
            float y,
            int simulationLayer,
            float health,
            float maxHealth,
            long ageTicks,
            boolean alive
    ) { }

    public record GameEvent(String kind, long sourceId, float x, float y, int simulationLayer, int frequency) { }

    private static final class RuntimeEntity {
        private final long id;
        private final UUID uuid;
        private EntityNode node;
        private String entityTypeId;
        private Category category;
        private float health;
        private float maxHealth;
        private long ageTicks;
        private long lastDamageTick = Long.MIN_VALUE;
        private long portalCooldownUntil;
        private int previousCellX;
        private int previousCellZ;
        private boolean previousCellKnown;
        private final Map<String, StatusEffectState> effects = new LinkedHashMap<>();

        private RuntimeEntity(long id) {
            this.id = id;
            this.uuid = UUID.nameUUIDFromBytes(("koil-virtual-entity:" + id).getBytes(StandardCharsets.UTF_8));
        }
    }

    private record StatusEffectState(int amplifier, long expiresAtTick) { }

    private final Map<Long, RuntimeEntity> entities = new LinkedHashMap<>();
    private final Map<CellKey, LinkedHashSet<Long>> byCell = new HashMap<>();
    private final List<GameEvent> pendingEvents = new ArrayList<>();
    private float accumulator;
    private float cellPixels = 16.0F;
    private long worldTick;
    private VirtualServerContext serverContext = new VirtualServerContext();

    private record CellKey(int x, int y, int z) { }

    public void setServerContext(VirtualServerContext serverContext) {
        this.serverContext = serverContext == null ? new VirtualServerContext() : serverContext;
    }

    public VirtualServerContext serverContext() { return serverContext; }
    public long worldTick() { return worldTick; }
    public int size() { return entities.size(); }

    public void setCellPixels(float cellPixels) {
        this.cellPixels = Math.max(6.0F, Math.min(64.0F, cellPixels));
    }

    public void clear() {
        entities.clear();
        byCell.clear();
        pendingEvents.clear();
        accumulator = 0.0F;
        worldTick = 0L;
    }

    public void tick(Collection<? extends EntityNode> nodes, Host host, float deltaSeconds) {
        reconcile(nodes);
        accumulator += Math.max(0.0F, Math.min(0.25F, deltaSeconds));
        int safety = 0;
        while (accumulator >= TICK_SECONDS && safety++ < 5) {
            accumulator -= TICK_SECONDS;
            worldTick++;
            serverContext.worldTick(worldTick);
            step(host);
        }
        publishState();
    }

    private void reconcile(Collection<? extends EntityNode> nodes) {
        byCell.clear();
        Set<Long> seen = new LinkedHashSet<>();
        if (nodes != null) {
            List<EntityNode> ordered = new ArrayList<>(nodes);
            ordered.sort(Comparator.comparingLong(EntityNode::id));
            for (EntityNode node : ordered) {
                if (node == null || node.removed()) continue;
                seen.add(node.id());
                RuntimeEntity runtime = entities.computeIfAbsent(node.id(), RuntimeEntity::new);
                runtime.node = node;
                runtime.entityTypeId = resolveEntityType(node);
                runtime.category = resolveCategory(node, runtime.entityTypeId);
                runtime.maxHealth = Math.max(1.0F, floatData(node.data("__koil_vw_max_health"), defaultMaxHealth(runtime.category)));
                runtime.health = Math.max(0.0F, Math.min(runtime.maxHealth,
                        floatData(node.data("__koil_vw_health"), runtime.health > 0.0F ? runtime.health : runtime.maxHealth)));
                index(runtime);
            }
        }
        entities.entrySet().removeIf(entry -> !seen.contains(entry.getKey()) || entry.getValue().node == null || entry.getValue().node.removed());
    }

    private void index(RuntimeEntity runtime) {
        EntityNode node = runtime.node;
        // Match KoilVirtualBlockWorld's half-cell-centered boundary convention.
        // A visual center at x=0/y=0 therefore belongs to logical cell 0 in both
        // services, including actors whose AABB spans an adjacent cell boundary.
        float halfCell = cellPixels * 0.5F;
        float hw = Math.max(0.5F, Math.min(cellPixels * 2.0F, node.halfWidth()));
        float hh = Math.max(0.5F, Math.min(cellPixels * 2.0F, node.halfHeight()));
        final float edgeEpsilon = 0.001F;
        int minX = (int) Math.floor((node.x() - hw + halfCell) / cellPixels);
        int maxX = (int) Math.floor((node.x() + hw - edgeEpsilon + halfCell) / cellPixels);
        int minZ = (int) Math.floor((node.y() - hh + halfCell) / cellPixels);
        int maxZ = (int) Math.floor((node.y() + hh - edgeEpsilon + halfCell) / cellPixels);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                byCell.computeIfAbsent(new CellKey(x, node.simulationLayer(), z), ignored -> new LinkedHashSet<>()).add(runtime.id);
            }
        }
    }

    private void step(Host host) {
        for (RuntimeEntity runtime : new ArrayList<>(entities.values())) {
            if (runtime.node == null || runtime.node.removed()) continue;
            runtime.ageTicks++;
            runtime.effects.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= worldTick);
            collectExplicitEvent(runtime);
            collectMovementEvent(runtime);

            if (runtime.category == Category.PRIMED_TNT) {
                int fuse = intData(runtime.node.data("__koil_vw_fuse"), 80);
                fuse--;
                runtime.node.data("__koil_vw_fuse", Math.max(0, fuse));
                if (fuse <= 0) {
                    runtime.node.playSound(SoundEvents.ENTITY_GENERIC_EXPLODE, 0.8F, 0.95F);
                    emitEvent("primed_tnt_expire", runtime.id, runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(), 15);
                    runtime.node.remove();
                }
            }
        }
    }

    private void collectExplicitEvent(RuntimeEntity runtime) {
        String explicit = runtime.node.data("__koil_vw_game_event");
        if (explicit == null || explicit.isBlank()) return;
        emitEvent(explicit.trim().toLowerCase(Locale.ROOT), runtime.id, runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(),
                clamp(intData(runtime.node.data("__koil_vw_vibration_frequency"), defaultFrequency(runtime.category)), 1, 15));
        runtime.node.data("__koil_vw_game_event", null);
    }

    private void collectMovementEvent(RuntimeEntity runtime) {
        int x = Math.round(runtime.node.x() / cellPixels);
        int z = Math.round(runtime.node.y() / cellPixels);
        float speedSq = runtime.node.velocityX() * runtime.node.velocityX() + runtime.node.velocityY() * runtime.node.velocityY();
        boolean movedCell = runtime.previousCellKnown && (runtime.previousCellX != x || runtime.previousCellZ != z);
        if ((runtime.node.dragging() || speedSq >= 9.0F || movedCell) && runtime.category != Category.ITEM) {
            emitEvent(movementKind(runtime.category), runtime.id, runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(), defaultFrequency(runtime.category));
        } else if ((runtime.node.dragging() || speedSq >= 9.0F || movedCell) && runtime.category == Category.ITEM) {
            emitEvent("item_movement", runtime.id, runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(), 3);
        }
        runtime.previousCellKnown = true;
        runtime.previousCellX = x;
        runtime.previousCellZ = z;
    }

    public boolean damage(long entityId, float amount, String source, int hurtCooldownTicks) {
        RuntimeEntity runtime = entities.get(entityId);
        if (runtime == null || runtime.node == null || runtime.node.removed() || amount <= 0.0F) return false;
        if (runtime.lastDamageTick != Long.MIN_VALUE && worldTick - runtime.lastDamageTick < Math.max(0, hurtCooldownTicks)) return false;
        runtime.lastDamageTick = worldTick;
        runtime.health = Math.max(0.0F, runtime.health - amount);
        runtime.node.data("__koil_vw_health", runtime.health);
        runtime.node.data("__koil_vw_last_damage", amount);
        runtime.node.data("__koil_vw_last_damage_source", source == null ? "virtual_world" : source);
        runtime.node.data("__koil_vw_last_damage_tick", worldTick);
        if (runtime.category == Category.PLAYER || runtime.category == Category.LIVING) {
            runtime.node.playSound(runtime.health <= 0.0F ? SoundEvents.ENTITY_GENERIC_DEATH : SoundEvents.ENTITY_GENERIC_HURT, 0.3F, 1.0F);
        }
        if (runtime.health <= 0.0F) {
            emitEvent("entity_death", runtime.id, runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(), 15);
            runtime.node.remove();
        }
        return true;
    }

    public boolean heal(long entityId, float amount) {
        RuntimeEntity runtime = entities.get(entityId);
        if (runtime == null || amount <= 0.0F || runtime.node == null || runtime.node.removed()) return false;
        runtime.health = Math.min(runtime.maxHealth, runtime.health + amount);
        runtime.node.data("__koil_vw_health", runtime.health);
        return true;
    }

    public boolean teleport(long entityId, float x, float y, int simulationLayer, int cooldownTicks, String destination) {
        RuntimeEntity runtime = entities.get(entityId);
        if (runtime == null || runtime.node == null || runtime.node.removed()) return false;
        if (worldTick < runtime.portalCooldownUntil) return false;
        runtime.node.position(x, y);
        runtime.node.simulationLayer(simulationLayer);
        runtime.node.velocity(0.0F, 0.0F);
        runtime.portalCooldownUntil = worldTick + Math.max(1, cooldownTicks);
        runtime.node.data("__koil_vw_portal_cooldown", runtime.portalCooldownUntil);
        if (destination != null) runtime.node.data("__koil_vw_portal_destination", destination);
        emitEvent("entity_teleport", runtime.id, x, y, simulationLayer, 5);
        return true;
    }

    public boolean applyStatusEffect(long entityId, String effectId, int durationTicks, int amplifier) {
        RuntimeEntity runtime = entities.get(entityId);
        if (runtime == null || runtime.node == null || runtime.node.removed() || effectId == null || effectId.isBlank()) return false;
        String key = effectId.trim().toLowerCase(Locale.ROOT);
        runtime.effects.put(key, new StatusEffectState(Math.max(0, amplifier), worldTick + Math.max(1, durationTicks)));
        runtime.node.data("__koil_vw_effect_" + key.replace(':', '_'), Math.max(0, durationTicks));
        return true;
    }

    public List<EntitySnapshot> queryRadius(float x, float y, int simulationLayer, float radius) {
        float r = Math.max(0.0F, radius);
        float rSq = r * r;
        List<EntitySnapshot> result = new ArrayList<>();
        for (RuntimeEntity runtime : entities.values()) {
            if (runtime.node == null || runtime.node.removed() || runtime.node.simulationLayer() != simulationLayer) continue;
            float dx = runtime.node.x() - x;
            float dy = runtime.node.y() - y;
            if (dx * dx + dy * dy > rSq) continue;
            result.add(snapshot(runtime));
        }
        return Collections.unmodifiableList(result);
    }

    public List<EntitySnapshot> queryCategory(float x, float y, int simulationLayer, float radius, Category category) {
        List<EntitySnapshot> result = new ArrayList<>();
        for (EntitySnapshot snapshot : queryRadius(x, y, simulationLayer, radius)) {
            if (category == null || snapshot.category() == category) result.add(snapshot);
        }
        return Collections.unmodifiableList(result);
    }

    public EntitySnapshot snapshot(long entityId) {
        RuntimeEntity runtime = entities.get(entityId);
        return runtime == null ? null : snapshot(runtime);
    }

    private EntitySnapshot snapshot(RuntimeEntity runtime) {
        return new EntitySnapshot(runtime.id, runtime.uuid, runtime.entityTypeId, runtime.category,
                runtime.node.x(), runtime.node.y(), runtime.node.simulationLayer(), runtime.health,
                runtime.maxHealth, runtime.ageTicks, runtime.node != null && !runtime.node.removed() && runtime.health > 0.0F);
    }

    public List<GameEvent> drainGameEvents() {
        if (pendingEvents.isEmpty()) return List.of();
        List<GameEvent> copy = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return copy;
    }

    public void emitEvent(String kind, long sourceId, float x, float y, int simulationLayer, int frequency) {
        if (kind == null || kind.isBlank()) return;
        pendingEvents.add(new GameEvent(kind.trim().toLowerCase(Locale.ROOT), sourceId, x, y, simulationLayer, clamp(frequency, 1, 15)));
    }

    public boolean spawn(Host host, String entityTypeId, float x, float y, int simulationLayer, Map<String, String> data) {
        if (host == null || entityTypeId == null || entityTypeId.isBlank()) return false;
        host.spawnEntity(entityTypeId.trim().toLowerCase(Locale.ROOT), x, y, simulationLayer,
                data == null ? Map.of() : new LinkedHashMap<>(data));
        return true;
    }

    private void publishState() {
        for (RuntimeEntity runtime : entities.values()) {
            if (runtime.node == null || runtime.node.removed()) continue;
            runtime.node.data("__koil_virtual_entity_managed", true);
            runtime.node.data("__koil_vw_entity_uuid", runtime.uuid.toString());
            runtime.node.data("__koil_vw_entity_type", runtime.entityTypeId);
            runtime.node.data("__koil_vw_entity_category", runtime.category.name().toLowerCase(Locale.ROOT));
            runtime.node.data("__koil_vw_health", runtime.health);
            runtime.node.data("__koil_vw_max_health", runtime.maxHealth);
            runtime.node.data("__koil_vw_entity_age_ticks", runtime.ageTicks);
            runtime.node.data("__koil_vw_portal_cooldown", runtime.portalCooldownUntil);
            runtime.node.data("__koil_vw_status_effects", String.join("|", runtime.effects.keySet()));
        }
    }

    private static String resolveEntityType(EntityNode node) {
        String explicit = node.data("__koil_vw_entity_type");
        if (explicit != null && !explicit.isBlank()) return explicit.trim().toLowerCase(Locale.ROOT);
        if (node.hasTag("entity:player")) return "minecraft:player";
        if (node.hasTag("entity:minecart") || node.hasTag("minecart_item")) return "minecraft:minecart";
        if (node.hasTag("entity:projectile") || node.hasTag("projectile_item")) return "minecraft:projectile";
        if (node.itemNode()) return "minecraft:item";
        return "koil:virtual_entity";
    }

    private static Category resolveCategory(EntityNode node, String type) {
        if (node.itemNode()) return Category.ITEM;
        if (node.hasTag("entity:player") || "minecraft:player".equals(type)) return Category.PLAYER;
        if (node.hasTag("entity:minecart") || node.hasTag("minecart_item") || type.endsWith("minecart")) return Category.MINECART;
        if (node.hasTag("entity:projectile") || node.hasTag("projectile_item")) return Category.PROJECTILE;
        if (node.hasTag("entity:primed_tnt") || "minecraft:tnt".equals(type)) return Category.PRIMED_TNT;
        if (node.hasTag("entity:living") || node.hasTag("living_entity")) return Category.LIVING;
        String explicitCategory = node.data("__koil_vw_entity_category");
        if (explicitCategory != null && !explicitCategory.isBlank()) {
            try { return Category.valueOf(explicitCategory.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        // Entity factory requests for ordinary Minecraft mob ids default to living
        // actors unless a more specific category was declared above.
        if (type != null && type.startsWith("minecraft:")
                && !"minecraft:item".equals(type) && !"minecraft:experience_orb".equals(type)) return Category.LIVING;
        return Category.GENERIC;
    }

    private static float defaultMaxHealth(Category category) {
        return switch (category) {
            case ITEM -> 5.0F;
            case PRIMED_TNT -> 1.0F;
            default -> 20.0F;
        };
    }

    private static int defaultFrequency(Category category) {
        return switch (category) {
            case PROJECTILE -> 2;
            case MINECART -> 6;
            case PLAYER -> 4;
            case LIVING -> 4;
            case ITEM -> 3;
            case PRIMED_TNT -> 15;
            default -> 1;
        };
    }

    private static String movementKind(Category category) {
        return switch (category) {
            case PLAYER -> "player_movement";
            case PROJECTILE -> "projectile";
            case MINECART -> "minecart";
            case ITEM -> "item_movement";
            default -> "entity_movement";
        };
    }

    private static int intData(String value, int fallback) {
        try { return value == null || value.isBlank() ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static float floatData(String value, float fallback) {
        try { return value == null || value.isBlank() ? fallback : Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
