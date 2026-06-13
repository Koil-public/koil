package com.spirit.client.gui;

import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.spirit.koil.api.design.uiColorVal.*;

public final class EntityInspectionTooltipBuilder {
    private EntityInspectionTooltipBuilder() {
    }

    public static List<Text> build(Entity entity) {
        List<Text> lines = new ArrayList<>();
        if (entity == null) {
            return lines;
        }
        int titleColor = uiColorToolTipWarning;
        int labelColor = uiColorToolTipLabel;
        int primaryColor = uiColorToolTipPrimary;
        int secondaryColor = uiColorToolTipSecondary;
        int ideaColor = uiColorToolTipIdea;

        Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
        lines.add(
                Text.literal("Entity").setStyle(Style.EMPTY.withColor(titleColor).withBold(true))
                        .append(Text.literal("  ").setStyle(Style.EMPTY.withColor(labelColor)))
                        .append(Text.literal(entity.getName().getString()).setStyle(Style.EMPTY.withColor(primaryColor)))
        );
        addLine(lines, "Type: ", entityId == null ? "unknown" : entityId.toString(), labelColor, secondaryColor);
        addLine(lines, "UUID: ", entity.getUuidAsString(), labelColor, secondaryColor);
        addLine(lines, "Entity Id: ", String.valueOf(entity.getId()), labelColor, secondaryColor);
        addLine(lines, "World: ", worldId(entity.getWorld()), labelColor, secondaryColor);
        addLine(lines, "Automation: ", automationState(entity), ideaColor, automationColor(entity));
        addLine(lines, "Position: ", formatPos(entity), labelColor, primaryColor);
        addLine(lines, "Block: ", blockPos(entity.getBlockPos()), labelColor, secondaryColor);
        addLine(lines, "Velocity: ", formatVelocity(entity), labelColor, secondaryColor);
        addLine(lines, "Rotation: ", String.format(Locale.ROOT, "yaw %.2f pitch %.2f", entity.getYaw(), entity.getPitch()), labelColor, secondaryColor);
        addLine(lines, "Flags: ", formatFlags(entity), labelColor, secondaryColor);
        addLine(lines, "Bounding Box: ", formatBox(entity.getBoundingBox()), labelColor, secondaryColor);

        if (entity instanceof LivingEntity living) {
            addLine(lines, "Vitals: ", String.format(Locale.ROOT, "health %.2f / %.2f, absorption %.2f, armor %d", living.getHealth(), living.getMaxHealth(), living.getAbsorptionAmount(), living.getArmor()), labelColor, primaryColor);
            addAttribute(lines, "Move Speed", living.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED), labelColor, secondaryColor);
            addAttribute(lines, "Attack Damage", living.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE), labelColor, secondaryColor);
            addAttribute(lines, "Attack Speed", living.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED), labelColor, secondaryColor);
            addAttribute(lines, "Follow Range", living.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE), labelColor, secondaryColor);
            addAttribute(lines, "Knockback Resist", living.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), labelColor, secondaryColor);
            addLine(lines, "Air / Fire: ", living.getAir() + " / " + living.getFireTicks(), labelColor, secondaryColor);
            addLine(lines, "Hands: ", stackLabel(living.getMainHandStack()) + " | offhand " + stackLabel(living.getOffHandStack()), labelColor, secondaryColor);
            if (living.getAttacker() != null) {
                addLine(lines, "Attacker: ", living.getAttacker().getName().getString(), labelColor, secondaryColor);
            }
            if (living.getAttacking() != null) {
                addLine(lines, "Target: ", living.getAttacking().getName().getString(), labelColor, secondaryColor);
            }
            if (!living.getStatusEffects().isEmpty()) {
                addLine(lines, "Effects: ", formatEffects(living.getStatusEffects()), labelColor, secondaryColor);
            }
        }

        if (entity instanceof PlayerEntity player) {
            addLine(lines, "Player: ", player.getGameProfile().getName(), labelColor, primaryColor);
            addLine(lines, "Food: ", String.format(Locale.ROOT, "%d food, %.2f saturation, %.2f exhaustion", player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(), player.getHungerManager().getExhaustion()), labelColor, secondaryColor);
            AbstractTeam team = player.getScoreboardTeam();
            if (team != null) {
                addLine(lines, "Team: ", team.getName(), labelColor, secondaryColor);
            }
        } else if (entity instanceof MobEntity mob) {
            addLine(lines, "Mob State: ", "ai " + (!mob.isAiDisabled()) + ", left-handed " + mob.isLeftHanded() + ", attacking " + mob.isAttacking(), labelColor, secondaryColor);
            if (mob.getTarget() != null) {
                addLine(lines, "Mob Target: ", mob.getTarget().getName().getString(), labelColor, secondaryColor);
            }
        }

        if (entity instanceof TameableEntity tameable) {
            addLine(lines, "Owner: ", tameable.getOwnerUuid() == null ? "none" : tameable.getOwnerUuid().toString(), labelColor, secondaryColor);
        }

        if (entity.hasPassengers()) {
            addLine(lines, "Passengers: ", joinEntityNames(entity.getPassengerList()), labelColor, secondaryColor);
        }
        if (entity.hasVehicle()) {
            addLine(lines, "Vehicle: ", entity.getVehicle() == null ? "none" : entity.getVehicle().getName().getString(), labelColor, secondaryColor);
        }

        NbtCompound nbt = new NbtCompound();
        try {
            entity.writeNbt(nbt);
            addWrappedLine(lines, "NBT: ", nbt.asString(), labelColor, secondaryColor);
        } catch (Exception exception) {
            addLine(lines, "NBT: ", "unavailable: " + exception.getMessage(), labelColor, secondaryColor);
        }

        return lines;
    }

    private static void addAttribute(List<Text> lines, String label, EntityAttributeInstance attribute, int labelColor, int valueColor) {
        if (attribute != null) {
            addLine(lines, label + ": ", String.format(Locale.ROOT, "%.3f", attribute.getValue()), labelColor, valueColor);
        }
    }

    private static void addLine(List<Text> lines, String label, String value, int labelColor, int valueColor) {
        lines.add(Text.literal(label).setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(value == null || value.isBlank() ? "none" : value).setStyle(Style.EMPTY.withColor(valueColor))));
    }

    private static void addWrappedLine(List<Text> lines, String label, String value, int labelColor, int valueColor) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            addLine(lines, label, value, labelColor, valueColor);
            return;
        }
        int wrapWidth = 320 - client.textRenderer.getWidth(label);
        List<String> wrapped = wrapPlain(client, value, Math.max(80, wrapWidth));
        if (wrapped.isEmpty()) {
            addLine(lines, label, value, labelColor, valueColor);
            return;
        }
        for (int i = 0; i < wrapped.size(); i++) {
            String row = wrapped.get(i);
            addLine(lines, i == 0 ? label : "  ", row, labelColor, valueColor);
        }
    }

    private static List<String> wrapPlain(MinecraftClient client, String value, int maxWidth) {
        List<String> rows = new ArrayList<>();
        if (client == null || client.textRenderer == null || value == null || value.isBlank()) {
            rows.add(value == null ? "" : value);
            return rows;
        }
        StringBuilder current = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && client.textRenderer.getWidth(candidate) > maxWidth) {
                rows.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) {
            rows.add(current.toString());
        }
        if (rows.isEmpty()) {
            rows.add(value);
        }
        return rows;
    }

    private static String worldId(World world) {
        if (world == null || world.getRegistryKey() == null || world.getRegistryKey().getValue() == null) {
            return "unknown";
        }
        return world.getRegistryKey().getValue().toString();
    }

    private static String automationState(Entity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return "not a player";
        }
        String state = AutomationPresenceState.stateFor(player);
        String detail = AutomationPresenceState.detailFor(player);
        return detail.isBlank() ? state : state + " - " + detail;
    }

    private static int automationColor(Entity entity) {
        if (entity instanceof PlayerEntity player) {
            return AutomationPresenceState.colorFor(player);
        }
        return uiColorToolTipPrimary;
    }

    private static String formatPos(Entity entity) {
        return String.format(Locale.ROOT, "x %.3f y %.3f z %.3f", entity.getX(), entity.getY(), entity.getZ());
    }

    private static String blockPos(BlockPos pos) {
        return pos == null ? "unknown" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String formatVelocity(Entity entity) {
        return String.format(Locale.ROOT, "x %.3f y %.3f z %.3f", entity.getVelocity().x, entity.getVelocity().y, entity.getVelocity().z);
    }

    private static String formatFlags(Entity entity) {
        return "on_ground " + entity.isOnGround()
                + ", sprinting " + entity.isSprinting()
                + ", sneaking " + entity.isSneaking()
                + ", swimming " + entity.isSwimming()
                + ", glowing " + entity.isGlowing()
                + ", invisible " + entity.isInvisible()
                + ", silent " + entity.isSilent()
                + ", no_gravity " + entity.hasNoGravity();
    }

    private static String formatBox(Box box) {
        return String.format(Locale.ROOT, "min %.2f %.2f %.2f | max %.2f %.2f %.2f", box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static String stackLabel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return stack.getCount() + "x " + (id == null ? stack.getName().getString() : id.toString());
    }

    private static String formatEffects(Iterable<StatusEffectInstance> effects) {
        List<String> rows = new ArrayList<>();
        for (StatusEffectInstance effect : effects) {
            Identifier id = Registries.STATUS_EFFECT.getId(effect.getEffectType());
            rows.add((id == null ? effect.getEffectType().getName().getString() : id.toString()) + " amp " + effect.getAmplifier() + " dur " + effect.getDuration());
        }
        return String.join(" | ", rows);
    }

    private static String joinEntityNames(List<Entity> entities) {
        List<String> names = new ArrayList<>();
        for (Entity entity : entities) {
            names.add(entity.getName().getString());
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
