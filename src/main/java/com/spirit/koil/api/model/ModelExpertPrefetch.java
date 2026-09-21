package com.spirit.koil.api.model;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded, read-only first-round world intelligence for Expert Prefetch.
 *
 * <p>This snapshot is intentionally broader than a single gameplay tool query,
 * but narrower than a full world inspection. It gives the model enough
 * client-visible state to avoid obvious first-round discovery calls while
 * preserving the normal Koil evidence loop for exact, changed, ambiguous, or
 * exhaustive state.</p>
 *
 * <p>Important boundaries:</p>
 * <ul>
 *     <li>No world mutation, packet sending, command execution, or KTL work.</li>
 *     <li>No server internals. Only state already visible to the client is read.</li>
 *     <li>Inventory and nearby-entity summaries are bounded and explicitly
 *     marked truncated when they are not exhaustive.</li>
 *     <li>The snapshot is initial evidence only. Any action, delay, movement,
 *     inventory change, target change, or world change can make it stale.</li>
 *     <li>Dynamic names are registry identifiers wherever possible, reducing
 *     prompt noise and preventing display-name text from acting like
 *     instructions.</li>
 * </ul>
 */
final class ModelExpertPrefetch {
    static final int MAXIMUM_CAPTURE_CHARACTERS = 8_192;
    static final int MAXIMUM_INVENTORY_TYPES = 18;
    static final int MAXIMUM_NEARBY_ENTITIES = 10;
    static final int MAXIMUM_STATUS_EFFECTS = 10;
    static final double NEARBY_ENTITY_RADIUS = 16.0D;
    static final int CLIENT_THREAD_CAPTURE_TIMEOUT_MILLIS = 250;

    private static final String UNAVAILABLE = "unavailable";

    private ModelExpertPrefetch() {
    }

    /**
     * Captures a prompt-conditioned first-round expert packet. The objective is
     * classified locally and deterministically so irrelevant live-state domains
     * are not inserted into the model context.
     */
    static String capture(String userPrompt) {
        PrefetchPlan plan = PrefetchPlan.forPrompt(userPrompt);
        if (plan.isEmpty()) {
            return "";
        }
        return captureSnapshot(plan).render(plan);
    }

    /**
     * Typed full snapshot retained for tests and diagnostics. Runtime prompting
     * should call {@link #capture(String)} so the objective can shape the packet.
     */
    static Snapshot captureSnapshot() {
        return captureSnapshot(PrefetchPlan.full());
    }

