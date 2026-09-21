package com.spirit.koil.api.design.particle;

import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Pointer interaction for an activated scene while the playground editor is closed.
 *
 * <p>Normal Minecraft/Koil screens can interact directly wherever their own widgets
 * do not consume the pointer. With no GUI open, holding Alt temporarily opens a
 * transparent scene-pointer layer so Koil never permanently steals normal world
 * camera/attack/use input.</p>
 */
final class SceneOverlayInteractionController {
    private long selectedActorId = -1L;
    private SceneCellPos selectedCell;
    private long draggingActorId = -1L;
    private boolean dragOriginalKinematic;
    private float lastMouseX;
    private float lastMouseY;
    private float releaseVelocityX;
    private float releaseVelocityY;
    private boolean rightUseHeld;
    private boolean previousLeft;
    private boolean previousRight;

    void update(MinecraftClient client, SpriteEngine engine, float mouseX, float mouseY) {
        if (client == null || engine == null || client.getWindow() == null) {
            cancelDrag(engine);
            previousLeft = false;
            previousRight = false;
            return;
        }

        long window = client.getWindow().getHandle();
        if (client.currentScreen == null) {
            boolean altHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
            boolean sceneHasContent = engine.scene().blocks().blockCount() > 0
                    || engine.scene().actors().size() > 0
                    || !engine.scene().fluids().entries().isEmpty();
            if (altHeld && sceneHasContent) {
                client.setScreen(new SpriteScenePointerScreen());
            }
            cancelDrag(engine);
            previousLeft = false;
            previousRight = false;
            return;
        }

        if (client.currentScreen instanceof SpritePlaygroundScreen || client.currentScreen instanceof ChatScreen) {
            cancelDrag(engine);
            previousLeft = false;
            previousRight = false;
            return;
        }

        boolean left = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // Do not steal clicks from the screen's own buttons, text fields, list rows,
        // or other interactive elements. Existing scene drags/releases still finish
        // even if the cursor crosses UI while held.
        boolean overScreenElement = client.currentScreen.hoveredElement(mouseX, mouseY).isPresent();

        if (left && !previousLeft && !overScreenElement) leftPressed(engine, mouseX, mouseY);
        if (left && draggingActorId >= 0L) drag(engine, mouseX, mouseY);
        if (!left && previousLeft) leftReleased(engine);

        if (right && !previousRight && !overScreenElement) rightPressed(engine, mouseX, mouseY);
        if (!right && previousRight) rightReleased(engine, mouseX, mouseY);

        previousLeft = left;
        previousRight = right;
    }

    void renderSelection(DrawContext context, SpriteEngine engine) {
        if (context == null || engine == null) return;
        if (selectedActorId >= 0L) {
            Actor actor = engine.actor(selectedActorId);
            if (actor == null || actor.removed()) {
                selectedActorId = -1L;
            } else {
                int x = Math.round(actor.x() - actor.halfWidth() - 2.0F);
                int y = Math.round(actor.y() - actor.halfHeight() - 2.0F);
                int w = Math.max(4, Math.round(actor.halfWidth() * 2.0F + 4.0F));
                int h = Math.max(4, Math.round(actor.halfHeight() * 2.0F + 4.0F));
                context.drawBorder(x, y, w, h, 0xD8FFFFFF);
                return;
            }
        }
        if (selectedCell != null) {
            if (engine.blockState(selectedCell) == null || engine.blockState(selectedCell).isAir()) {
                selectedCell = null;
                return;
            }
            float size = engine.scene().projection().cellPixels();
            float cx = engine.scene().projection().cellCenterScreenX(selectedCell);
            float cy = engine.scene().projection().cellCenterScreenY(selectedCell);
            context.drawBorder(Math.round(cx - size * 0.5F - 1.0F), Math.round(cy - size * 0.5F - 1.0F),
                    Math.max(3, Math.round(size + 2.0F)), Math.max(3, Math.round(size + 2.0F)), 0xD8FFFFFF);
        }
    }

    void reset() {
        selectedActorId = -1L;
        selectedCell = null;
        draggingActorId = -1L;
        rightUseHeld = false;
        previousLeft = false;
        previousRight = false;
    }

