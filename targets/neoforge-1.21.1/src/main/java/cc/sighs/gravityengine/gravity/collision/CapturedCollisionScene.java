package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Immutable operation-local {@link CollisionScene}.
 *
 * <p>Every Minecraft datum needed by any legal nested route segment is
 * materialized into neutral obstacle lists before the first collision solve.
 * This scene deliberately holds no {@code Level}, {@code Entity},
 * {@code BlockState}-reading handle, {@code VoxelShape}, collision context or
 * obstacle registry. {@link #query} only filters captured immutable data and
 * fail-closes when a caller asks for a region outside the conservative
 * {@link CollisionCaptureDomain}.</p>
 */
public final class CapturedCollisionScene implements CollisionScene {
    private long diagnosticGeometryFingerprint;

    /** Content hint only, never a revision identity or collision authority.
     * Compare capture bounds as well: different domains can contain different
     * obstacles even when their shared corridor is identical. */
    public long diagnosticGeometryFingerprint() { return diagnosticGeometryFingerprint; }
    private final CollisionCaptureDomain domain;
    private final long tick;
    private final long revision;
    private final KinematicStepContext time;
    private final CollisionWorkTracker tracker;
    private final WorldBorderCollisionSnapshot worldBorder;
    private final List<BlockObstacle> blockObstacles;
    private final List<DynamicCollisionObstacleSnapshot> dynamicObstacles;
    private final List<SphereObstacle> sphereObstacles;
    private final List<EntityObstacle> poseStrictObstacles;
    private final Map<BlockPos, BlockMovementMaterialSnapshot>
            movementMaterials;
    private final int evaluatedBlockPositions;

    public CapturedCollisionScene(
            CollisionCaptureDomain domain,
            long tick,
            long revision,
            KinematicStepContext time,
            CollisionWorkTracker tracker,
            WorldBorderCollisionSnapshot worldBorder,
            List<BlockObstacle> blockObstacles,
            List<DynamicCollisionObstacleSnapshot> dynamicObstacles,
            List<SphereObstacle> sphereObstacles,
            List<EntityObstacle> poseStrictObstacles,
            Map<BlockPos, BlockMovementMaterialSnapshot> movementMaterials,
            int evaluatedBlockPositions
    ) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.tick = tick;
        this.revision = revision;
        this.time = Objects.requireNonNull(time, "time");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.worldBorder = Objects.requireNonNull(
                worldBorder, "worldBorder");
        this.blockObstacles = List.copyOf(blockObstacles);
        this.dynamicObstacles = List.copyOf(dynamicObstacles);
        DynamicEntityBroadphasePolicy.validateCaptured(this.dynamicObstacles, time);
        this.sphereObstacles = List.copyOf(sphereObstacles);
        this.poseStrictObstacles = List.copyOf(poseStrictObstacles);
        this.movementMaterials = Map.copyOf(movementMaterials);
        this.evaluatedBlockPositions = evaluatedBlockPositions;
        if (cc.sighs.gravityengine.gravity.debug.GravityDebugLog.MOVEMENT_ENABLED) {
            long hash = worldBorder.hashCode();
            for (var block : this.blockObstacles) hash = 31 * hash + block.hashCode();
            for (var sphere : this.sphereObstacles) hash = 31 * hash + sphere.hashCode();
            for (var dynamic : this.dynamicObstacles) {
                hash = 31 * hash + dynamic.hashCode();
            }
            diagnosticGeometryFingerprint = hash;
        }
    }

    /**
     * Immutable movement material evidence for one captured block position.
     *
     * <p>Material values (friction, speed factor, water/bubble-column
     * classification) are evaluated exactly once at the capture boundary and
     * frozen with the scene; movement policy never re-reads a live
     * {@code BlockState}/{@code Block}.</p>
     *
     * <p>{@code Optional.empty()} is a proven answer: the scene captured this
     * position and it contains no block movement material (air or a
     * registered-sphere replacement), which vanilla treats as the default
     * material. It is never the same as a lookup outside the captured
     * envelope, which is an explicit {@link CollisionSceneCoverageException}
     * and never a live-world fallback.</p>
     */
    public Optional<BlockMovementMaterialSnapshot> movementMaterialAt(
            BlockPos position
    ) {
        Objects.requireNonNull(position, "position");
        if (!domainContainsBlockCell(position)) {
            throw new CollisionSceneCoverageException(
                    "movement material lookup outside the captured scene "
                            + "envelope: "
                            + position
                            + " captured=" + this.domain.staticBounds());
        }
        return Optional.ofNullable(this.movementMaterials.get(position));
    }

    private boolean domainContainsBlockCell(BlockPos position) {
        Aabb3d bounds = this.domain.staticBounds();
        double minX = Math.max(bounds.minX(), position.getX());
        double minY = Math.max(bounds.minY(), position.getY());
        double minZ = Math.max(bounds.minZ(), position.getZ());
        double maxX = Math.min(bounds.maxX(), position.getX() + 1.0D);
        double maxY = Math.min(bounds.maxY(), position.getY() + 1.0D);
        double maxZ = Math.min(bounds.maxZ(), position.getZ() + 1.0D);
        return minX <= maxX + 1.0E-7D
                && minY <= maxY + 1.0E-7D
                && minZ <= maxZ + 1.0E-7D;
    }

    /**
     * Pose-fit obstacle materialization: every static block/registered
     * sphere/world-border obstacle plus hard dynamic entities present at the
     * operation start, and every captured pose-strict ordinary entity whose
     * immutable snapshot overlaps the candidate body. This is the only scene
     * consumer of the pose-strict snapshots; movement queries never see
     * vanilla-soft entities.
     */
    public List<CollisionObstacle> queryPoseFit(CollisionBody body) {
        Objects.requireNonNull(body, "body");
        List<CollisionObstacle> obstacles = query(
                body,
                new Vector3d(),
                new SweepTimeWindow(0.0D, 0.0D)
        );
        Aabb3d searchBox = body.enclosingAabb()
                .inflate(CollisionTolerances.CONTACT_SLOP)
                .inflate(CollisionTolerances.CONTACT_SKIN);
        for (EntityObstacle pose : this.poseStrictObstacles) {
            if (!pose.broadphaseBounds().intersects(searchBox)) {
                continue;
            }
            if (!this.tracker.recordObstacles(1)) {
                break;
            }
            obstacles.add(pose);
        }
        obstacles.sort(CollisionObstacle.STABLE_COMPARATOR);
        return obstacles;
    }

    /**
     * {@code MOVEMENT_OFFSET_FALLBACK} supporting-block query: the
     * vanilla-equivalent bottom-slice + movement-offset fallback resolved
     * against captured immutable block primitives only.
     *
     * <p>This is deliberately distinct from the {@code CURRENT_CONTACT}
     * query answered by the exact {@code GravityMoveResult} support witness.
     * Semantics mirror {@code Entity.checkSupportingBlock}: the bottom
     * world-Y slice of the body's proxy AABB is queried first; when no block
     * intersects it, the slice shifted by the world horizontal movement
     * offset is queried. No live {@code Level} read ever happens. When the
     * requested slice is outside the captured scene envelope, an explicit
     * {@link CollisionSceneCoverageException} is thrown and the caller fails
     * closed with no support witness.</p>
     */
    @Override
    public List<CollisionObstacle> query(
            CollisionBody body,
            Vector3dc movement,
            SweepTimeWindow window
    ) {
        return queryFiltered(
                body,
                movement,
                window,
                true
        );
    }

    @Override
    public List<CollisionObstacle> querySupport(
            CollisionBody body,
            Vector3dc movement,
            SweepTimeWindow window
    ) {
        /*
         * Geometry was already bounded at the capture boundary.
         *
         * This is only deterministic filtering of the immutable captured lists.
         * FeetSupportQuery owns a separate bounded supportWorkTracker for actual
         * support narrow-phase/exposure work.
         */
        return queryFiltered(
                body,
                movement,
                window,
                false
        );
    }

    private List<CollisionObstacle> queryFiltered(
            CollisionBody body,
            Vector3dc movement,
            SweepTimeWindow window,
            boolean chargeHardCollisionBudget
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(window, "window");

        if (chargeHardCollisionBudget) {
            this.tracker.recordSceneQuery();
        }

        Aabb3d searchBox =
                body.rawSweptAabb(movement)
                        .inflate(
                                CollisionTolerances.CONTACT_SLOP)
                        .inflate(
                                CollisionTolerances.CONTACT_SKIN);

        requireCovered(searchBox);

        double intervalTicks =
                this.time.intervalTicks();

        double normalizedStart =
                window.normalizedStartTicks(
                        intervalTicks);

        double normalizedDuration =
                window.normalizedDurationTicks(
                        intervalTicks);

        List<CollisionObstacle> obstacles =
                new ArrayList<>();

        for (SphereObstacle sphere
                : this.sphereObstacles) {

            if (!sphere.broadphaseBounds()
                    .intersects(searchBox)) {
                continue;
            }

            if (chargeHardCollisionBudget
                    && !this.tracker.recordObstacles(1)) {
                return finish(obstacles);
            }

            obstacles.add(sphere);
        }

        for (BlockObstacle primitive
                : this.blockObstacles) {

            if (!primitive.bounds()
                    .intersects(searchBox)) {
                continue;
            }

            obstacles.add(primitive);
        }

        for (DynamicCollisionObstacleSnapshot snapshot
                : this.dynamicObstacles) {

            if (!snapshot.sweptBounds(
                            normalizedStart,
                            Math.min(
                                    1.0D,
                                    normalizedStart
                                            + normalizedDuration))
                    .inflate(
                            CollisionTolerances.CONTACT_SKIN)
                    .intersects(searchBox)) {
                continue;
            }

            if (chargeHardCollisionBudget
                    && !this.tracker.recordObstacles(1)) {
                return finish(obstacles);
            }

            obstacles.add(
                    new EntityObstacle(snapshot)
            );
        }

        for (WorldBorderObstacle obstacle
                : this.worldBorder.intersect(searchBox)) {

            if (chargeHardCollisionBudget
                    && !this.tracker.recordObstacles(1)) {
                return finish(obstacles);
            }

            obstacles.add(obstacle);
        }

        return finish(obstacles);
    }

    private void requireCovered(Aabb3d requested) {
        Aabb3d captured = this.domain.staticBounds();
        if (requested.minX() < captured.minX() - 1.0E-7D
                || requested.minY() < captured.minY() - 1.0E-7D
                || requested.minZ() < captured.minZ() - 1.0E-7D
                || requested.maxX() > captured.maxX() + 1.0E-7D
                || requested.maxY() > captured.maxY() + 1.0E-7D
                || requested.maxZ() > captured.maxZ() + 1.0E-7D) {
            throw new CollisionSceneCoverageException(
                    "query swept region exceeds the captured scene domain: "
                            + "requested=" + requested
                            + " captured=" + captured
            );
        }
    }

    private static List<CollisionObstacle> finish(
            List<CollisionObstacle> obstacles
    ) {
        obstacles.sort(CollisionObstacle.STABLE_COMPARATOR);
        return obstacles;
    }

    /** Number of block positions materialized at capture time. */
    public int evaluatedBlockPositions() {
        return this.evaluatedBlockPositions;
    }

    @Override
    public long tick() {
        return this.tick;
    }

    @Override
    public long revision() {
        return this.revision;
    }

    @Override
    public KinematicStepContext time() {
        return this.time;
    }

    @Override
    public CollisionWorkDiagnostics diagnostics() {
        return this.tracker.snapshot();
    }

    /** The conservative envelope this scene was captured from. */
    public CollisionCaptureDomain domain() {
        return this.domain;
    }
}
