package com.spirit.koil.api.design.sprite.physics;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.FallingBlockActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.SceneBlockView;
import com.spirit.koil.api.design.sprite.systems.FluidSystem;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Fixed-step scene-native physics for Koil actors.
 *
 * <p>This world is detached from ClientWorld/ServerWorld. Static geometry is rebuilt
 * from the authoritative {@link BlockGrid} and real Minecraft
 * {@link BlockState#getCollisionShape(BlockView, BlockPos)} calls against a scene-backed
 * {@link BlockView}. Dynamic motion is solved at the SceneClock physics frequency,
 * independent from rendering FPS and Minecraft's 20 TPS logic.</p>
 */
public final class KoilPhysicsWorld {
    private static final float SWEEP_EPSILON = 0.015F;
    private static final float CONTACT_EPSILON = 0.35F;
    private static final int MAX_SWEEP_ITERATIONS = 6;
    private static final int MAX_DEPENETRATION_ITERATIONS = 8;
    private static final float FALLING_LAND_SPEED = 12.0F;

    public record Statistics(
            long staticGeometryRevision,
            int staticColliders,
            int staticBuckets,
            int dynamicBuckets,
            int contactsLastStep,
            int sleepingActors,
            int fallingActors
    ) { }

    private record SweepHit(StaticCollider2D collider, float time, float normalX, float normalY) { }
    private record ActorPair(long low, long high) {
        static ActorPair of(long a, long b) { return a < b ? new ActorPair(a, b) : new ActorPair(b, a); }
    }

    private final BlockGrid blocks;
    private final FluidGrid fluids;
    private final ActorWorld actors;
    private final SceneProjection projection;
    private final FluidSystem fluidSystem;
    private final SceneEventBus events;
    private final LongSupplier physicsStep;
    private final LongSupplier gameTick;
    private final List<StaticCollider2D> staticColliders = new ArrayList<>();
    private final SpatialHash<StaticCollider2D> staticHash = new SpatialHash<>(32.0F);
    private final SpatialHash<DynamicCollider2D> dynamicHash = new SpatialHash<>(32.0F);
    private long compiledBlockRevision = Long.MIN_VALUE;
    private long compiledProjectionRevision = Long.MIN_VALUE;
    private int contactsLastStep;
    private float viewportWidth = 854.0F;
    private float viewportHeight = 480.0F;
    private boolean viewportCollision = true;

    public KoilPhysicsWorld(BlockGrid blocks, FluidGrid fluids, ActorWorld actors,
                            SceneProjection projection, FluidSystem fluidSystem,
                            SceneEventBus events, LongSupplier physicsStep, LongSupplier gameTick) {
        this.blocks = blocks;
        this.fluids = fluids;
        this.actors = actors;
        this.projection = projection;
        this.fluidSystem = fluidSystem;
        this.events = events;
        this.physicsStep = physicsStep;
        this.gameTick = gameTick;
    }

    public void setViewport(float width, float height) {
        if (Float.isFinite(width) && width > 0.0F) viewportWidth = width;
        if (Float.isFinite(height) && height > 0.0F) viewportHeight = height;
    }

    public void setViewportCollision(boolean value) { viewportCollision = value; }
    public float viewportWidth() { return viewportWidth; }
    public float viewportHeight() { return viewportHeight; }

    /** Minecraft-tick hook. Falling blocks are promoted from grid cells to actors here. */
    public void minecraftTick() {
        promoteUnsupportedFallingBlocks();
    }

    /** Runs one fixed physics step. */
    public void step(float dt) {
        if (!(dt > 0.0F) || !Float.isFinite(dt)) return;
        rebuildStaticGeometryIfNeeded();
        contactsLastStep = 0;

        List<Actor> snapshot = new ArrayList<>(actors.actors());
        snapshot.sort(Comparator.comparingLong(Actor::id));
        List<Long> removeAfterStep = new ArrayList<>();

        for (Actor actor : snapshot) {
            if (actor == null || actor.removed()) continue;
            PhysicsBody2D body = actor.body();
            body.beginStep();

            if (body.kinematic()) {
                // Dragged/externally controlled actors still sweep from the prior
                // pose to the pointer-requested pose. Kinematic means no gravity or
                // solver impulse ownership, not "ignore scene geometry".
                if (body.collideWorld()) resolveStaticMotion(actor, body);
                if (viewportCollision && body.collideSceneBounds()) resolveViewport(actor, body);
                body.setGrounded(body.grounded() || hasGroundSupport(body));
                body.updateSleep(dt, body.grounded());
                body.finishStep();
                continue;
            }

            if (body.sleeping()) {
                boolean support = hasGroundSupport(body);
                body.setGrounded(support);
                if (!support) body.wake();
                else {
                    body.updateSleep(dt, true);
                    body.finishStep();
                    continue;
                }
            }

            float beforeX = body.x();
            float beforeY = body.y();
            body.integrateVelocity(dt);
            fluidSystem.applyActorFluidForces(actor, dt);
            body.integratePosition(dt);

            if (body.collideWorld()) resolveStaticMotion(actor, body);
            if (viewportCollision && body.collideSceneBounds()) resolveViewport(actor, body);
            body.updateSleep(dt, body.grounded());

            if (events != null && (Math.abs(body.x() - beforeX) > 0.0001F || Math.abs(body.y() - beforeY) > 0.0001F)) {
                events.publish(new SceneEvent.ActorMoved(actor.id(), beforeX, beforeY,
                        body.x(), body.y(), physicsStep.getAsLong()));
            }

            if (actor instanceof FallingBlockActor falling && shouldLand(falling)) {
                landFallingActor(falling);
                removeAfterStep.add(actor.id());
            }
            body.finishStep();
        }

        resolveActorContacts(removeAfterStep);
        for (Long id : removeAfterStep) actors.remove(id);
    }

    public Statistics statistics() {
        int sleeping = 0;
        int falling = 0;
        for (Actor actor : actors.actors()) {
            if (actor.body().sleeping()) sleeping++;
            if (actor instanceof FallingBlockActor) falling++;
        }
        return new Statistics(compiledBlockRevision, staticColliders.size(), staticHash.bucketCount(),
                dynamicHash.bucketCount(), contactsLastStep, sleeping, falling);
    }

    public List<StaticCollider2D> staticColliders() { return List.copyOf(staticColliders); }

    public void invalidateStaticGeometry() {
        compiledBlockRevision = Long.MIN_VALUE;
        compiledProjectionRevision = Long.MIN_VALUE;
    }

    public void reset() {
        staticColliders.clear();
        staticHash.clear();
        dynamicHash.clear();
        compiledBlockRevision = Long.MIN_VALUE;
        compiledProjectionRevision = Long.MIN_VALUE;
        contactsLastStep = 0;
    }

    private void promoteUnsupportedFallingBlocks() {
        List<BlockGrid.Entry> entries = new ArrayList<>(blocks.entries());
        entries.sort(Comparator
                .comparingInt((BlockGrid.Entry e) -> e.position().depth())
                .thenComparingInt(e -> e.position().x())
                .thenComparingInt(e -> e.position().y()));

        for (BlockGrid.Entry entry : entries) {
            SceneCellPos position = entry.position();
            BlockCell cell = blocks.get(position);
            if (cell == null || cell.blockState() == null || cell.blockState().isAir()) continue;
            if (!(cell.blockState().getBlock() instanceof FallingBlock)) continue;

            SceneCellPos below = projection.interactionOffset(position, Direction.DOWN);
            if (below == null) continue;
            BlockState belowState = blocks.getBlockState(below);
            if (!canFallThrough(belowState)) continue;

            BlockCell removed = blocks.remove(position);
            if (removed == null) continue;
            long id = removed.authority() == BlockCell.Authority.LEGACY_PROXY && removed.sourceId() >= 0L
                    ? removed.sourceId() : actors.allocateNativeId();
            if (actors.get(id) != null) continue;

            Actor.Authority actorAuthority = removed.authority() == BlockCell.Authority.LEGACY_PROXY
                    ? Actor.Authority.LEGACY_PROXY : Actor.Authority.SCENE;
            FallingBlockActor falling = new FallingBlockActor(id, actorAuthority,
                    removed.blockState(), position,
                    projection.cellCenterScreenX(position), projection.cellCenterScreenY(position),
                    projection.depthCoordinate(position));
            falling.captureSourceCell(removed);
            falling.setSourceBlockAuthority(removed.authority(), removed.sourceId());
            actors.put(falling);
        }
    }

    private boolean canFallThrough(BlockState state) {
        if (state == null || state.isAir()) return true;
        try {
            if (state.isReplaceable()) return true;
            FluidState fluid = state.getFluidState();
            return fluid != null && !fluid.isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void rebuildStaticGeometryIfNeeded() {
        if (compiledBlockRevision == blocks.revision()
                && compiledProjectionRevision == projection.revision()) return;

        staticColliders.clear();
        staticHash.clear();
        staticHash.setCellSize(Math.max(16.0F, projection.cellPixels() * 2.0F));

        SceneBlockView view = new SceneBlockView(blocks, fluids);

        for (BlockGrid.Entry entry : blocks.entries()) {
            SceneCellPos position = entry.position();
            BlockCell authoredCell = entry.cell();
            BlockState state = authoredCell.blockState();
            if (state == null || state.isAir() || !authoredCell.runtimeBoolean("collision_enabled", true)) continue;
            VoxelShape shape;
            try {
                shape = state.getCollisionShape(view, projection.toMinecraft(position));
            } catch (RuntimeException ignored) {
                continue;
            }
            if (shape == null || shape.isEmpty()) continue;
            float slipperiness;
            try { slipperiness = state.getBlock().getSlipperiness(); }
            catch (RuntimeException ignored) { slipperiness = 0.6F; }
            slipperiness = authoredCell.runtimeFloat("slipperiness", slipperiness);
            boolean slime = state.isOf(Blocks.SLIME_BLOCK);
            boolean honey = state.isOf(Blocks.HONEY_BLOCK);
            float surfaceRestitution = authoredCell.runtimeFloat("restitution", slime ? 0.91F : (honey ? 0.12F : 0.0F));
            float surfaceFriction = authoredCell.runtimeFloat("surface_friction", honey ? 0.56F : 1.0F);
            int depth = projection.depthCoordinate(position);
            for (Box box : shape.getBoundingBoxes()) {
                CollisionShape2D projected = projectShape(position, box);
                if (projected == null || projected.width() < 0.0001F || projected.height() < 0.0001F) continue;
                StaticCollider2D collider = new StaticCollider2D(position, state, depth, projected,
                        slipperiness, surfaceRestitution, surfaceFriction, slime, honey);
                staticColliders.add(collider);
                staticHash.insert(collider, projected, depth);
            }
        }

        compiledBlockRevision = blocks.revision();
        compiledProjectionRevision = projection.revision();
    }

    private CollisionShape2D projectShape(SceneCellPos cell, Box box) {
        float pixels = projection.cellPixels();
        float centerX = projection.cellCenterScreenX(cell);
        float centerY = projection.cellCenterScreenY(cell);
        return switch (projection.mode()) {
            case XY_SIDE -> new CollisionShape2D(
                    centerX + ((float) box.minX - 0.5F) * pixels,
                    centerY - ((float) box.maxY - 0.5F) * pixels,
                    centerX + ((float) box.maxX - 0.5F) * pixels,
                    centerY - ((float) box.minY - 0.5F) * pixels);
            case XZ_TOP -> new CollisionShape2D(
                    centerX + ((float) box.minX - 0.5F) * pixels,
                    centerY + ((float) box.minZ - 0.5F) * pixels,
                    centerX + ((float) box.maxX - 0.5F) * pixels,
                    centerY + ((float) box.maxZ - 0.5F) * pixels);
            case ZY_SIDE -> new CollisionShape2D(
                    centerX + ((float) box.minZ - 0.5F) * pixels,
                    centerY - ((float) box.maxY - 0.5F) * pixels,
                    centerX + ((float) box.maxZ - 0.5F) * pixels,
                    centerY - ((float) box.minY - 0.5F) * pixels);
        };
    }

    private void resolveStaticMotion(Actor actor, PhysicsBody2D body) {
        List<StaticCollider2D> broad = staticHash.query(body.sweptBounds().expanded(1.0F, 1.0F), body.depth());
        if (broad.isEmpty()) return;

        float[] start = depenetrate(body.previousX(), body.previousY(), body.halfWidth(), body.halfHeight(), broad);
        float x = start[0];
        float y = start[1];
        float remainingX = body.x() - x;
        float remainingY = body.y() - y;

        for (int iteration = 0; iteration < MAX_SWEEP_ITERATIONS; iteration++) {
            if (Math.abs(remainingX) < 0.0001F && Math.abs(remainingY) < 0.0001F) break;
            SweepHit hit = earliestHit(x, y, remainingX, remainingY, body, broad);
            if (hit == null) {
                x += remainingX;
                y += remainingY;
                break;
            }

            float travelBackoff = SWEEP_EPSILON / Math.max(1.0F, Math.abs(remainingX) + Math.abs(remainingY));
            float travel = Math.max(0.0F, hit.time() - travelBackoff);
            x += remainingX * travel;
            y += remainingY * travel;

            float impact = Math.abs(hit.normalX() != 0.0F ? body.velocityX() : body.velocityY());
            boolean grounded = hit.normalY() < -0.5F;
            applyStaticResponse(body, hit.collider(), hit.normalX(), hit.normalY(), impact);
            if (grounded) body.setGrounded(true);
            publishContact(new ContactManifold(actor.id(), null, hit.collider().cell(), hit.collider().state(),
                    hit.normalX(), hit.normalY(), impact, grounded));

            float leftover = Math.max(0.0F, 1.0F - hit.time());
            remainingX *= leftover;
            remainingY *= leftover;
            if (hit.normalX() != 0.0F) remainingX = 0.0F;
            if (hit.normalY() != 0.0F) remainingY = 0.0F;
            x += hit.normalX() * SWEEP_EPSILON;
            y += hit.normalY() * SWEEP_EPSILON;
        }

        float[] corrected = depenetrate(x, y, body.halfWidth(), body.halfHeight(), broad);
        body.moveTo(corrected[0], corrected[1]);
    }

    private SweepHit earliestHit(float x, float y, float dx, float dy, PhysicsBody2D body,
                                  List<StaticCollider2D> colliders) {
        SweepHit best = null;
        for (StaticCollider2D collider : colliders) {
            CollisionShape2D expanded = collider.bounds().expanded(body.halfWidth(), body.halfHeight());
            SweepHit candidate = sweepPoint(x, y, dx, dy, collider, expanded);
            if (candidate == null) continue;
            if (best == null || candidate.time() < best.time() - 0.000001F
                    || (Math.abs(candidate.time() - best.time()) < 0.000001F
                    && compareCollider(candidate.collider(), best.collider()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private SweepHit sweepPoint(float x, float y, float dx, float dy,
                                StaticCollider2D collider, CollisionShape2D box) {
        float nearX;
        float farX;
        if (Math.abs(dx) < 0.000001F) {
            if (x <= box.left() || x >= box.right()) return null;
            nearX = Float.NEGATIVE_INFINITY;
            farX = Float.POSITIVE_INFINITY;
        } else {
            nearX = (box.left() - x) / dx;
            farX = (box.right() - x) / dx;
            if (nearX > farX) { float t = nearX; nearX = farX; farX = t; }
        }

        float nearY;
        float farY;
        if (Math.abs(dy) < 0.000001F) {
            if (y <= box.top() || y >= box.bottom()) return null;
            nearY = Float.NEGATIVE_INFINITY;
            farY = Float.POSITIVE_INFINITY;
        } else {
            nearY = (box.top() - y) / dy;
            farY = (box.bottom() - y) / dy;
            if (nearY > farY) { float t = nearY; nearY = farY; farY = t; }
        }

        float near = Math.max(nearX, nearY);
        float far = Math.min(farX, farY);
        if (near > far || far < 0.0F || near > 1.0F || near < 0.0F) return null;

        float nx = 0.0F;
        float ny = 0.0F;
        if (nearX > nearY) nx = dx > 0.0F ? -1.0F : 1.0F;
        else ny = dy > 0.0F ? -1.0F : 1.0F;
        return new SweepHit(collider, near, nx, ny);
    }

    private float[] depenetrate(float x, float y, float hw, float hh, List<StaticCollider2D> colliders) {
        float px = x;
        float py = y;
        for (int iteration = 0; iteration < MAX_DEPENETRATION_ITERATIONS; iteration++) {
            StaticCollider2D bestCollider = null;
            float bestDx = 0.0F;
            float bestDy = 0.0F;
            float bestDistance = Float.POSITIVE_INFINITY;
            CollisionShape2D actorBounds = new CollisionShape2D(px - hw, py - hh, px + hw, py + hh);
            for (StaticCollider2D collider : colliders) {
                CollisionShape2D b = collider.bounds();
                if (!actorBounds.intersects(b)) continue;
                float pushLeft = b.left() - actorBounds.right();
                float pushRight = b.right() - actorBounds.left();
                float pushUp = b.top() - actorBounds.bottom();
                float pushDown = b.bottom() - actorBounds.top();
                float[] values = {pushLeft, pushRight, pushUp, pushDown};
                for (int i = 0; i < values.length; i++) {
                    float distance = Math.abs(values[i]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCollider = collider;
                        bestDx = i == 0 || i == 1 ? values[i] : 0.0F;
                        bestDy = i == 2 || i == 3 ? values[i] : 0.0F;
                    }
                }
            }
            if (bestCollider == null) break;
            px += bestDx;
            py += bestDy;
        }
        return new float[]{px, py};
    }

    private void applyStaticResponse(PhysicsBody2D body, StaticCollider2D collider,
                                     float nx, float ny, float impact) {
        float vx = body.velocityX();
        float vy = body.velocityY();
        float restitution = Math.max(body.restitution(), collider.restitution());

        if (collider.slime() && ny < -0.5F && vy > 0.0F) {
            vy = -Math.max(24.0F, Math.abs(vy) * Math.max(0.82F, restitution));
            body.wake();
        } else if (nx != 0.0F) {
            vx = -vx * restitution;
        } else if (ny != 0.0F) {
            vy = -vy * restitution;
            if (ny < -0.5F && impact < 28.0F && restitution < 0.35F) vy = 0.0F;
        }

        float tangentialRetention = clamp01(body.surfaceFriction() * collider.surfaceFriction()
                * Math.max(0.25F, collider.slipperiness()));
        if (ny != 0.0F) vx *= tangentialRetention;
        if (nx != 0.0F) vy *= tangentialRetention;
        if (collider.honey()) {
            vx *= 0.45F;
            if (vy > 22.0F) vy = 22.0F;
        }
        body.setVelocity(vx, vy);
    }

    private void resolveViewport(Actor actor, PhysicsBody2D body) {
        float x = body.x();
        float y = body.y();
        float vx = body.velocityX();
        float vy = body.velocityY();
        boolean changed = false;

        if (x - body.halfWidth() < 0.0F) {
            float impact = Math.abs(vx);
            x = body.halfWidth();
            vx = Math.abs(vx) * body.restitution();
            publishContact(new ContactManifold(actor.id(), null, null, null, 1.0F, 0.0F, impact, false));
            changed = true;
        } else if (x + body.halfWidth() > viewportWidth) {
            float impact = Math.abs(vx);
            x = viewportWidth - body.halfWidth();
            vx = -Math.abs(vx) * body.restitution();
            publishContact(new ContactManifold(actor.id(), null, null, null, -1.0F, 0.0F, impact, false));
            changed = true;
        }
        if (y - body.halfHeight() < 0.0F) {
            float impact = Math.abs(vy);
            y = body.halfHeight();
            vy = Math.abs(vy) * body.restitution();
            publishContact(new ContactManifold(actor.id(), null, null, null, 0.0F, 1.0F, impact, false));
            changed = true;
        } else if (y + body.halfHeight() > viewportHeight) {
            float impact = Math.abs(vy);
            y = viewportHeight - body.halfHeight();
            vy = -Math.abs(vy) * body.restitution();
            if (impact < 28.0F && body.restitution() < 0.35F) vy = 0.0F;
            body.setGrounded(true);
            publishContact(new ContactManifold(actor.id(), null, null, null, 0.0F, -1.0F, impact, true));
            changed = true;
        }
        if (changed) {
            body.moveTo(x, y);
            body.setVelocity(vx, vy);
        }
    }

    private boolean hasGroundSupport(PhysicsBody2D body) {
        if (body == null) return false;
        if (viewportCollision && body.collideSceneBounds()
                && body.y() + body.halfHeight() >= viewportHeight - CONTACT_EPSILON) return true;
        CollisionShape2D feet = new CollisionShape2D(
                body.x() - Math.max(0.25F, body.halfWidth() - 0.5F),
                body.y() + body.halfHeight() - 0.2F,
                body.x() + Math.max(0.25F, body.halfWidth() - 0.5F),
                body.y() + body.halfHeight() + CONTACT_EPSILON);
        for (StaticCollider2D collider : staticHash.query(feet, body.depth())) {
            if (feet.intersects(collider.bounds())) return true;
        }
        return false;
    }

    private void resolveActorContacts(List<Long> removeAfterStep) {
        dynamicHash.clear();
        dynamicHash.setCellSize(Math.max(16.0F, projection.cellPixels() * 2.0F));
        List<Actor> snapshot = new ArrayList<>(actors.actors());
        snapshot.sort(Comparator.comparingLong(Actor::id));
        for (Actor actor : snapshot) {
            if (actor.removed() || !actor.body().collideActors()) continue;
            dynamicHash.insert(new DynamicCollider2D(actor, actor.body().bounds(), actor.depth()),
                    actor.body().bounds(), actor.depth());
        }

        Set<ActorPair> processed = new HashSet<>();
        for (Actor a : snapshot) {
            if (a.removed() || !a.body().collideActors()) continue;
            List<DynamicCollider2D> near = dynamicHash.query(a.body().bounds().expanded(1.0F, 1.0F), a.depth());
            for (DynamicCollider2D entry : near) {
                Actor b = entry.actor();
                if (b == null || b.id() == a.id() || b.removed() || !b.body().collideActors()) continue;
                ActorPair key = ActorPair.of(a.id(), b.id());
                if (!processed.add(key)) continue;
                resolveActorPair(a, b);
            }
        }

        for (Actor actor : snapshot) {
            if (actor instanceof FallingBlockActor falling && !removeAfterStep.contains(actor.id()) && shouldLand(falling)) {
                landFallingActor(falling);
                removeAfterStep.add(actor.id());
            }
        }
    }

    private void resolveActorPair(Actor a, Actor b) {
        if (a.depth() != b.depth()) return;
        PhysicsBody2D ba = a.body();
        PhysicsBody2D bb = b.body();
        CollisionShape2D aa = ba.bounds();
        CollisionShape2D ab = bb.bounds();
        if (!aa.intersects(ab)) return;

        float overlapX = Math.min(aa.right(), ab.right()) - Math.max(aa.left(), ab.left());
        float overlapY = Math.min(aa.bottom(), ab.bottom()) - Math.max(aa.top(), ab.top());
        if (overlapX <= 0.0F || overlapY <= 0.0F) return;

        float nx = 0.0F;
        float ny = 0.0F;
        float penetration;
        if (overlapX < overlapY) {
            nx = ba.x() < bb.x() ? -1.0F : 1.0F;
            penetration = overlapX;
        } else {
            ny = ba.y() < bb.y() ? -1.0F : 1.0F;
            penetration = overlapY;
        }

        float invA = ba.inverseMass();
        float invB = bb.inverseMass();
        float invSum = invA + invB;
        if (invSum <= 0.000001F) return;

        float correction = Math.max(0.0F, penetration + 0.001F) / invSum;
        if (invA > 0.0F) ba.moveTo(ba.x() + nx * correction * invA, ba.y() + ny * correction * invA);
        if (invB > 0.0F) bb.moveTo(bb.x() - nx * correction * invB, bb.y() - ny * correction * invB);

        float rvx = ba.velocityX() - bb.velocityX();
        float rvy = ba.velocityY() - bb.velocityY();
        float velocityAlongNormal = rvx * nx + rvy * ny;
        float impact = Math.abs(velocityAlongNormal);
        if (velocityAlongNormal < 0.0F) {
            float restitution = Math.min(ba.restitution(), bb.restitution());
            float impulse = -(1.0F + restitution) * velocityAlongNormal / invSum;
            float ix = impulse * nx;
            float iy = impulse * ny;
            if (invA > 0.0F) ba.setVelocity(ba.velocityX() + ix * invA, ba.velocityY() + iy * invA);
            if (invB > 0.0F) bb.setVelocity(bb.velocityX() - ix * invB, bb.velocityY() - iy * invB);
        }

        if (ny < -0.5F) ba.setGrounded(true);
        if (ny > 0.5F) bb.setGrounded(true);
        ba.wake();
        bb.wake();
        publishContact(new ContactManifold(a.id(), b.id(), null, null, nx, ny, impact, ny < -0.5F));
        publishContact(new ContactManifold(b.id(), a.id(), null, null, -nx, -ny, impact, ny > 0.5F));
    }

    private boolean shouldLand(FallingBlockActor falling) {
        PhysicsBody2D body = falling.body();
        return body.grounded() && Math.abs(body.velocityY()) <= FALLING_LAND_SPEED && !body.kinematic();
    }

    private void landFallingActor(FallingBlockActor falling) {
        SceneCellPos target = projection.screenToCellAtDepth(falling.x(), falling.y(), falling.depth());
        if (blocks.occupied(target)) {
            Direction up = projection.screenUpDirection();
            SceneCellPos candidate = target;
            for (int i = 0; i < 4 && blocks.occupied(candidate); i++) candidate = projection.offset(candidate, up);
            target = candidate;
        }
        if (blocks.occupied(target)) return;

        BlockCell landed;
        if (falling.sourceBlockAuthority() == BlockCell.Authority.LEGACY_PROXY
                && falling.sourceBlockId() >= 0L) {
            landed = blocks.setLegacyProxy(target, falling.blockState(), falling.sourceBlockId());
        } else {
            landed = blocks.set(target, falling.blockState());
        }
        if (landed != null) {
            blocks.setFlags(target, falling.sourceFlags());
            for (java.util.Map.Entry<String, String> data : falling.sourceRuntimeData().entrySet())
                blocks.putRuntimeData(target, data.getKey(), data.getValue());
            for (java.util.Map.Entry<String, String> data : falling.sourceBlockEntityData().entrySet())
                blocks.putBlockEntityData(target, data.getKey(), data.getValue());
            blocks.restartLifetime(target);
        }
        if (events != null) events.publish(new SceneEvent.ActorLanded(
                falling.id(), target, gameTick.getAsLong()));
    }

    private void publishContact(ContactManifold contact) {
        contactsLastStep++;
        if (events != null && contact != null) {
            events.publish(new SceneEvent.PhysicsContact(contact.actorId(), contact.otherActorId(),
                    contact.blockCell(), contact.blockState(), contact.normalX(), contact.normalY(),
                    contact.impactSpeed(), contact.grounded(), physicsStep.getAsLong()));
        }
    }

    private static int compareCollider(StaticCollider2D a, StaticCollider2D b) {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        int depth = Integer.compare(a.depth(), b.depth());
        if (depth != 0) return depth;
        int x = Integer.compare(a.cell().x(), b.cell().x());
        if (x != 0) return x;
        int y = Integer.compare(a.cell().y(), b.cell().y());
        if (y != 0) return y;
        return Integer.compare(a.cell().depth(), b.cell().depth());
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

}