    private void leftPressed(SpriteEngine engine, float x, float y) {
        long actorId = actorAt(engine, x, y);
        if (actorId < 0L || !(engine.actor(actorId) instanceof ItemActor)) {
            SceneCellPos block = engine.sceneBlockAtScreen(x, y);
            selectedCell = block;
            if (block != null) selectedActorId = -1L;
            return;
        }
        selectedActorId = actorId;
        selectedCell = null;
        Actor actor = engine.actor(actorId);
        dragOriginalKinematic = actor.body().kinematic();
        actor.setKinematic(true);
        actor.setVelocity(0.0F, 0.0F);
        draggingActorId = actorId;
        lastMouseX = x;
        lastMouseY = y;
        releaseVelocityX = 0.0F;
        releaseVelocityY = 0.0F;
    }

    private void drag(SpriteEngine engine, float x, float y) {
        Actor actor = engine.actor(draggingActorId);
        if (actor == null || actor.removed()) {
            draggingActorId = -1L;
            return;
        }
        actor.body().teleport(x, y);
        actor.setVelocity(0.0F, 0.0F);
        releaseVelocityX = clamp((x - lastMouseX) * 10.0F, -220.0F, 220.0F);
        releaseVelocityY = clamp((y - lastMouseY) * 10.0F, -220.0F, 220.0F);
        lastMouseX = x;
        lastMouseY = y;
    }

    private void leftReleased(SpriteEngine engine) {
        if (draggingActorId < 0L) return;
        Actor actor = engine.actor(draggingActorId);
        if (actor != null) {
            actor.setKinematic(dragOriginalKinematic);
            if (dragOriginalKinematic) actor.setVelocity(0.0F, 0.0F);
            else actor.setVelocity(releaseVelocityX, releaseVelocityY);
        }
        draggingActorId = -1L;
        releaseVelocityX = releaseVelocityY = 0.0F;
        dragOriginalKinematic = false;
    }

    private void rightPressed(SpriteEngine engine, float x, float y) {
        long hoveredActor = actorAt(engine, x, y);
        if (hoveredActor >= 0L && engine.actor(hoveredActor) instanceof ItemActor) {
            // First right-click selects an item for use. A second right-click on the
            // same selected item performs its free-use action (bows, throwables, etc.).
            if (selectedActorId != hoveredActor) {
                selectedActorId = hoveredActor;
                selectedCell = null;
                return;
            }
            rightUseHeld = engine.beginItemUse(selectedActorId, x, y);
            return;
        }

        SceneCellPos block = engine.sceneBlockAtScreen(x, y);
        if (selectedActorId >= 0L && engine.actor(selectedActorId) instanceof ItemActor) {
            boolean handled = block != null && engine.useItemOnBlock(selectedActorId, block);
            if (!handled) {
                int depth = engine.actor(selectedActorId) == null ? 0 : engine.actor(selectedActorId).depth();
                SceneCellPos target = engine.scene().projection().screenToCellAtDepth(x, y, depth);
                if (block == null) handled = engine.useItemAtCell(selectedActorId, target);
            }
            if (!handled) rightUseHeld = engine.beginItemUse(selectedActorId, x, y);
            if (block != null) selectedCell = block;
            return;
        }

        if (block != null) {
            selectedCell = block;
            selectedActorId = -1L;
            engine.useBlock(block, x, y);
        }
    }

    private void rightReleased(SpriteEngine engine, float x, float y) {
        if (rightUseHeld && selectedActorId >= 0L) engine.releaseItemUse(selectedActorId, x, y);
        rightUseHeld = false;
    }

    private void cancelDrag(SpriteEngine engine) {
        if (draggingActorId >= 0L && engine != null) {
            Actor actor = engine.actor(draggingActorId);
            if (actor != null) actor.setKinematic(dragOriginalKinematic);
        }
        draggingActorId = -1L;
        rightUseHeld = false;
    }

    private static long actorAt(SpriteEngine engine, float x, float y) {
        long best = -1L;
        int bestDepth = Integer.MIN_VALUE;
        for (Actor actor : engine.scene().actors().actors()) {
            if (!(actor instanceof ItemActor) || actor.removed() || actor.authority() != Actor.Authority.SCENE) continue;
            if (x < actor.x() - actor.halfWidth() - 3.0F || x > actor.x() + actor.halfWidth() + 3.0F
                    || y < actor.y() - actor.halfHeight() - 3.0F || y > actor.y() + actor.halfHeight() + 3.0F) continue;
            if (best < 0L || actor.depth() >= bestDepth) {
                best = actor.id();
                bestDepth = actor.depth();
            }
        }
        return best;
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
