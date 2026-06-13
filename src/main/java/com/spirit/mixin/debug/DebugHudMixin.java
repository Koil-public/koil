package com.spirit.mixin.debug;

import com.llamalad7.mixinextras.sugar.Local;
import com.spirit.Main;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.f3.F3Controller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

@Mixin(DebugHud.class)
public class DebugHudMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$hideVanillaDebugHud(DrawContext context, CallbackInfo ci) {
        if (KoilScreenBackgrounds.uiRedesignEnabled() && !Main.vanillaF3Design() && !F3Controller.vanillaFallback()) {
            ci.cancel();
        }
    }

    @Inject(method = "getLeftText", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0, shift = At.Shift.AFTER), slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=XYZ: %.3f / %.5f / %.3f")))
    private void appendOverworldCoordinates(CallbackInfoReturnable<List<String>> cir, @Local List<String> list) {
        var coordinateScale = client.world.getDimension().coordinateScale();
        list.add(String.format(Locale.ROOT, "Overworld: %.3f / %.3f", this.client.getCameraEntity().getX() * coordinateScale, this.client.getCameraEntity().getZ() * coordinateScale));
    }

    @Inject(method = "getRightText", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 10, shift = At.Shift.AFTER))
    private void onAdd(CallbackInfoReturnable<List<String>> cir, @Local List<String> list) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.targetedEntity instanceof LivingEntity livingEntity) {
            list.add(String.format("Health: %.3f/%.1f", livingEntity.getHealth(), livingEntity.getMaxHealth()));
            list.add(String.format("Absorption: %.3f", livingEntity.getAbsorptionAmount()));

            list.add(String.format("Armor: %d", livingEntity.getArmor()));

            EntityAttributeInstance maxHealth = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            EntityAttributeInstance followRange = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE);
            EntityAttributeInstance knockbackResistance = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            EntityAttributeInstance movementSpeed = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            EntityAttributeInstance flyingSpeed = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_FLYING_SPEED);
            EntityAttributeInstance attackDamage = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            EntityAttributeInstance attackKnockback = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
            EntityAttributeInstance attackSpeed = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED);
            EntityAttributeInstance armor = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
            EntityAttributeInstance armorToughness = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
            EntityAttributeInstance luck = livingEntity.getAttributeInstance(EntityAttributes.GENERIC_LUCK);
            EntityAttributeInstance zombieSpawnReinforcements = livingEntity.getAttributeInstance(EntityAttributes.ZOMBIE_SPAWN_REINFORCEMENTS);
            EntityAttributeInstance horseJumpStrength = livingEntity.getAttributeInstance(EntityAttributes.HORSE_JUMP_STRENGTH);

            if (maxHealth != null) {
                list.add(String.format("Max Health: %.3f", maxHealth.getValue()));
            }
            if (followRange != null) {
                list.add(String.format("Follow Range: %.3f", followRange.getValue()));
            }
            if (knockbackResistance != null) {
                list.add(String.format("Knockback Resistance: %.3f", knockbackResistance.getValue()));
            }
            if (movementSpeed != null) {
                list.add(String.format("Movement Speed: %.3f", movementSpeed.getValue()));
            }
            if (flyingSpeed != null) {
                list.add(String.format("Flying Speed: %.3f", flyingSpeed.getValue()));
            }
            if (attackDamage != null) {
                list.add(String.format("Attack Damage: %.3f", attackDamage.getValue()));
            }
            if (attackKnockback != null) {
                list.add(String.format("Attack Knockback: %.3f", attackKnockback.getValue()));
            }
            if (attackSpeed != null) {
                list.add(String.format("Attack Speed: %.3f", attackSpeed.getValue()));
            }
            if (armor != null) {
                list.add(String.format("Armor: %.3f", armor.getValue()));
            }
            if (armorToughness != null) {
                list.add(String.format("Armor Toughness: %.3f", armorToughness.getValue()));
            }
            if (luck != null) {
                list.add(String.format("Luck: %.3f", luck.getValue()));
            }
            if (zombieSpawnReinforcements != null) {
                list.add(String.format("Zombie Spawn Reinforcements: %.3f", zombieSpawnReinforcements.getValue()));
            }
            if (horseJumpStrength != null) {
                list.add(String.format("Horse Jump Strength: %.3f", horseJumpStrength.getValue()));
            }

            Hand activeHand = livingEntity.getActiveHand();
            list.add(String.format("Active Hand: %s", activeHand));
            if (activeHand != null) {
                list.add(String.format("Active Item: %s", livingEntity.getStackInHand(activeHand).getItem().toString()));
            }
            list.add(String.format("Offhand Item: %s", livingEntity.getOffHandStack().getItem().toString()));

            if (livingEntity.getAttacker() != null) {
                list.add(String.format("Attacker: %s", livingEntity.getAttacker().getName().getString()));
            }

            if (livingEntity.getAttacking() != null) {
                list.add(String.format("Target: %s", livingEntity.getAttacking().getName().getString()));
            }

            if (livingEntity instanceof PlayerEntity playerEntity) {
                list.add(String.format("Hunger: %d", playerEntity.getHungerManager().getFoodLevel()));
                list.add(String.format("Saturation: %.3f", playerEntity.getHungerManager().getSaturationLevel()));
                list.add(String.format("Exhaustion: %.3f", playerEntity.getHungerManager().getExhaustion()));
                list.add(String.format("Previous Food Level: %d", playerEntity.getHungerManager().getPrevFoodLevel()));
            }

            if (!livingEntity.getStatusEffects().isEmpty()) {
                list.add("Status Effects:");
                for (StatusEffectInstance effect : livingEntity.getStatusEffects()) {
                    list.add(String.format("  - %s (Amplifier: %d, Duration: %d ticks)",
                            effect.getEffectType().getName().getString(),
                            effect.getAmplifier(),
                            effect.getDuration()));
                }
            }

            list.add(String.format("Air: %d/%d", livingEntity.getAir(), livingEntity.getMaxAir()));
            list.add(String.format("Fall Distance: %.3f", livingEntity.fallDistance));
            list.add(String.format("Fire: %d ticks", livingEntity.getFireTicks()));
            list.add(String.format("No Gravity: %b", livingEntity.hasNoGravity()));

            list.add(String.format("UUID: %s", livingEntity.getUuidAsString()));
            list.add(String.format("Position: [X: %.2f, Y: %.2f, Z: %.2f]", livingEntity.getX(), livingEntity.getY(), livingEntity.getZ()));

            list.add(String.format("Bounding Box: [Min: %.2f, %.2f, %.2f | Max: %.2f, %.2f, %.2f]",
                    livingEntity.getBoundingBox().minX,
                    livingEntity.getBoundingBox().minY,
                    livingEntity.getBoundingBox().minZ,
                    livingEntity.getBoundingBox().maxX,
                    livingEntity.getBoundingBox().maxY,
                    livingEntity.getBoundingBox().maxZ));
        }
    }
}
