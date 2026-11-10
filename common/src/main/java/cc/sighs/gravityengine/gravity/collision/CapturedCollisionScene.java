package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

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
    private final Map<CellPos, BlockMovementMaterialSnapshot>
            movementMaterials;
    private final int evaluatedBlockPositions;
    private final List<Aabb3d> unavailableBlocks;
    private Map<CellPos, BlockObstacle> firstBlockByCell;
    private Map<CellPos, List<BlockObstacle>> multipleBlocksByCell;
    private final Map<RigidObstacleIdentity, DynamicCollisionObstacleSnapshot> rigidByIdentity;
    private CapturedBlockIndex blockIndex;
    private long blockCandidateVisits;
    /** Aggregate operation-local broadphase work, with no trace allocation. */
    public long blockCandidateVisits() { return blockCandidateVisits; }

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
            Map<CellPos, BlockMovementMaterialSnapshot> movementMaterials,
            int evaluatedBlockPositions
    ) {
        this(domain, tick, revision, time, tracker, worldBorder, blockObstacles, dynamicObstacles,
                sphereObstacles, poseStrictObstacles, movementMaterials, evaluatedBlockPositions, List.of());
    }

    /** Unavailable block domains are raw missing data, including the capture halo.
     * Query dependencies include one cell of shape overhang and one of neighbor access. */
    public CapturedCollisionScene(
            CollisionCaptureDomain domain, long tick, long revision, KinematicStepContext time,
            CollisionWorkTracker tracker, WorldBorderCollisionSnapshot worldBorder,
            List<BlockObstacle> blockObstacles, List<DynamicCollisionObstacleSnapshot> dynamicObstacles,
            List<SphereObstacle> sphereObstacles, List<EntityObstacle> poseStrictObstacles,
            Map<CellPos, BlockMovementMaterialSnapshot> movementMaterials, int evaluatedBlockPositions,
            List<Aabb3d> unavailableBlocks) {
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
        this.unavailableBlocks = List.copyOf(unavailableBlocks);
        Map<RigidObstacleIdentity, DynamicCollisionObstacleSnapshot> identities = new HashMap<>();
        for (var obstacle : this.dynamicObstacles) identities.put(RigidObstacleIdentity.of(obstacle), obstacle);
        this.rigidByIdentity = Map.copyOf(identities);

    }

    /** Derived operation-local lookup, built only for consumers which need cells.
     * It adds no authority: all values come from the immutable captured list. */
    private void indexBlockCells() {
        if (firstBlockByCell != null) return;
        // Most cells have one primitive; avoid a list/builder/copy for every solid cell.
        Map<CellPos, BlockObstacle> first = new HashMap<>();
        Map<CellPos, List<BlockObstacle>> multiple = new HashMap<>();
        for (var block : this.blockObstacles) {
            var previous=first.putIfAbsent(block.blockPos(),block);
            if (previous != null) {
                var values=multiple.computeIfAbsent(block.blockPos(), ignored -> new ArrayList<>(List.of(previous)));
                values.add(block);
            }
        }
        multiple.replaceAll((cell, values) -> {
            values.sort(CollisionObstacle.STABLE_COMPARATOR);
            first.put(cell, values.get(0));
            return List.copyOf(values);
        });
        // Private derived maps are never exposed or mutated after construction.
        this.firstBlockByCell = first;
        this.multipleBlocksByCell = multiple;
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
            CellPos position
    ) {
        Objects.requireNonNull(position, "position");
        if (!domainContainsBlockCell(position)) {
            throw new CollisionSceneCoverageException(
                    "movement material lookup outside the captured scene "
                            + "envelope: "
                            + position
                            + " captured=" + this.domain.staticBounds());
        }
        if (!unavailableBlocks.isEmpty()) requireBlockData(new Aabb3d(position.x(), position.y(), position.z(),
                position.x()+1.0, position.y()+1.0, position.z()+1.0), 1.0);
        return Optional.ofNullable(this.movementMaterials.get(position));
    }

    private boolean domainContainsBlockCell(CellPos position) {
        Aabb3d bounds = this.domain.staticBounds();
        double minX = Math.max(bounds.minX(), position.x());
        double minY = Math.max(bounds.minY(), position.y());
        double minZ = Math.max(bounds.minZ(), position.z());
        double maxX = Math.min(bounds.maxX(), position.x() + 1.0D);
        double maxY = Math.min(bounds.maxY(), position.y() + 1.0D);
        double maxZ = Math.min(bounds.maxZ(), position.z() + 1.0D);
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
    @Override
    public List<CollisionObstacle> queryPoseFit(CollisionBody body) {
        return queryPoseFit(body, 0.0D);
    }

    @Override
    public List<CollisionObstacle> queryPoseFit(CollisionBody body, double timeTicks) {
        Objects.requireNonNull(body, "body");
        if (!Double.isFinite(timeTicks) || timeTicks < 0.0D
                || timeTicks > time().intervalTicks()) {
            throw new IllegalArgumentException("pose-fit time outside operation");
        }
        List<CollisionObstacle> obstacles = query(
                body, Vec3d.ZERO, new SweepTimeWindow(timeTicks, 0.0D));
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
            Vec3d movement,
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
            Vec3d movement,
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
            Vec3d movement,
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

        // Short-lived scenes pay no tree-build cost. Repeated scans themselves
        // supply the work evidence for amortizing an operation-local index.
        if (blockIndex == null && blockObstacles.size() >= 64
                && blockCandidateVisits >= 4L * blockObstacles.size())
            blockIndex = new CapturedBlockIndex(blockObstacles);
        if (blockIndex == null) {
            for (var block : blockObstacles) if (block.bounds().intersects(searchBox)) obstacles.add(block);
            blockCandidateVisits += blockObstacles.size();
        } else blockCandidateVisits += blockIndex.query(searchBox, obstacles);

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
        requireBlockData(requested, 2.0);
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

    private void requireBlockData(Aabb3d requested, double dependencyMargin) {
        if (unavailableBlocks.isEmpty()) return;
        Aabb3d dependencies = requested.inflate(dependencyMargin);
        for (var missing : unavailableBlocks) {
            if (dependencies.intersects(missing)) throw new CollisionSceneCoverageException(
                    CollisionSceneCoverageException.Reason.UNAVAILABLE_BLOCK_DATA,
                    "query depends on unavailable block data: requested=" + requested + " missing=" + missing);
        }
    }

    private void requireCell(CellPos position) {
        if (!domainContainsBlockCell(position)) throw new CollisionSceneCoverageException("block lookup outside capture: " + position);
        if (!unavailableBlocks.isEmpty()) requireBlockData(new Aabb3d(position.x(), position.y(), position.z(),
                position.x()+1.0, position.y()+1.0, position.z()+1.0), 1.0);
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

    /**
     * Immutable captured rigid-obstacle publication for this operation.
     *
     * <p>This is the scene-snapshot evidence persistent support revalidates
     * against. The returned list is already {@code List.copyOf}; it can never
     * observe a later world read.</p>
     */
    @Override
    public List<DynamicCollisionObstacleSnapshot> dynamicObstacles() {
        return this.dynamicObstacles;
    }

    /** Exact qualified obstacle identity, including physical continuity. */
    public Optional<DynamicCollisionObstacleSnapshot> dynamicObstacle(
            RigidObstacleIdentity identity
    ) {
        Objects.requireNonNull(identity, "identity");
        return Optional.ofNullable(rigidByIdentity.get(identity));
    }

    /** Exact captured static block primitive; missing coverage is not absence. */
    public Optional<BlockObstacle> blockObstacleAt(CellPos position) {
        Objects.requireNonNull(position, "position");
        requireCell(position);
        indexBlockCells();
        return Optional.ofNullable(firstBlockByCell.get(position));
    }

    /** All primitives at one cell in stable order, with no whole-scene scan. */
    public List<BlockObstacle> blockObstaclesAt(CellPos position) {
        Objects.requireNonNull(position, "position");
        requireCell(position);
        indexBlockCells();
        var multiple=multipleBlocksByCell.get(position);
        if (multiple != null) return multiple;
        var first=firstBlockByCell.get(position);
        return first == null ? List.of() : List.of(first);
    }

    public Optional<BlockObstacle> blockObstacle(CellPos position, Aabb3d voxelPiece) {
        Objects.requireNonNull(voxelPiece, "voxelPiece");
        return blockObstaclesAt(position).stream().filter(block -> block.bounds().equals(voxelPiece)).findFirst();
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
