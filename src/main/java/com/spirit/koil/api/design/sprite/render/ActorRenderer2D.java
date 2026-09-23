package com.spirit.koil.api.design.sprite.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.design.particle.UiItemTextureResolver;
import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.EntityActor;
import com.spirit.koil.api.design.sprite.actor.ExperienceOrbActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.actor.PrimedTntActor;
import com.spirit.koil.api.design.sprite.actor.ProjectileActor;
import com.spirit.koil.api.design.sprite.gameplay.DirectionalItemProfile;
import com.spirit.koil.api.design.sprite.gameplay.ItemCapabilityRegistry;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.systems.SceneLightingSystem;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.entity.TntMinecartEntityRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.RotationAxis;

/**
 * Renders scene-native dynamic actors.
 *
 * <p>Rendering observes actor state. It never owns fuse/use/projectile state.
 * Special native gameplay actors use the same visual family Minecraft uses:
 * primed TNT is a flashing block, while ranged generated items resolve their
 * real model override texture from the active resource pack.</p>
 */
public final class ActorRenderer2D {
    private static final Identifier EXPERIENCE_ORB_TEXTURE =
            new Identifier("minecraft", "textures/entity/experience_orb.png");

    public void render(DrawContext context, Scene scene) {
        render(context, scene, SceneDepthCompositor.build(scene), null);
    }

    public void render(DrawContext context, Scene scene, Integer depthFilter) {
        render(context, scene, SceneDepthCompositor.build(scene, depthFilter), depthFilter);
    }

