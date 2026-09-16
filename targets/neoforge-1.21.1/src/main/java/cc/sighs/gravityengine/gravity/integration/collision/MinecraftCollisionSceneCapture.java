package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Live Minecraft capture boundary for one outer collision operation.
 *
 * <p>This is the only place that reads {@code Level}, {@code BlockState},
 * {@code VoxelShape}, the obstacle registry or hard-entity geometry for a
 * collision scene. It materializes one complete {@link CapturedCollisionScene}
 * and discards every Minecraft reading handle before the solver starts.</p>
 */
public final class MinecraftCollisionSceneCapture {
    private MinecraftCollisionSceneCapture() {}

    /**
     * Captures every obstacle in {@code domain} exactly once. No query
     * performed on the returned scene re-enters the Minecraft world.
     */
    public static CapturedCollisionScene capture(
            Entity collisionOwner,
            CollisionCaptureDomain domain,
            long tick,
            long revision,
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(tracker, "tracker");

        Level level = Objects.requireNonNull(
                collisionOwner.level(), "collisionOwner level");
        CollisionContext context = CollisionContext.of(collisionOwner);

        // World border and registry are snapshotted once, before block and
        // entity capture, so the whole scene observes one consistent instant.
        WorldBorderCollisionSnapshot worldBorder =
                captureWorldBorder(level);
        tracker.recordWorldBorderSnapshot();

        CollisionObstacleRegistry.RegisteredSphereQuery spheres =
                CollisionObstacleRegistry.get(level).query(
                        MinecraftGeometryAdapter.toMinecraft(
                                domain.staticBounds()));
        tracker.recordSourceSphereSnapshot();
        List<SphereObstacle> sphereObstacles =
                new ArrayList<>(spheres.obstacles());
        Set<BlockPos> replacedBlockPositions =
                new HashSet<>(spheres.registeredBlockPositions());

        List<BlockObstacle> blockObstacles = new ArrayList<>();
        Map<BlockPos, BlockMovementMaterialSnapshot> movementMaterials =
                new HashMap<>();
        int evaluatedBlockPositions = captureBlocks(
                level,
                context,
                domain.staticBounds(),
                tracker,
                blockObstacles,
                replacedBlockPositions,
                movementMaterials,
                collisionOwner
        );

        List<DynamicCollisionObstacleSnapshot> dynamicObstacles =
                captureDynamicObstacles(
                        level,
                        collisionOwner,
                        domain.dynamicEntityBounds(),
                        time,
                        tracker
                );
        /*
         * Pose-fit legality (Player.canPlayerFitWithinBlocksAndEntitiesWhen)
         * treats ordinary entities as strict blockers, unlike the
         * vanilla-soft movement solve. Those ordinary entities are captured
         * here as immutable zero-motion pose-strict snapshots so the later
         * pose query never re-reads the live entity list or live bounding
         * boxes.
         */
        List<EntityObstacle> poseStrictObstacles =
                capturePoseStrictEntities(
                        level,
                        collisionOwner,
                        domain.staticBounds(),
                        tracker
                );

        return new CapturedCollisionScene(
                domain,
                tick,
                revision,
                time,
                tracker,
                worldBorder,
                blockObstacles,
                dynamicObstacles,
                sphereObstacles,
                poseStrictObstacles,
                movementMaterials,
                evaluatedBlockPositions
        );
    }

    /**
     * Capture boundary for a geometry transition outside a gravity operation.
     * The returned scene is strong and immutable; the transition may then use
     * only {@link CollisionScene#query} for legality/recovery.
     */
    public static CapturedCollisionScene captureAround(
            Entity collisionOwner,
            CollisionBody body,
            double maxStepHeight
    ) {
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(body, "body");
        CollisionCaptureDomain domain = CollisionCaptureDomain.forTransition(body, maxStepHeight);
        long tick = collisionOwner.level().getGameTime();
        long revision = 0L;
        KinematicStepContext time = KinematicStepContext.fullTick(
                tick, revision);
        CollisionWorkTracker tracker = new CollisionWorkTracker(
                CollisionWorkBudget.defaults());
        return capture(
                collisionOwner,
                domain,
                tick,
                revision,
                time,
                tracker);
    }

