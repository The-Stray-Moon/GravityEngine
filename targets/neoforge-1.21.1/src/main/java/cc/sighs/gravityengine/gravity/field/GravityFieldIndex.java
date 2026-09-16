package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldKey;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Level-dimension-local spatial index of generic gravity fields.
 *
 * <p>Finite influence volumes are bucketed. Infinite or excessively large
 * finite volumes live in a global candidate set and are filtered by their
 * exact influence predicate at query time.</p>
 */
public final class GravityFieldIndex {
    static final int CELL_SIZE = 16;
    static final int MAX_BUCKETED_CELLS = 4096;

    private final ResourceKey<Level> dimension;

    private final Map<GravityFieldKey, GravityFieldInstance> instances =
            new HashMap<>();

    private final Map<Cell, Set<GravityFieldKey>> cellMembership =
            new HashMap<>();

    private final Map<GravityFieldKey, Set<Cell>> occupiedCells =
            new HashMap<>();

    private final Set<GravityFieldKey> globalFields =
            new HashSet<>();

    public GravityFieldIndex(ResourceKey<Level> dimension) {
        this.dimension = Objects.requireNonNull(
                dimension,
                "dimension"
        );
    }

    public synchronized boolean put(GravityFieldInstance instance) {
        Objects.requireNonNull(instance, "instance");

        if (!this.dimension.equals(instance.key().dimension())) {
            throw new IllegalArgumentException(
                    "field dimension "
                            + instance.key().dimension().location()
                            + " does not match index dimension "
                            + this.dimension.location()
            );
        }

        GravityFieldInstance current =
                this.instances.get(instance.key());

        if (current != null
                && instance.revision() <= current.revision()) {
            return false;
        }

        if (current != null) {
            removeMembership(current.key());
        }

        this.instances.put(instance.key(), instance);
        addMembership(instance);
        return true;
    }

    public synchronized boolean remove(GravityFieldKey key) {
        Objects.requireNonNull(key, "key");

        GravityFieldInstance removed =
                this.instances.remove(key);

        if (removed == null) {
            return false;
        }

        removeMembership(key);
        return true;
    }

    /**
     * Revision-aware removal.
     *
     * <p>The single field runtime accepts a later revision of the same key
     * while a previous registration request may still be unregistered. An
     * unconditional key removal would let that stale owner delete the newer
     * accepted instance. Removal therefore only applies when the currently
     * registered instance still belongs to the revision that requested it.</p>
     */
    public synchronized boolean remove(
            GravityFieldKey key,
            long expectedRevision
    ) {
        Objects.requireNonNull(key, "key");

        GravityFieldInstance current =
                this.instances.get(key);

        if (current == null
                || current.revision() != expectedRevision) {
            return false;
        }

        this.instances.remove(key);
        removeMembership(key);
        return true;
    }