    void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask) {
        render(context, scene, depthMask, null);
    }

    /** Render exactly one hidden-axis slice for deterministic back-to-front composition. */
    void renderDepth(DrawContext context, Scene scene,
                     SceneDepthCompositor.DepthMask depthMask, int depth) {
        render(context, scene, depthMask, depth);
    }

    private void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask, Integer depthFilter) {
        if (context == null || scene == null) return;
        if (depthMask == null) depthMask = SceneDepthCompositor.build(scene, depthFilter);
        float alpha = scene.clock().renderAlpha();
        for (Actor actor : scene.actors().actors()) {
            if (actor == null || actor.removed()) continue;
            if (depthFilter != null && actor.depth() != depthFilter) continue;
            if (!depthMask.renderActor(scene, actor.interpolatedX(alpha), actor.interpolatedY(alpha), actor.depth())) continue;
            if (actor instanceof ExperienceOrbActor orb) {
                renderExperienceOrb(context, scene, orb, alpha);
                continue;
            }
            if (actor instanceof PrimedTntActor tnt) {
                renderPrimedTnt(context, scene, tnt, alpha);
                continue;
            }
            renderItemBackedActor(context, scene, actor, alpha);
        }
    }

    private static void renderItemBackedActor(DrawContext context, Scene scene, Actor actor, float alpha) {
        ItemStack stack = displayStack(actor);
        if (stack.isEmpty()) return;

        float x = actor.interpolatedX(alpha);
        float y = actor.interpolatedY(alpha);
        SceneCellPos lightingCell = scene.projection().screenToCellAtDepth(x, y, actor.depth());
        SceneLightingSystem.LightColor sceneLight = scene.lighting().color(lightingCell);
        float actorRotation = actor.interpolatedRotation(alpha);
        // ItemActor rotation is exactly what the user sees. ProjectileActor rotation
        // instead stores its velocity heading, so compensate only projectile art's
        // intrinsic forward axis to keep the visible tip on the trajectory.
        float visualRotation = actor instanceof ProjectileActor projectile
                && isDirectionalProjectile(projectile)
                ? DirectionalItemProfile.projectileRenderRotationDegrees(stack, actorRotation)
                : actorRotation;

        MatrixStack matrices = context.getMatrices();
        // Shader color is a draw-time uniform. Flush prior GUI geometry before
        // applying per-actor RGB scene light so unrelated widgets are not tinted.
        context.draw();
        matrices.push();
        try {
            RenderSystem.setShaderColor(sceneLight.r(), sceneLight.g(), sceneLight.b(), 1.0F);
            matrices.translate(x, y, 180.0F);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(visualRotation));
            float scale = 0.70F;
            if (actor instanceof ProjectileActor projectile) {
                scale = projectile.projectileKind() == ItemCapabilityRegistry.ProjectileKind.TRIDENT
                        ? 0.78F : 0.62F;
            } else if (actor instanceof EntityActor) {
                scale = 0.82F;
            }
            matrices.scale(scale, scale, 1.0F);

            if (!renderDetachedUseVisual(context, actor, stack)) {
                context.drawItem(stack, -8, -8);
            }
            context.draw();
        } catch (RuntimeException ignored) {
            // A modded item model must not take down the detached scene.
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            matrices.pop();
        }
    }

    /**
     * Bow/crossbow/trident model overrides normally query a living user. Koil has
     * no fake player/world, so resolve the same JSON override from authoritative
     * ItemActor use state and draw its actual resource-pack texture.
     */
    private static boolean renderDetachedUseVisual(DrawContext context, Actor actor, ItemStack stack) {
        if (!(actor instanceof ItemActor itemActor) || !supportsDetachedUseVisual(stack)) return false;
        boolean active = itemActor.usingItem();
        boolean charged = stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
        if (!active && !charged) return false;

        Identifier texture = UiItemTextureResolver.resolve(stack, itemActor.useProgress(), active);
        Identifier spriteId = atlasSpriteId(texture);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || spriteId == null) return false;
        try {
            Sprite sprite = client.getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE).apply(spriteId);
            if (sprite == null) return false;
            // Draw the stitched sprite, not the source PNG. This preserves native
            // .mcmeta animation and the active resource pack just like item models.
            context.drawSprite(-8, -8, 0, 16, 16, sprite);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Identifier atlasSpriteId(Identifier textureResource) {
        if (textureResource == null) return null;
        String path = textureResource.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        if (path.isBlank()) return null;
        try { return new Identifier(textureResource.getNamespace(), path); }
        catch (RuntimeException ignored) { return null; }
    }

    private static boolean supportsDetachedUseVisual(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem) return true;
        try {
            UseAction action = stack.getItem().getUseAction(stack);
            return action == UseAction.BOW || action == UseAction.SPEAR;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isDirectionalProjectile(ProjectileActor projectile) {
        if (projectile == null) return false;
        return switch (projectile.projectileKind()) {
            case ARROW, TRIDENT, FIREWORK -> true;
            default -> false;
        };
    }

    /**
     * Reproduces vanilla primed-TNT presentation without constructing TntEntity.
     * Minecraft's own flashing-block helper supplies the normal block model and
     * white overlay. The late-fuse pulse and five-tick flash cadence follow
     * TntEntityRenderer semantics and remain centered on the actor body.
     */
    private static void renderPrimedTnt(DrawContext context, Scene scene,
                                        PrimedTntActor tnt, float physicsAlpha) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getBlockRenderManager() == null) return;

        float x = tnt.interpolatedX(physicsAlpha);
        float y = tnt.interpolatedY(physicsAlpha);
        float partial = scene.clock().gameRenderAlpha();
        float fuse = Math.max(0.0F, tnt.fuseTicks() - partial + 1.0F);
        float pulse = 1.0F;
        if (fuse < 10.0F) {
            float f = 1.0F - fuse / 10.0F;
            f = clamp(f, 0.0F, 1.0F);
            f *= f;
            f *= f;
            pulse = 1.0F + f * 0.30F;
        }
        // Vanilla alternates the primed-TNT white overlay in five-tick bands
        // for the fuse duration. The native helper renders the overlay itself.
        boolean flash = tnt.fuseTicks() > 0 && (tnt.fuseTicks() / 5) % 2 == 0;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(x, y, 181.0F);
            matrices.scale(pulse, pulse, 1.0F);
            // renderFlashingBlock renders the actual block model in 0..1 block
            // coordinates. Center that unit cube on the actor's 16px body.
            matrices.translate(-8.0F, 8.0F, 0.0F);
            matrices.scale(16.0F, -16.0F, 3.2F);
            SceneCellPos lightCell = scene.projection().screenToCellAtDepth(x, y, tnt.depth());
            int packedLight = LightmapTextureManager.pack(
                    scene.lighting().blockLight(lightCell), scene.lighting().skyLight(lightCell));
            SceneLightingSystem.LightColor lightColor = scene.lighting().color(lightCell);
            context.draw();
            RenderSystem.setShaderColor(lightColor.r(), lightColor.g(), lightColor.b(), 1.0F);
            TntMinecartEntityRenderer.renderFlashingBlock(
                    client.getBlockRenderManager(), Blocks.TNT.getDefaultState(), matrices,
                    context.getVertexConsumers(), packedLight, flash);
            // Native block buffers must be consumed while this transform is live.
            context.draw();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (RuntimeException ignored) {
            // Resource reloads can transiently invalidate the baked TNT model.
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            matrices.pop();
        }
    }

    private static void renderExperienceOrb(DrawContext context, Scene scene, ExperienceOrbActor orb, float alpha) {
        int index = orb.textureIndex();
        int u = (index % 4) * 16;
        int v = (index / 4) * 16;
        float x = orb.interpolatedX(alpha);
        float y = orb.interpolatedY(alpha);
        SceneCellPos lightCell = scene.projection().screenToCellAtDepth(x, y, orb.depth());
        SceneLightingSystem.LightColor lightColor = scene.lighting().color(lightCell);
        MatrixStack matrices = context.getMatrices();
        context.draw();
        matrices.push();
        try {
            RenderSystem.setShaderColor(lightColor.r(), lightColor.g(), lightColor.b(), 1.0F);
            matrices.translate(x, y, 182.0F);
            float pulse = 0.54F + (float) Math.sin((orb.ageTicks() + alpha) * 0.18F) * 0.04F;
            matrices.scale(pulse, pulse, 1.0F);
            context.drawTexture(EXPERIENCE_ORB_TEXTURE, -8, -8, (float) u, (float) v, 16, 16, 64, 64);
            context.draw();
        } catch (RuntimeException ignored) {
            // Resource packs may replace/remove the native texture; one orb must not break the scene.
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            matrices.pop();
        }
    }

    private static ItemStack displayStack(Actor actor) {
        if (actor instanceof ProjectileActor projectile) return projectile.stack();
        if (actor instanceof ItemActor item && item.authority() == Actor.Authority.SCENE) return item.stack();
        if (actor instanceof EntityActor entity) {
            ItemStack explicit = entity.visualStack();
            if (!explicit.isEmpty()) return explicit;
            try {
                EntityType<?> type = Registries.ENTITY_TYPE.get(entity.entityType());
                SpawnEggItem egg = SpawnEggItem.forEntity(type);
                if (egg != null) return new ItemStack(egg);
            } catch (RuntimeException ignored) { }
        }
        return ItemStack.EMPTY;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