    static Snapshot captureSnapshot(PrefetchPlan plan) {
        MinecraftClient client = MinecraftClient.getInstance();
        long requestedAtMillis = System.currentTimeMillis();

        if (client == null) {
            return Snapshot.unavailable(requestedAtMillis, "minecraft_client_unavailable");
        }
        if (client.isOnThread()) {
            return captureOnClientThread(client, requestedAtMillis, plan);
        }

        CompletableFuture<Snapshot> future = new CompletableFuture<>();
        try {
            client.execute(() -> {
                try {
                    future.complete(captureOnClientThread(client, System.currentTimeMillis(), plan));
                } catch (RuntimeException failure) {
                    future.complete(Snapshot.unavailable(
                            System.currentTimeMillis(),
                            "client_thread_capture_failed:" + failureName(failure)
                    ));
                }
            });
            return future.get(CLIENT_THREAD_CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Snapshot.unavailable(requestedAtMillis, "client_thread_capture_interrupted");
        } catch (TimeoutException timeout) {
            return Snapshot.unavailable(requestedAtMillis, "client_thread_capture_timeout");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            return Snapshot.unavailable(
                    requestedAtMillis,
                    "client_thread_capture_failed:"
                            + (cause == null ? failure.getClass().getSimpleName() : cause.getClass().getSimpleName())
            );
        } catch (RuntimeException failure) {
            return Snapshot.unavailable(
                    requestedAtMillis,
                    "client_thread_dispatch_failed:" + failureName(failure)
            );
        }
    }

    private static Snapshot captureOnClientThread(
            MinecraftClient client,
            long capturedAtMillis,
            PrefetchPlan plan
    ) {
        if (client.player == null || client.world == null) {
            return Snapshot.unavailable(capturedAtMillis, "player_or_world_unavailable");
        }

        List<String> warnings = new ArrayList<>();
        PlayerSnapshot player = plan.captures(Domain.PLAYER) ? capturePlayer(client, warnings) : PlayerSnapshot.unavailable();
        WorldSnapshot world = plan.captures(Domain.WORLD) ? captureWorld(client, warnings) : WorldSnapshot.unavailable();
        EquipmentSnapshot equipment = plan.captures(Domain.EQUIPMENT) ? captureEquipment(client, warnings) : EquipmentSnapshot.unavailable();
        InventorySnapshot inventory = plan.captures(Domain.INVENTORY) ? captureInventory(client, warnings) : InventorySnapshot.unavailable();
        TargetSnapshot target = plan.captures(Domain.TARGET) ? captureTarget(client, warnings) : TargetSnapshot.unavailable();
        EnvironmentSnapshot environment = plan.captures(Domain.ENVIRONMENT) ? captureEnvironment(client, warnings) : EnvironmentSnapshot.unavailable();
        NearbySnapshot nearby = plan.captures(Domain.NEARBY) ? captureNearby(client, warnings) : NearbySnapshot.unavailable();
        ReadinessSnapshot readiness = plan.needs(Domain.READINESS)
                ? deriveReadiness(player, equipment, inventory)
                : ReadinessSnapshot.unavailable();

        return new Snapshot(
                true,
                capturedAtMillis,
                "",
                player,
                world,
                equipment,
                inventory,
                target,
                environment,
                nearby,
                readiness,
                List.copyOf(warnings)
        );
    }

    private static PlayerSnapshot capturePlayer(MinecraftClient client, List<String> warnings) {
        try {
            var player = client.player;
            BlockPos blockPos = player.getBlockPos();
            Vec3d velocity = player.getVelocity();
            String vehicle = player.getVehicle() == null
                    ? "none"
                    : entityTypeId(player.getVehicle());

            List<String> effects = new ArrayList<>();
            boolean effectsTruncated = false;
            int index = 0;
            for (StatusEffectInstance effect : player.getStatusEffects()) {
                if (index++ >= MAXIMUM_STATUS_EFFECTS) {
                    effectsTruncated = true;
                    break;
                }
                effects.add(
                        statusEffectId(effect)
                                + ":amp=" + effect.getAmplifier()
                                + ":duration=" + effect.getDuration() + "t"
                );
            }

            String gameMode = client.interactionManager == null
                    ? "unknown"
                    : client.interactionManager.getCurrentGameMode().getName();

            return new PlayerSnapshot(
                    round(player.getX()),
                    round(player.getY()),
                    round(player.getZ()),
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    player.getHorizontalFacing().asString(),
                    round(player.getYaw()),
                    round(player.getPitch()),
                    round(velocity.x),
                    round(velocity.y),
                    round(velocity.z),
                    round(horizontalSpeed(velocity)),
                    player.isOnGround(),
                    player.isSprinting(),
                    player.isSneaking(),
                    player.isSwimming(),
                    player.isFallFlying(),
                    player.isTouchingWater(),
                    player.isSubmergedInWater(),
                    player.isInLava(),
                    player.getAbilities().flying,
                    player.getAbilities().allowFlying,
                    round(player.fallDistance),
                    round(player.getHealth()),
                    round(player.getMaxHealth()),
                    player.getHungerManager().getFoodLevel(),
                    round(player.getHungerManager().getSaturationLevel()),
                    round(player.getHungerManager().getExhaustion()),
                    player.getArmor(),
                    player.getAir(),
                    player.getMaxAir(),
                    player.getFireTicks(),
                    player.experienceLevel,
                    gameMode,
                    player.getInventory().selectedSlot,
                    vehicle,
                    List.copyOf(effects),
                    effectsTruncated
            );
        } catch (RuntimeException failure) {
            warnings.add("player_snapshot_failed:" + failureName(failure));
            return PlayerSnapshot.unavailable();
        }
    }

    private static WorldSnapshot captureWorld(MinecraftClient client, List<String> warnings) {
        try {
            BlockPos pos = client.player.getBlockPos();
            String biome = client.world.getBiome(pos).getKey()
                    .map(key -> key.getValue().toString())
                    .orElse("unknown");
            long time = client.world.getTimeOfDay();
            String weather = client.world.isThundering()
                    ? "thunder"
                    : client.world.isRaining() ? "rain" : "clear";
            String connection = client.isInSingleplayer()
                    ? "singleplayer"
                    : client.getCurrentServerEntry() != null ? "multiplayer" : "client";

            return new WorldSnapshot(
                    client.world.getRegistryKey().getValue().toString(),
                    biome,
                    time,
                    Math.floorDiv(time, 24_000L),
                    weather,
                    client.world.getDifficulty().getName(),
                    client.world.getMoonPhase(),
                    client.world.getBottomY(),
                    client.world.getTopY(),
                    client.world.getLightLevel(LightType.BLOCK, pos),
                    client.world.getLightLevel(LightType.SKY, pos),
                    client.world.getLightLevel(pos),
                    client.world.isSkyVisible(pos),
                    connection
            );
        } catch (RuntimeException failure) {
            warnings.add("world_snapshot_failed:" + failureName(failure));
            return WorldSnapshot.unavailable();
        }
    }

    private static EquipmentSnapshot captureEquipment(MinecraftClient client, List<String> warnings) {
        try {
            var player = client.player;
            return new EquipmentSnapshot(
                    stackSummary(player.getMainHandStack()),
                    stackSummary(player.getOffHandStack()),
                    stackSummary(player.getEquippedStack(EquipmentSlot.HEAD)),
                    stackSummary(player.getEquippedStack(EquipmentSlot.CHEST)),
                    stackSummary(player.getEquippedStack(EquipmentSlot.LEGS)),
                    stackSummary(player.getEquippedStack(EquipmentSlot.FEET))
            );
        } catch (RuntimeException failure) {
            warnings.add("equipment_snapshot_failed:" + failureName(failure));
            return EquipmentSnapshot.unavailable();
        }
    }

    private static InventorySnapshot captureInventory(MinecraftClient client, List<String> warnings) {
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            int occupiedSlots = 0;
            int totalItems = 0;

            for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                occupiedSlots++;
                totalItems += stack.getCount();
                counts.merge(itemId(stack), stack.getCount(), Integer::sum);
            }

            List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counts.entrySet());
            ordered.sort(
                    Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(Map.Entry::getKey)
            );

            boolean truncated = ordered.size() > MAXIMUM_INVENTORY_TYPES;
            List<ItemCount> visible = ordered.stream()
                    .limit(MAXIMUM_INVENTORY_TYPES)
                    .map(entry -> new ItemCount(entry.getKey(), entry.getValue()))
                    .toList();

