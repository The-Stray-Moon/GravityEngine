package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.collision.CollisionObstacle;
import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.collision.SphereObstacle;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Level-instance-local registry of independently registered collision
 * obstacles.
 *
 * <p>This registry is completely independent from gravity-field registries:
 * collision discovery must never traverse {@code GravityFieldRuntime} and
 * field discovery must never query this registry.  An owning block/entity
 * lifecycle performs two separate registrations when it is both a field
 * producer and a physical collider.</p>
 *
 * <p>Entries are immutable obstacle descriptors plus their owning block
 * position as opaque provenance.  The registry never retains block entities
 * and never derives geometry from a gravity source.</p>
 */
public final class CollisionObstacleRegistry {
    private static final Map<Level, CollisionObstacleRegistry> INSTANCES =
            new IdentityHashMap<>();

    private final Level level;
    private final Map<BlockPos, SphereObstacle> obstacles =
            new HashMap<>();

    private CollisionObstacleRegistry(Level level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public static CollisionObstacleRegistry get(Level level) {
        Objects.requireNonNull(level, "level");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(
                    level, CollisionObstacleRegistry::new);
        }
    }

    /** Removes the level registry without constructing one during unload. */
    public static void remove(Level level) {
        Objects.requireNonNull(level, "level");
        CollisionObstacleRegistry removed;
        synchronized (INSTANCES) {
            removed = INSTANCES.remove(level);
        }
        if (removed != null) {
            removed.clear();
        }
    }

    /**
     * Registers or replaces the immutable obstacle descriptor owned by one
     * block position.  The descriptor must already be a complete immutable
     * snapshot; no block entity or source state is consulted here.
     */
    public void put(BlockPos position, SphereObstacle obstacle) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(obstacle, "obstacle");
        synchronized (this) {
            this.obstacles.put(position.immutable(), obstacle);
        }
    }

    /** Withdraws one position's obstacle registration. */
    public boolean remove(BlockPos position) {
        Objects.requireNonNull(position, "position");
        synchronized (this) {
            return this.obstacles.remove(position.immutable()) != null;
        }
    }

    /**
     * Obstacles intersecting the search box plus the block positions whose
     * voxel collider is replaced by a registered sphere this operation.
     */
    public RegisteredSphereQuery query(AABB searchBox) {
        Objects.requireNonNull(searchBox, "searchBox");
        Aabb3d neutral =
                MinecraftGeometryAdapter.toAabb3d(searchBox);
        List<RegisteredSphere> found = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<BlockPos, SphereObstacle> entry
                    : this.obstacles.entrySet()) {
                SphereObstacle obstacle = entry.getValue();
                if (!obstacle.broadphaseBounds()
                        .intersects(neutral)) {
                    continue;
                }
                BlockPos key = entry.getKey();
                found.add(new RegisteredSphere(key, obstacle));
            }
        }
        /*
         * Deterministic total order: obstacle geometry first (type/center/
         * radius), then the stable registration key. HashMap iteration order
         * never reaches the caller; equal geometric descriptors are broken by
         * their immutable registration position.
         */
        found.sort((left, right) -> {
            int geometric = CollisionObstacle.STABLE_COMPARATOR.compare(
                    left.obstacle(), right.obstacle());
            if (geometric != 0) {
                return geometric;
            }
            return CollisionObstacle.compareBlockPos(
                    left.position(), right.position());
        });
        List<SphereObstacle> ordered = new ArrayList<>(found.size());
        List<BlockPos> orderedPositions = new ArrayList<>(found.size());
        for (RegisteredSphere entry : found) {
            ordered.add(entry.obstacle());
            orderedPositions.add(entry.position());
        }
        return new RegisteredSphereQuery(
                List.copyOf(ordered),
                List.copyOf(orderedPositions));
    }

    public int size() {
        synchronized (this) {
            return this.obstacles.size();
        }
    }

    private void clear() {
        synchronized (this) {
            this.obstacles.clear();
        }
    }

    private record RegisteredSphere(
            BlockPos position,
            SphereObstacle obstacle
    ) {}

    public record RegisteredSphereQuery(
            List<SphereObstacle> obstacles,
            List<BlockPos> registeredBlockPositions
    ) {
        public RegisteredSphereQuery {
            Objects.requireNonNull(obstacles, "obstacles");
            Objects.requireNonNull(
                    registeredBlockPositions,
                    "registeredBlockPositions");
            obstacles = List.copyOf(obstacles);
            registeredBlockPositions =
                    List.copyOf(registeredBlockPositions);
            if (obstacles.size() != registeredBlockPositions.size()) {
                throw new IllegalArgumentException(
                        "obstacle and position lists must be parallel");
            }
        }
    }
}