    private static int captureBlocks(
            Level level,
            CollisionContext context,
            Aabb3d searchBox,
            CollisionWorkTracker tracker,
            List<BlockObstacle> blockObstacles,
            Set<BlockPos> replacedBlockPositions,
            Map<BlockPos, BlockMovementMaterialSnapshot> movementMaterials,
            Entity collisionOwner
    ) {
        BlockPos minimum = BlockPos.containing(
                searchBox.minX() - 1.0D,
                searchBox.minY() - 1.0D,
                searchBox.minZ() - 1.0D
        );
        BlockPos maximum = BlockPos.containing(
                searchBox.maxX() + 1.0D,
                searchBox.maxY() + 1.0D,
                searchBox.maxZ() + 1.0D
        );
        CollisionWorldQuery.requireBoundedBlockVolume(minimum, maximum);
        int evaluated = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minimumChunkX = SectionPos.blockToSectionCoord(minimum.getX());
        int maximumChunkX = SectionPos.blockToSectionCoord(maximum.getX());
        int minimumChunkZ = SectionPos.blockToSectionCoord(minimum.getZ());
        int maximumChunkZ = SectionPos.blockToSectionCoord(maximum.getZ());

        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            int firstX = Math.max(
                    minimum.getX(),
                    SectionPos.sectionToBlockCoord(chunkX));
            int lastX = Math.min(
                    maximum.getX(),
                    SectionPos.sectionToBlockCoord(chunkX, 15));
            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ; chunkZ++) {
                int firstZ = Math.max(
                        minimum.getZ(),
                        SectionPos.sectionToBlockCoord(chunkZ));
                int lastZ = Math.min(
                        maximum.getZ(),
                        SectionPos.sectionToBlockCoord(chunkZ, 15));
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                for (int x = firstX; x <= lastX; x++) {
                    for (int z = firstZ; z <= lastZ; z++) {
                        for (int y = minimum.getY();
                             y <= maximum.getY(); y++) {
                            if (!tracker.recordBlockPosition()) {
                                throw new CollisionComplexityLimitException(
                                        "scene capture block budget exceeded: "
                                                + tracker.limitReason());
                            }
                            cursor.set(x, y, z);
                            BlockPos position = cursor.immutable();
                            evaluated++;
                            if (replacedBlockPositions.contains(position)) {
                                // The registering sphere replaces the voxel.
                                continue;
                            }
                            tracker.recordBlockShapeEvaluated();
                            BlockState state = level.getBlockState(position);
                            if (state.isAir()) {
                                continue;
                            }
                            // Immutable material evidence captured once with
                            // the scene; movement policy later reads only this
                            // snapshot and never re-queries the live
                            // BlockState/Block.
                            movementMaterials.put(
                                    position,
                                    new BlockMovementMaterialSnapshot(
                                            state.getFriction(
                                                    level,
                                                    position,
                                                    collisionOwner),
                                            state.getBlock()
                                                    .getSpeedFactor(),
                                            state.is(Blocks.WATER),
                                            state.is(Blocks.BUBBLE_COLUMN)));
                            VoxelShape shape = state.getCollisionShape(
                                    level, position, context);
                            List<BlockObstacle> built = new ArrayList<>();
                            CollisionWorldQuery.enumerateShapePrimitives(
                                    shape,
                                    tracker,
                                    true,
                                    position,
                                    built);
                            if (tracker.limitExceeded()) {
                                throw new CollisionComplexityLimitException(
                                        "scene capture primitive budget "
                                                + "exceeded: "
                                                + tracker.limitReason());
                            }
                            tracker.recordBlockPrimitives(built.size());
                            blockObstacles.addAll(built);
                        }
                    }
                }
            }
        }
        return evaluated;
    }

    private static List<DynamicCollisionObstacleSnapshot>
    captureDynamicObstacles(
            Level level,
            Entity collisionOwner,
            Aabb3d queryBounds,
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        var predicate = EntitySelector.NO_SPECTATORS.and(
                collisionOwner::canCollideWith);
        List<Entity> candidates = level.getEntities(
                collisionOwner,
                MinecraftGeometryAdapter.toMinecraft(queryBounds),
                predicate.and(candidate ->
                        isHardObstacle(candidate))
        );
        List<DynamicCollisionObstacleSnapshot> captured = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Entity other : candidates) {
            if (other == collisionOwner || other.isRemoved() || !seen.add(other.getId())) continue;
            // Exactly one completed handoff read. No provider reaches the returned scene.
            var publication = ((CollisionSurfaceMotionProvider) other)
                    .gravityengine$collisionSurfaceMotionSnapshot();
            if (publication == null) throw new CollisionSceneCoverageException("missing rigid publication");
            Aabb3d discovery = MinecraftGeometryAdapter.toAabb3d(other.getBoundingBox());
            DynamicEntityBroadphasePolicy.validatePublication(publication, discovery, time);
            captured.addAll(publication.primitives());
            tracker.recordDynamicSurfaceSnapshot();
        }
        return captured;
    }

    /**
     * Captures ordinary (vanilla-soft) collidable entities as immutable
     * pose-strict snapshots. They never join the movement obstacle set;
     * {@link CapturedCollisionScene#queryPoseFit} is the only consumer.
     */
    private static List<EntityObstacle> capturePoseStrictEntities(
            Level level,
            Entity collisionOwner,
            Aabb3d queryBounds,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(queryBounds, "queryBounds");
        Objects.requireNonNull(tracker, "tracker");
        var predicate = EntitySelector.NO_SPECTATORS.and(
                collisionOwner::canCollideWith).and(candidate ->
                !isHardObstacle(candidate));
        List<Entity> candidates = level.getEntities(
                collisionOwner,
                MinecraftGeometryAdapter.toMinecraft(queryBounds),
                predicate
        );
        List<EntityObstacle> captured = new ArrayList<>();
        for (Entity other : candidates) {
            if (other == collisionOwner || other.isRemoved()) {
                continue;
            }
            Aabb3d bounds = MinecraftGeometryAdapter.toAabb3d(
                    other.getBoundingBox());
            CollisionBody exactBody = null;
            if (GravityInfluencePolicy.usesCustomBody(other)) {
                GravityFrame otherFrame =
                        GravityFrameAccess.authoritativeFrame(other);
                exactBody = GravityEntityGeometry.exactBody(
                        other, otherFrame);
            }
            tracker.recordDynamicSurfaceSnapshot();
            captured.add(new EntityObstacle(
                    other.getId(),
                    bounds,
                    exactBody
            ));
        }
        return captured;
    }
    /**
     * Captures the level's current finite border planes exactly once.
     *
     * <p>Do not use {@link WorldBorder#getCollisionShape()} here: Vanilla's
     * shape intentionally represents the exterior with infinite slabs.</p>
     */
    private static WorldBorderCollisionSnapshot captureWorldBorder(Level level) {
        Objects.requireNonNull(level, "level");

        WorldBorder border = level.getWorldBorder();

        return new WorldBorderCollisionSnapshot(
                border.getMinX(),
                border.getMinZ(),
                border.getMaxX(),
                border.getMaxZ()
        );
    }

    /** Explicit opt-in boundary between vanilla-soft entities and hard structures. */
    private static boolean isHardObstacle(Entity entity) {
        return entity != null && isHardObstacleType(entity.getClass());
    }

    static boolean isHardObstacleType(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        return CollisionSurfaceMotionProvider.class.isAssignableFrom(entityType);
    }

}