            int fireworks = counts.getOrDefault("minecraft:firework_rocket", 0);
            int saddles = counts.getOrDefault("minecraft:saddle", 0);
            int boats = counts.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("_boat") || entry.getKey().endsWith("_raft"))
                    .mapToInt(Map.Entry::getValue)
                    .sum();

            return new InventorySnapshot(
                    client.player.getInventory().size(),
                    occupiedSlots,
                    counts.size(),
                    totalItems,
                    visible,
                    truncated,
                    fireworks,
                    boats,
                    saddles
            );
        } catch (RuntimeException failure) {
            warnings.add("inventory_snapshot_failed:" + failureName(failure));
            return InventorySnapshot.unavailable();
        }
    }

    private static TargetSnapshot captureTarget(MinecraftClient client, List<String> warnings) {
        try {
            HitResult hit = client.crosshairTarget;
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return TargetSnapshot.none();
            }

            if (hit instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = client.world.getBlockState(pos);
                FluidState fluid = client.world.getFluidState(pos);
                return new TargetSnapshot(
                        "block",
                        blockId(state),
                        pos.toShortString(),
                        blockHit.getSide().asString(),
                        round(client.player.getPos().distanceTo(hit.getPos())),
                        fluid.isEmpty() ? "none" : fluidId(fluid),
                        ""
                );
            }

            if (hit instanceof EntityHitResult entityHit) {
                Entity entity = entityHit.getEntity();
                String detail = entity instanceof ItemEntity itemEntity
                        ? "item=" + stackSummary(itemEntity.getStack())
                        : entity instanceof LivingEntity living
                        ? "health=" + round(living.getHealth()) + "/" + round(living.getMaxHealth())
                        : "";
                return new TargetSnapshot(
                        "entity",
                        entityTypeId(entity),
                        entity.getBlockPos().toShortString(),
                        "",
                        round(client.player.distanceTo(entity)),
                        "",
                        detail
                );
            }

            return new TargetSnapshot(
                    hit.getType().name().toLowerCase(Locale.ROOT),
                    "unknown",
                    "",
                    "",
                    round(client.player.getPos().distanceTo(hit.getPos())),
                    "",
                    ""
            );
        } catch (RuntimeException failure) {
            warnings.add("target_snapshot_failed:" + failureName(failure));
            return TargetSnapshot.unavailable();
        }
    }

    private static EnvironmentSnapshot captureEnvironment(MinecraftClient client, List<String> warnings) {
        try {
            BlockPos feet = client.player.getBlockPos();
            Direction facing = client.player.getHorizontalFacing();
            BlockPos below = feet.down();
            BlockPos head = feet.up();
            BlockPos forwardFeet = feet.offset(facing);
            BlockPos forwardHead = head.offset(facing);
            BlockPos forwardSupport = below.offset(facing);

            List<NeighborBlock> neighbors = List.of(
                    neighbor(client, "down", feet.down()),
                    neighbor(client, "up", feet.up()),
                    neighbor(client, "north", feet.north()),
                    neighbor(client, "south", feet.south()),
                    neighbor(client, "west", feet.west()),
                    neighbor(client, "east", feet.east())
            );

            return new EnvironmentSnapshot(
                    blockAt(client, below),
                    blockAt(client, feet),
                    blockAt(client, head),
                    fluidAt(client, feet),
                    facing.asString(),
                    blockAt(client, forwardFeet),
                    blockAt(client, forwardHead),
                    blockAt(client, forwardSupport),
                    collisionEmpty(client, forwardFeet),
                    collisionEmpty(client, forwardHead),
                    List.copyOf(neighbors)
            );
        } catch (RuntimeException failure) {
            warnings.add("environment_snapshot_failed:" + failureName(failure));
            return EnvironmentSnapshot.unavailable();
        }
    }

    private static NearbySnapshot captureNearby(MinecraftClient client, List<String> warnings) {
        try {
            Box box = client.player.getBoundingBox().expand(NEARBY_ENTITY_RADIUS);
            List<Entity> entities = new ArrayList<>(client.world.getOtherEntities(client.player, box));
            entities.sort(Comparator.comparingDouble(client.player::distanceTo));

            boolean truncated = entities.size() > MAXIMUM_NEARBY_ENTITIES;
            List<NearbyEntity> visible = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();

            for (Entity entity : entities) {
                String type = entityTypeId(entity);
                counts.merge(type, 1, Integer::sum);
                if (visible.size() >= MAXIMUM_NEARBY_ENTITIES) {
                    continue;
                }

                String detail = "";
                if (entity instanceof ItemEntity itemEntity) {
                    detail = "item=" + stackSummary(itemEntity.getStack());
                } else if (entity instanceof LivingEntity living) {
                    detail = "health=" + round(living.getHealth()) + "/" + round(living.getMaxHealth());
                }

                visible.add(new NearbyEntity(
                        type,
                        round(client.player.distanceTo(entity)),
                        entity.getBlockPos().toShortString(),
                        detail
                ));
            }

            List<String> typeCounts = counts.entrySet().stream()
                    .sorted(
                            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                                    .reversed()
                                    .thenComparing(Map.Entry::getKey)
                    )
                    .limit(MAXIMUM_NEARBY_ENTITIES)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList();

            return new NearbySnapshot(
                    NEARBY_ENTITY_RADIUS,
                    entities.size(),
                    List.copyOf(visible),
                    typeCounts,
                    truncated
            );
        } catch (RuntimeException failure) {
            warnings.add("nearby_snapshot_failed:" + failureName(failure));
            return NearbySnapshot.unavailable();
        }
    }

    private static ReadinessSnapshot deriveReadiness(
            PlayerSnapshot player,
            EquipmentSnapshot equipment,
            InventorySnapshot inventory
    ) {
        boolean elytraEquipped = equipment.chest().startsWith("minecraft:elytra");
        int fireworks = inventory.fireworkRockets();
        int saddles = inventory.saddles();
        int boats = inventory.boatItems();

        List<String> availableTravel = new ArrayList<>();
        availableTravel.add("walk");
        availableTravel.add("sprint");
        if (player.submergedInWater() || player.swimming()) {
            availableTravel.add("swim");
        }
        if (!"none".equals(player.vehicle()) && !UNAVAILABLE.equals(player.vehicle())) {
            availableTravel.add("mounted");
        }
        if (elytraEquipped) {
            availableTravel.add("elytra");
        }
        if (boats > 0) {
            availableTravel.add("boat_available");
        }

        boolean lowHealth = player.maximumHealth() > 0.0D
                && player.health() <= Math.max(6.0D, player.maximumHealth() * 0.30D);
        boolean lowFood = player.food() <= 6;
        boolean lowAir = player.maximumAir() > 0
                && player.air() <= Math.max(40, player.maximumAir() / 3);

        return new ReadinessSnapshot(
                List.copyOf(availableTravel),
                elytraEquipped,
                fireworks > 0,
                fireworks,
                boats,
                saddles,
                lowHealth,
                lowFood,
                lowAir,
                player.onGround(),
                player.inLava(),
                player.fireTicks() > 0
        );
    }

    private static NeighborBlock neighbor(MinecraftClient client, String direction, BlockPos pos) {
        return new NeighborBlock(direction, blockAt(client, pos), fluidAt(client, pos));
    }

    private static String blockAt(MinecraftClient client, BlockPos pos) {
        return blockId(client.world.getBlockState(pos));
    }

    private static String fluidAt(MinecraftClient client, BlockPos pos) {
        FluidState fluid = client.world.getFluidState(pos);
        return fluid.isEmpty() ? "none" : fluidId(fluid);
    }

    private static boolean collisionEmpty(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.getCollisionShape(client.world, pos).isEmpty();
    }

    private static String blockId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private static String fluidId(FluidState fluid) {
        return Registries.FLUID.getId(fluid.getFluid()).toString();
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? "empty"
                : Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static String entityTypeId(Entity entity) {
        return entity == null
                ? "none"
                : Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }

    private static String statusEffectId(StatusEffectInstance effect) {
        return Registries.STATUS_EFFECT.getId(effect.getEffectType()).toString();
    }

    private static String stackSummary(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }

        StringBuilder output = new StringBuilder(itemId(stack))
                .append(" x")
                .append(stack.getCount());
        if (stack.isDamageable()) {
            int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamage());
            output.append(" durability=").append(remaining).append('/').append(stack.getMaxDamage());
        }
        if (stack.hasNbt()) {
            output.append(" nbt=true");
        }
        return output.toString();
    }

    private static double horizontalSpeed(Vec3d velocity) {
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String failureName(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "runtime_exception" : name;
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAXIMUM_CAPTURE_CHARACTERS) {
            return value;
        }

        String suffix = "\n[truncated] Expert Prefetch reached its "
                + MAXIMUM_CAPTURE_CHARACTERS
                + "-character prompt budget. Missing sections are unknown, not absent.";
        int keep = Math.max(0, MAXIMUM_CAPTURE_CHARACTERS - suffix.length());
        int newline = value.lastIndexOf('\n', keep);
        if (newline > 0) {
            keep = newline;
        }
        return value.substring(0, keep).stripTrailing() + suffix;
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append("- ").append(key).append('=').append(value).append('\n');
    }

    private static void section(StringBuilder out, String title) {
        out.append('[').append(title).append("]\n");
    }

    enum Domain {
        PLAYER,
        WORLD,
        EQUIPMENT,
        INVENTORY,
        TARGET,
        ENVIRONMENT,
        NEARBY,
        READINESS
    }

    enum Signal {
        PLAYER_POSITION,
        PLAYER_ORIENTATION,
        PLAYER_MOVEMENT,
        PLAYER_FLUID_STATE,
        PLAYER_VITALS,
        PLAYER_EXPERIENCE,
        PLAYER_GAME_MODE,
        PLAYER_VEHICLE,
        PLAYER_EFFECTS,
        WORLD_IDENTITY,
        WORLD_TIME,
        WORLD_WEATHER,
        WORLD_DIFFICULTY,
        WORLD_LIGHT,
        EQUIPMENT_HANDS,
        EQUIPMENT_ARMOR,
        ENVIRONMENT_LOCAL,
        ENVIRONMENT_FORWARD,
        ENVIRONMENT_NEIGHBORS,
        TARGET_CROSSHAIR,
        INVENTORY_SUMMARY,
        INVENTORY_ITEMS,
        NEARBY_SUMMARY,
        NEARBY_ENTITIES,
        READINESS_TRAVEL,
        READINESS_RESOURCE_PRESSURE,
        READINESS_HAZARDS
    }

    /**
     * Deterministic prompt-to-evidence routing. This deliberately uses a small
     * local classifier rather than another model call: prefetch must remain
     * cheap, predictable, side-effect free, and available before the first
     * provider round.
     */
    record PrefetchPlan(EnumSet<Domain> domains, EnumSet<Signal> signals) {
        PrefetchPlan {
            domains = domains == null ? EnumSet.noneOf(Domain.class) : domains.clone();
            signals = signals == null ? EnumSet.noneOf(Signal.class) : signals.clone();
        }

        static PrefetchPlan full() {
            return new PrefetchPlan(
                    EnumSet.allOf(Domain.class),
                    EnumSet.allOf(Signal.class)
            );
        }

        static PrefetchPlan forPrompt(String rawPrompt) {
            String prompt = normalizePrompt(rawPrompt);
            if (prompt.isBlank()) {
                // Preserve the old no-argument diagnostic behavior. Runtime
                // Automation now passes the actual objective.
                return full();
            }

            EnumSet<Domain> domains = EnumSet.noneOf(Domain.class);
            EnumSet<Signal> signals = EnumSet.noneOf(Signal.class);

            if (hasAny(prompt,
                    "inspect everything", "inspect my surroundings", "look around",
                    "what is around me", "what's around me", "current situation",
                    "status report", "full status", "world status", "everything around me")) {
                return full();
            }

            boolean directCommand = rawPrompt != null && rawPrompt.stripLeading().startsWith("/")
                    || hasAny(prompt, "run command", "execute command", "send command", "type command");
            if (directCommand && !hasAny(prompt, "check", "current", "what", "whether", " if ")) {
                return new PrefetchPlan(domains, signals);
            }

            boolean positionIntent = hasAny(prompt,
                    "where am i", "my position", "my coordinates", "coordinates", "location",
                    "position", "teleport", "tp me", "return home");
            boolean movementIntent = hasAny(prompt,
                    "move", "walk", "sprint", "jump", "travel", "navigate", "route", "path",
                    "go to", "get to", "reach", "follow", "fly", "elytra", "swim", "climb",
                    "ride", "mount", "boat", "horse");
            boolean survivalIntent = hasAny(prompt,
                    "health", "hurt", "damage", "heal", "healing", "hunger", "hungry", "food",
                    "eat", "consume", "drink", "air", "drown", "drowning", "burn", "burning", "fire",
                    "lava", "survive", "dying", "status effect", "potion effect", "effects");
            boolean consumptionIntent = hasAny(prompt,
                    "food", "eat", "consume", "drink", "hunger", "hungry", "heal", "healing");
            boolean hazardIntent = hasAny(prompt,
                    "drown", "drowning", "burn", "burning", "fire", "lava", "survive", "dying");
            boolean combatIntent = hasAny(prompt,
                    "kill", "fight", "attack", "hit", "shoot", "combat", "defend", "enemy", "hostile");
            boolean inventoryIntent = hasAny(prompt,
                    "inventory", "item", "items", "carry", "carrying", "do i have", "how many",
                    "food", "eat", "consume", "drink", "heal", "healing", "craft", "recipe", "equip", "equipment", "armor",
                    "weapon", "tool", "rocket", "firework", "saddle", "boat", "place", "build");
            boolean equipmentIntent = hasAny(prompt,
                    "equip", "equipment", "armor", "helmet", "chestplate", "leggings", "boots",
                    "main hand", "mainhand", "off hand", "offhand", "held item", "holding",
                    "weapon", "sword", "pickaxe", "axe", "shovel", "hoe", "bow", "crossbow",
                    "shield", "elytra");
            boolean targetIntent = hasAny(prompt,
                    "target", "crosshair", "looking at", "look at", "what am i looking at",
                    "this block", "that block", "this entity", "that entity", "that mob",
                    "interact", "use on", "open", "mine", "break", "attack", "hit", "shoot", "place",
                    "click", "press", "door", "chest", "lever", "button");
            boolean environmentIntent = hasAny(prompt,
                    "surrounding", "surroundings", "near me", "around me", "ground", "floor",
                    "above me", "below me", "in front", "ahead", "obstacle", "wall", "space",
                    "room", "block", "blocks", "place", "build", "mine", "break", "dig",
                    "bridge", "water", "lava", "door", "chest", "redstone");
            boolean nearbyIntent = hasAny(prompt,
                    "nearby", "mob", "mobs", "entity", "entities", "enemy", "enemies",
                    "hostile", "animal", "animals", "player nearby", "players nearby", "zombie",
                    "creeper", "skeleton", "spider", "enderman", "villager", "cow", "pig", "sheep",
                    "kill", "fight", "attack", "pickup", "pick up", "dropped item");

            boolean worldIdentityIntent = hasAny(prompt,
                    "biome", "dimension", "overworld", "nether", "the end", "world", "where am i");
            boolean worldTimeIntent = hasAny(prompt,
                    "time", "day", "night", "sunrise", "sunset", "moon", "moon phase");
            boolean worldWeatherIntent = hasAny(prompt,
                    "weather", "rain", "raining", "thunder", "thundering", "storm", "clear weather");
            boolean worldLightIntent = hasAny(prompt,
                    "light", "light level", "dark", "darkness", "sky visible", "sky visibility");
            boolean difficultyIntent = hasAny(prompt,
                    "difficulty", "peaceful difficulty", "easy difficulty", "normal difficulty", "hard difficulty");
            boolean gameModeIntent = hasAny(prompt, "gamemode", "game mode", "creative", "survival mode", "spectator", "adventure mode");
            boolean experienceIntent = hasAny(prompt, "experience", "xp", "xp level", "experience level", "levels", "enchant");
            boolean vehicleIntent = hasAny(prompt, "vehicle", "mounted", "mount", "ride", "boat", "horse", "minecart");
            boolean effectIntent = hasAny(prompt, "status effect", "potion effect", "effects", "buff", "debuff");

            if (positionIntent || movementIntent || survivalIntent || combatIntent || targetIntent
                    || environmentIntent || gameModeIntent || experienceIntent || vehicleIntent || effectIntent) {
                domains.add(Domain.PLAYER);
            }
            if (positionIntent || movementIntent) {
                signals.add(Signal.PLAYER_POSITION);
            }
            if (movementIntent || targetIntent || environmentIntent) {
                signals.add(Signal.PLAYER_ORIENTATION);
            }
            if (movementIntent) {
                signals.add(Signal.PLAYER_MOVEMENT);
            }
            if (survivalIntent || combatIntent) {
                signals.add(Signal.PLAYER_VITALS);
            }
            if (hazardIntent || hasAny(prompt, "swim", "water", "lava")) {
                signals.add(Signal.PLAYER_FLUID_STATE);
            }
            if (experienceIntent) signals.add(Signal.PLAYER_EXPERIENCE);
            if (gameModeIntent) signals.add(Signal.PLAYER_GAME_MODE);
            if (vehicleIntent || movementIntent) signals.add(Signal.PLAYER_VEHICLE);
            if (effectIntent) signals.add(Signal.PLAYER_EFFECTS);

            if (worldIdentityIntent || worldTimeIntent || worldWeatherIntent || worldLightIntent || difficultyIntent) {
                domains.add(Domain.WORLD);
            }
            if (worldIdentityIntent) signals.add(Signal.WORLD_IDENTITY);
            if (worldTimeIntent) signals.add(Signal.WORLD_TIME);
            if (worldWeatherIntent) signals.add(Signal.WORLD_WEATHER);
            if (difficultyIntent) signals.add(Signal.WORLD_DIFFICULTY);
            if (worldLightIntent) signals.add(Signal.WORLD_LIGHT);

            if (equipmentIntent || consumptionIntent || combatIntent || targetIntent || movementIntent) {
                domains.add(Domain.EQUIPMENT);
                signals.add(Signal.EQUIPMENT_HANDS);
            }
            if (equipmentIntent || combatIntent || hasAny(prompt, "armor", "protect", "protection")) {
                domains.add(Domain.EQUIPMENT);
                signals.add(Signal.EQUIPMENT_ARMOR);
            }

            if (inventoryIntent) {
                domains.add(Domain.INVENTORY);
                signals.add(Signal.INVENTORY_ITEMS);
                if (hasAny(prompt, "inventory", "how many", "do i have", "what do i have", "slots", "full inventory")) {
                    signals.add(Signal.INVENTORY_SUMMARY);
                }
            }

            if (targetIntent) {
                domains.add(Domain.TARGET);
                signals.add(Signal.TARGET_CROSSHAIR);
            }

            if (environmentIntent || movementIntent || targetIntent) {
                domains.add(Domain.ENVIRONMENT);
                signals.add(Signal.ENVIRONMENT_LOCAL);
            }
            if (movementIntent || targetIntent || hasAny(prompt, "in front", "ahead", "place", "build")) {
                domains.add(Domain.ENVIRONMENT);
                signals.add(Signal.ENVIRONMENT_FORWARD);
            }
            if (environmentIntent && hasAny(prompt,
                    "around", "surrounding", "neighbor", "adjacent", "place", "build", "redstone", "connect")) {
                signals.add(Signal.ENVIRONMENT_NEIGHBORS);
            }

            if (nearbyIntent) {
                domains.add(Domain.NEARBY);
                signals.add(Signal.NEARBY_SUMMARY);
                signals.add(Signal.NEARBY_ENTITIES);
            }

            if (movementIntent || hasAny(prompt, "elytra", "rocket", "firework", "boat", "saddle", "travel")) {
                domains.add(Domain.READINESS);
                signals.add(Signal.READINESS_TRAVEL);
            }
            if (survivalIntent) {
                domains.add(Domain.READINESS);
                signals.add(Signal.READINESS_RESOURCE_PRESSURE);
            }
            if (hazardIntent) {
                signals.add(Signal.READINESS_HAZARDS);
            }
            if (movementIntent && hasAny(prompt, "lava", "fire", "danger", "safe", "safely")) {
                domains.add(Domain.READINESS);
                signals.add(Signal.READINESS_HAZARDS);
            }

            // Deictic requests usually refer to the current crosshair target.
            if (hasAny(prompt, "that", "this", "there")
                    && (movementIntent || targetIntent || environmentIntent || nearbyIntent)) {
                domains.add(Domain.TARGET);
                signals.add(Signal.TARGET_CROSSHAIR);
            }

            // Pure explicit command execution should not receive unrelated world
            // context. The command/tool loop can verify state only if needed.
            if (directCommand && domains.isEmpty()) {
                return new PrefetchPlan(domains, signals);
            }

            if (domains.isEmpty() && hasAny(prompt,
                    "do it", "continue", "keep going", "go ahead", "proceed", "again",
                    "try again", "finish it", "complete it", "fix it", "yes", "okay", "now")) {
                // Continuation-like objectives depend on prior conversational
                // context. Give a compact safety packet instead of guessing a
                // broad world snapshot.
                domains.add(Domain.PLAYER);
                domains.add(Domain.EQUIPMENT);
                domains.add(Domain.TARGET);
                signals.add(Signal.PLAYER_POSITION);
                signals.add(Signal.PLAYER_VITALS);
                signals.add(Signal.EQUIPMENT_HANDS);
                signals.add(Signal.TARGET_CROSSHAIR);
            }

            return new PrefetchPlan(domains, signals);
        }

        boolean isEmpty() {
            return domains.isEmpty();
        }

        boolean needs(Domain domain) {
            return domains.contains(domain);
        }

        boolean captures(Domain domain) {
            if (domains.contains(domain)) {
                return true;
            }
            return domains.contains(Domain.READINESS)
                    && (domain == Domain.PLAYER || domain == Domain.EQUIPMENT || domain == Domain.INVENTORY);
        }

        boolean wants(Signal signal) {
            return signals.contains(signal);
        }

        String domainSummary() {
            return domains.stream()
                    .map(domain -> domain.name().toLowerCase(Locale.ROOT))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("none");
        }

        private static String normalizePrompt(String value) {
            if (value == null) {
                return "";
            }
            return value.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_:/.-]+", " ")
                    .trim();
        }

        private static boolean hasAny(String normalizedPrompt, String... terms) {
            String haystack = " " + normalizedPrompt + " ";
            for (String term : terms) {
                String needle = " " + normalizePrompt(term) + " ";
                if (haystack.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Snapshot(
            boolean available,
            long capturedAtMillis,
            String unavailableReason,
            PlayerSnapshot player,
            WorldSnapshot world,
            EquipmentSnapshot equipment,
            InventorySnapshot inventory,
            TargetSnapshot target,
            EnvironmentSnapshot environment,
            NearbySnapshot nearby,
            ReadinessSnapshot readiness,
            List<String> warnings
    ) {
        static Snapshot unavailable(long capturedAtMillis, String reason) {
            return new Snapshot(
                    false,
                    capturedAtMillis,
                    reason == null ? "unknown" : reason,
                    PlayerSnapshot.unavailable(),
                    WorldSnapshot.unavailable(),
                    EquipmentSnapshot.unavailable(),
                    InventorySnapshot.unavailable(),
                    TargetSnapshot.unavailable(),
                    EnvironmentSnapshot.unavailable(),
                    NearbySnapshot.unavailable(),
                    ReadinessSnapshot.unavailable(),
                    List.of()
            );
        }

        String render() {
            return render(PrefetchPlan.full());
        }

        String render(PrefetchPlan plan) {
            if (!available) {
                return """
                        Koil Expert Prefetch:
                        - availability=unavailable
                        - reason=%s
                        - requestedDomains=%s
                        - scope=client-visible read-only state only
                        - guidance=Do not infer world/player state from this absence. Use a supplied evidence tool when the objective depends on live state.
                        """.formatted(unavailableReason, plan.domainSummary()).strip();
            }

            StringBuilder out = new StringBuilder(2_048);
            out.append("Koil Expert Prefetch v3\n");
            out.append("selection=prompt-conditioned domains:").append(plan.domainSummary()).append('\n');
            out.append("freshness=capture-time evidence only; re-inspect after relevant state changes or side effects\n");
            line(out, "capturedAtMillis", capturedAtMillis);

            if (plan.needs(Domain.PLAYER)) {
                section(out, "player");
                if (plan.wants(Signal.PLAYER_POSITION)) {
                    line(out, "position", player.positionText());
                    line(out, "blockPosition", player.blockPositionText());
                }
                if (plan.wants(Signal.PLAYER_ORIENTATION)) {
                    line(out, "facing", player.facing() + " yaw=" + player.yaw() + " pitch=" + player.pitch());
                }
                if (plan.wants(Signal.PLAYER_MOVEMENT)) {
                    line(out, "velocity", player.velocityText() + " horizontalSpeed=" + player.horizontalSpeed());
                    line(out, "movement",
                            "ground=" + player.onGround()
                                    + " sprint=" + player.sprinting()
                                    + " sneak=" + player.sneaking()
                                    + " swim=" + player.swimming()
                                    + " fallFlying=" + player.fallFlying()
                                    + " flying=" + player.flying()
                                    + " allowFlying=" + player.allowFlying()
                                    + " fallDistance=" + player.fallDistance());
                }
                if (plan.wants(Signal.PLAYER_FLUID_STATE)) {
                    line(out, "fluidState",
                            "touchingWater=" + player.touchingWater()
                                    + " submergedWater=" + player.submergedInWater()
                                    + " inLava=" + player.inLava());
                }
                if (plan.wants(Signal.PLAYER_VITALS)) {
                    line(out, "vitals",
                            "health=" + player.health() + "/" + player.maximumHealth()
                                    + " food=" + player.food() + "/20"
                                    + " saturation=" + player.saturation()
                                    + " exhaustion=" + player.exhaustion()
                                    + " armor=" + player.armor()
                                    + " air=" + player.air() + "/" + player.maximumAir()
                                    + " fireTicks=" + player.fireTicks());
                }
                if (plan.wants(Signal.PLAYER_EXPERIENCE)) {
                    line(out, "experienceLevel", player.experienceLevel());
                }
                if (plan.wants(Signal.PLAYER_GAME_MODE)) {
                    line(out, "gameMode", player.gameMode());
                }
                if (plan.wants(Signal.PLAYER_VEHICLE)) {
                    line(out, "vehicle", player.vehicle());
                }
                if (plan.wants(Signal.PLAYER_EFFECTS)) {
                    line(out, "effects", player.effects().isEmpty() ? "none" : String.join(", ", player.effects()));
                    line(out, "effectsTruncated", player.effectsTruncated());
                }
            }

            if (plan.needs(Domain.WORLD)) {
                section(out, "world");
                if (plan.wants(Signal.WORLD_IDENTITY)) {
                    line(out, "dimension", world.dimension());
                    line(out, "biome", world.biome());
                    line(out, "heightRange", world.bottomY() + ".." + world.topY());
                    line(out, "connection", world.connection());
                }
                if (plan.wants(Signal.WORLD_TIME)) {
                    line(out, "time", world.timeOfDay() + " day=" + world.day());
                    line(out, "moonPhase", world.moonPhase());
                }
                if (plan.wants(Signal.WORLD_WEATHER)) {
                    line(out, "weather", world.weather());
                }
                if (plan.wants(Signal.WORLD_DIFFICULTY)) {
                    line(out, "difficulty", world.difficulty());
                }
                if (plan.wants(Signal.WORLD_LIGHT)) {
                    line(out, "light",
                            "block=" + world.blockLight()
                                    + " sky=" + world.skyLight()
                                    + " combined=" + world.combinedLight()
                                    + " skyVisible=" + world.skyVisible());
                }
            }

            if (plan.needs(Domain.EQUIPMENT)) {
                section(out, "equipment");
                if (plan.wants(Signal.EQUIPMENT_HANDS)) {
                    line(out, "mainHand", equipment.mainHand());
                    line(out, "offHand", equipment.offHand());
                }
                if (plan.wants(Signal.EQUIPMENT_ARMOR)) {
                    line(out, "head", equipment.head());
                    line(out, "chest", equipment.chest());
                    line(out, "legs", equipment.legs());
                    line(out, "feet", equipment.feet());
                }
            }

            if (plan.needs(Domain.ENVIRONMENT)) {
                section(out, "environment");
                if (plan.wants(Signal.ENVIRONMENT_LOCAL)) {
                    line(out, "standingOn", environment.below());
                    line(out, "feetBlock", environment.feet());
                    line(out, "headBlock", environment.head());
                    line(out, "feetFluid", environment.feetFluid());
                }
                if (plan.wants(Signal.ENVIRONMENT_FORWARD)) {
                    line(out, "forward",
                            "direction=" + environment.forwardDirection()
                                    + " feet=" + environment.forwardFeet()
                                    + " head=" + environment.forwardHead()
                                    + " support=" + environment.forwardSupport()
                                    + " feetCollisionEmpty=" + environment.forwardFeetCollisionEmpty()
                                    + " headCollisionEmpty=" + environment.forwardHeadCollisionEmpty());
                }
                if (plan.wants(Signal.ENVIRONMENT_NEIGHBORS)) {
                    line(out, "neighbors", environment.neighborSummary());
                }
            }

            if (plan.needs(Domain.TARGET) && plan.wants(Signal.TARGET_CROSSHAIR)) {
                section(out, "target");
                line(out, "crosshair",
                        "kind=" + target.kind()
                                + " id=" + target.id()
                                + " pos=" + target.position()
                                + " side=" + target.side()
                                + " distance=" + target.distance()
                                + " fluid=" + target.fluid()
                                + (target.detail().isBlank() ? "" : " " + target.detail()));
            }

            if (plan.needs(Domain.INVENTORY)) {
                section(out, "inventory");
                if (plan.wants(Signal.INVENTORY_SUMMARY)) {
                    line(out, "summary",
                            "slots=" + inventory.totalSlots()
                                    + " occupied=" + inventory.occupiedSlots()
                                    + " distinctTypes=" + inventory.distinctTypes()
                                    + " totalStackItems=" + inventory.totalItems()
                                    + " truncated=" + inventory.truncated());
                }
                if (plan.wants(Signal.INVENTORY_ITEMS)) {
                    line(out, "topItems", inventory.itemSummary());
                    line(out, "truncated", inventory.truncated());
                }
            }

            if (plan.needs(Domain.NEARBY)) {
                section(out, "nearbyEntities");
                if (plan.wants(Signal.NEARBY_SUMMARY)) {
                    line(out, "summary",
                            "radius=" + nearby.radius()
                                    + " observed=" + nearby.observedCount()
                                    + " listed=" + nearby.entities().size()
                                    + " truncated=" + nearby.truncated());
                    line(out, "typeCounts", nearby.typeCounts().isEmpty() ? "none" : String.join(", ", nearby.typeCounts()));
                }
                if (plan.wants(Signal.NEARBY_ENTITIES)) {
                    if (nearby.entities().isEmpty()) {
                        line(out, "nearest", "none");
                    } else {
                        for (int i = 0; i < nearby.entities().size(); i++) {
                            NearbyEntity entity = nearby.entities().get(i);
                            line(out, "nearby[" + i + "]",
                                    "id=" + entity.id()
                                            + " distance=" + entity.distance()
                                            + " pos=" + entity.position()
                                            + (entity.detail().isBlank() ? "" : " " + entity.detail()));
                        }
                    }
                }
            }

            if (plan.needs(Domain.READINESS)) {
                section(out, "planningReadiness");
                if (plan.wants(Signal.READINESS_TRAVEL)) {
                    line(out, "availableTravelModes", String.join(",", readiness.availableTravelModes()));
                    line(out, "elytraEquipped", readiness.elytraEquipped());
                    line(out, "elytraBoostAvailable", readiness.elytraBoostAvailable());
                    line(out, "fireworkRockets", readiness.fireworkRockets());
                    line(out, "boatItems", readiness.boatItems());
                    line(out, "saddles", readiness.saddles());
                    line(out, "onGround", readiness.onGround());
                }
                if (plan.wants(Signal.READINESS_RESOURCE_PRESSURE)) {
                    line(out, "resourcePressure",
                            "lowHealth=" + readiness.lowHealth()
                                    + " lowFood=" + readiness.lowFood()
                                    + " lowAir=" + readiness.lowAir());
                }
                if (plan.wants(Signal.READINESS_HAZARDS)) {
                    line(out, "immediateHazards",
                            "lava=" + readiness.inLava() + " burning=" + readiness.burning());
                }
            }

            if (!warnings.isEmpty()) {
                section(out, "captureWarnings");
                for (String warning : warnings) {
                    line(out, "warning", warning);
                }
            }

            out.append("[guidance]\n");
            out.append("- This packet contains only state selected as relevant to the current objective. Omitted domains are unknown, not absent.\n");
            out.append("- Re-inspect exact or changed state after movement, delay, inventory/world changes, or any side effect.\n");
            out.append("- Never treat prefetch as proof that an action completed.\n");

            return bounded(out.toString().stripTrailing());
        }

    }

    record PlayerSnapshot(
            double x,
            double y,
            double z,
            int blockX,
            int blockY,
            int blockZ,
            String facing,
            double yaw,
            double pitch,
            double velocityX,
            double velocityY,
            double velocityZ,
            double horizontalSpeed,
            boolean onGround,
            boolean sprinting,
            boolean sneaking,
            boolean swimming,
            boolean fallFlying,
            boolean touchingWater,
            boolean submergedInWater,
            boolean inLava,
            boolean flying,
            boolean allowFlying,
            double fallDistance,
            double health,
            double maximumHealth,
            int food,
            double saturation,
            double exhaustion,
            int armor,
            int air,
            int maximumAir,
            int fireTicks,
            int experienceLevel,
            String gameMode,
            int selectedHotbarSlot,
            String vehicle,
            List<String> effects,
            boolean effectsTruncated
    ) {
        static PlayerSnapshot unavailable() {
            return new PlayerSnapshot(
                    0.0D, 0.0D, 0.0D,
                    0, 0, 0,
                    UNAVAILABLE,
                    0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    false, false, false, false, false,
                    false, false, false,
                    false, false,
                    0.0D,
                    0.0D, 0.0D,
                    0, 0.0D, 0.0D, 0,
                    0, 0, 0, 0,
                    UNAVAILABLE, 0, UNAVAILABLE,
                    List.of(), false
            );
        }

        String positionText() {
            return x + "," + y + "," + z;
        }

        String blockPositionText() {
            return blockX + "," + blockY + "," + blockZ;
        }

        String velocityText() {
            return velocityX + "," + velocityY + "," + velocityZ;
        }
    }

    record WorldSnapshot(
            String dimension,
            String biome,
            long timeOfDay,
            long day,
            String weather,
            String difficulty,
            int moonPhase,
            int bottomY,
            int topY,
            int blockLight,
            int skyLight,
            int combinedLight,
            boolean skyVisible,
            String connection
    ) {
        static WorldSnapshot unavailable() {
            return new WorldSnapshot(
                    UNAVAILABLE, UNAVAILABLE,
                    0L, 0L,
                    UNAVAILABLE, UNAVAILABLE,
                    0, 0, 0, 0, 0, 0, false, UNAVAILABLE
            );
        }
    }

    record EquipmentSnapshot(
            String mainHand,
            String offHand,
            String head,
            String chest,
            String legs,
            String feet
    ) {
        static EquipmentSnapshot unavailable() {
            return new EquipmentSnapshot(
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE
            );
        }
    }

    record InventorySnapshot(
            int totalSlots,
            int occupiedSlots,
            int distinctTypes,
            int totalItems,
            List<ItemCount> items,
            boolean truncated,
            int fireworkRockets,
            int boatItems,
            int saddles
    ) {
        static InventorySnapshot unavailable() {
            return new InventorySnapshot(0, 0, 0, 0, List.of(), false, 0, 0, 0);
        }

        String itemSummary() {
            if (items.isEmpty()) {
                return "empty";
            }
            return items.stream()
                    .map(item -> item.id() + "=" + item.count())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("empty");
        }
    }

    record ItemCount(String id, int count) {
    }

    record TargetSnapshot(
            String kind,
            String id,
            String position,
            String side,
            double distance,
            String fluid,
            String detail
    ) {
        static TargetSnapshot none() {
            return new TargetSnapshot("none", "none", "", "", 0.0D, "none", "");
        }

        static TargetSnapshot unavailable() {
            return new TargetSnapshot(UNAVAILABLE, UNAVAILABLE, "", "", 0.0D, UNAVAILABLE, "");
        }
    }

    record EnvironmentSnapshot(
            String below,
            String feet,
            String head,
            String feetFluid,
            String forwardDirection,
            String forwardFeet,
            String forwardHead,
            String forwardSupport,
            boolean forwardFeetCollisionEmpty,
            boolean forwardHeadCollisionEmpty,
            List<NeighborBlock> neighbors
    ) {
        static EnvironmentSnapshot unavailable() {
            return new EnvironmentSnapshot(
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                    false, false, List.of()
            );
        }

        String neighborSummary() {
            if (neighbors.isEmpty()) {
                return UNAVAILABLE;
            }
            return neighbors.stream()
                    .map(neighbor -> neighbor.direction()
                            + "=" + neighbor.block()
                            + (neighbor.fluid().equals("none") ? "" : "/" + neighbor.fluid()))
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(UNAVAILABLE);
        }
    }

    record NeighborBlock(String direction, String block, String fluid) {
    }

    record NearbySnapshot(
            double radius,
            int observedCount,
            List<NearbyEntity> entities,
            List<String> typeCounts,
            boolean truncated
    ) {
        static NearbySnapshot unavailable() {
            return new NearbySnapshot(NEARBY_ENTITY_RADIUS, 0, List.of(), List.of(), false);
        }
    }

    record NearbyEntity(
            String id,
            double distance,
            String position,
            String detail
    ) {
    }

    record ReadinessSnapshot(
            List<String> availableTravelModes,
            boolean elytraEquipped,
            boolean elytraBoostAvailable,
            int fireworkRockets,
            int boatItems,
            int saddles,
            boolean lowHealth,
            boolean lowFood,
            boolean lowAir,
            boolean onGround,
            boolean inLava,
            boolean burning
    ) {
        static ReadinessSnapshot unavailable() {
            return new ReadinessSnapshot(
                    List.of(), false, false,
                    0, 0, 0,
                    false, false, false,
                    false, false, false
            );
        }
    }
}