    public synchronized Optional<GravityFieldInstance> get(
            GravityFieldKey key
    ) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(
                this.instances.get(key)
        );
    }

    public synchronized List<GravityFieldInstance> query(
            Vector3dc position
    ) {
        Objects.requireNonNull(position, "position");
        requireFinite(position, "position");

        Set<GravityFieldKey> candidates =
                new HashSet<>(this.globalFields);

        Set<GravityFieldKey> local =
                this.cellMembership.get(
                        Cell.from(position)
                );

        if (local != null) {
            candidates.addAll(local);
        }

        List<GravityFieldInstance> result =
                new ArrayList<>(candidates.size());

        for (GravityFieldKey key : candidates) {
            GravityFieldInstance instance =
                    this.instances.get(key);

            if (instance == null) {
                continue;
            }

            if (instance.influence().contains(position)) {
                result.add(instance);
            }
        }

        result.sort(
                GravityFieldInstance.ACCUMULATION_ORDER
        );

        return List.copyOf(result);
    }

    public synchronized List<GravityFieldInstance> allInstances() {
        List<GravityFieldInstance> result =
                new ArrayList<>(
                        this.instances.values()
                );

        result.sort(
                GravityFieldInstance.ACCUMULATION_ORDER
        );

        return List.copyOf(result);
    }

    public synchronized int size() {
        return this.instances.size();
    }

    public synchronized int globalFieldCount() {
        return this.globalFields.size();
    }

    public synchronized int bucketedFieldCount() {
        return this.occupiedCells.size();
    }

    public synchronized void clear() {
        this.instances.clear();
        this.cellMembership.clear();
        this.occupiedCells.clear();
        this.globalFields.clear();
    }

    private void addMembership(
            GravityFieldInstance instance
    ) {
        Optional<Aabb3d> bounds =
                instance.influence().finiteBounds();

        if (bounds.isEmpty()) {
            this.globalFields.add(instance.key());
            return;
        }

        CellRange range =
                CellRange.forBounds(bounds.get());

        if (range.cellCountExceeds(
                MAX_BUCKETED_CELLS
        )) {
            this.globalFields.add(instance.key());
            return;
        }

        Set<Cell> membership =
                new HashSet<>((int) range.cellCount());

        for (int x = range.minX();
             x <= range.maxX();
             x++) {

            for (int y = range.minY();
                 y <= range.maxY();
                 y++) {

                for (int z = range.minZ();
                     z <= range.maxZ();
                     z++) {

                    Cell cell =
                            new Cell(x, y, z);

                    membership.add(cell);

                    this.cellMembership
                            .computeIfAbsent(
                                    cell,
                                    ignored -> new HashSet<>()
                            )
                            .add(instance.key());
                }
            }
        }

        this.occupiedCells.put(
                instance.key(),
                Set.copyOf(membership)
        );
    }

    private void removeMembership(
            GravityFieldKey key
    ) {
        this.globalFields.remove(key);

        Set<Cell> membership =
                this.occupiedCells.remove(key);

        if (membership == null) {
            return;
        }

        for (Cell cell : membership) {
            Set<GravityFieldKey> keys =
                    this.cellMembership.get(cell);

            if (keys == null) {
                continue;
            }

            keys.remove(key);

            if (keys.isEmpty()) {
                this.cellMembership.remove(cell);
            }
        }
    }

    private static void requireFinite(
            Vector3dc vector,
            String name
    ) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: "
                            + vector
            );
        }
    }

    private record Cell(
            int x,
            int y,
            int z
    ) {
        static Cell from(Vector3dc point) {
            return new Cell(
                    cellCoordinate(point.x()),
                    cellCoordinate(point.y()),
                    cellCoordinate(point.z())
            );
        }

        private static int cellCoordinate(
                double value
        ) {
            return (int) Math.floor(
                    value / CELL_SIZE
            );
        }
    }

    private record CellRange(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        static CellRange forBounds(
                Aabb3d bounds
        ) {
            return new CellRange(
                    Cell.cellCoordinate(bounds.minX()),
                    Cell.cellCoordinate(bounds.minY()),
                    Cell.cellCoordinate(bounds.minZ()),
                    Cell.cellCoordinate(bounds.maxX()),
                    Cell.cellCoordinate(bounds.maxY()),
                    Cell.cellCoordinate(bounds.maxZ())
            );
        }

        long cellCount() {
            return axisCount(minX, maxX)
                    * axisCount(minY, maxY)
                    * axisCount(minZ, maxZ);
        }

        boolean cellCountExceeds(long limit) {
            long x = axisCount(minX, maxX);
            long y = axisCount(minY, maxY);
            long z = axisCount(minZ, maxZ);

            return x > limit
                    || y > limit
                    || z > limit
                    || x * y > limit
                    || x * y * z > limit;
        }

        private static long axisCount(
                int min,
                int max
        ) {
            return (long) max - min + 1L;
        }
    }
}
