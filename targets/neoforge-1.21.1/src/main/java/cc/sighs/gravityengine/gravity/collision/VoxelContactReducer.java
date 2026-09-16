package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

import java.util.*;

/**
 * Deterministic cotemporal plane reduction for decomposed static voxels.
 *
 * <p>Both passes are local and hard-bounded:</p>
 *
 * <ul>
 *   <li>Occlusion uses a {@link PrimitiveSpatialIndex} keyed by the cells the
 *   actual primitive AABBs occupy (never the owner {@code BlockPos} alone), so
 *   oversized/modded shapes that extend beyond their owner cell are found by
 *   the sample point's cells. Cell classification uses overflow-safe capped
 *   arithmetic and ULP-safe exclusive upper bounds.</li>
 *   <li>Plane merging uses a quantized normal bucket index: a candidate only
 *   examines its own normal cell plus the adjacent cells, then re-verifies
 *   with the exact {@code VOXEL_SEAM_NORMAL_DOT} cone.</li>
 * </ul>
 *
 * <p>All comparison/index work consumes a checked work budget (the
 * operation-local {@link CollisionWorkTracker} in production, or a reducer
 * local hard budget for one-shot/test callers) and fails as a typed
 * {@code COMPLEXITY_LIMIT}. {@code HashMap} indexes are lookup-only; iteration
 * order never influences the output.</p>
 */
public final class VoxelContactReducer {
    /**
     * Normal-bucket cell size. Two unit normals within the
     * {@code VOXEL_SEAM_NORMAL_DOT} cone (cos theta = 1 - 1e-7) differ by at
     * most sin(theta) ~ 4.5e-4 in every component, so a cell size of 1e-3
     * guarantees equivalent normals land in the same or an adjacent cell.
     */
    private static final double PLANE_BUCKET_CELL_SIZE = 1.0E-3D;
    private static final int PLANE_BUCKET_RADIUS = 1;
    /**
     * A primitive that strictly contains the outward sample provably occupies
     * the sample's own fine/coarse cell (strict containment is deeper than any
     * cell-boundary ULP), so a radius-0 spatial query is exact.
     */
    private static final int SPATIAL_QUERY_RADIUS = 0;

    /** One-block fine cells for ordinary primitives. */
    private static final double FINE_CELL_SIZE = 1.0D;
    /** Four-block coarse cells for oversized primitives. */
    private static final double COARSE_CELL_SIZE = 4.0D;
    /** A primitive occupying more fine cells goes to the coarse tier. */
    private static final long MAX_FINE_CELLS_PER_PRIMITIVE = 27L;
    /** A primitive occupying more coarse cells goes to the oversized list. */
    private static final long MAX_COARSE_CELLS_PER_PRIMITIVE = 27L;
    /** Hard cap on gigantic primitives; exceeding it is a recoverable limit. */
    static final int MAX_GLOBAL_OVERSIZED = 64;

    /** Reducer-local hard bounds used when no operation tracker is supplied. */
    static final int LOCAL_MAX_COMPARISON_CHECKS = 1_000_000;
    static final int LOCAL_MAX_CELL_REGISTRATIONS = 262_144;

    private VoxelContactReducer() {}

    public static List<CollisionContact> reduce(List<CollisionContact> contacts) {
        return reduce(contacts, VoxelContactReducer::compareStable);
    }

    /** Operation-local entry using the reducer's stable default ordering. */
    public static List<CollisionContact> reduce(
            List<CollisionContact> contacts,
            CollisionWorkTracker tracker
    ) {
        return reduce(contacts, VoxelContactReducer::compareStable, tracker);
    }

    public static List<CollisionContact> reduce(
            List<CollisionContact> contacts,
            Comparator<CollisionContact> stableOrder
    ) {
        return reduceWithWorkCount(contacts, stableOrder).contacts();
    }

    /** Operation-local entry: reducer work consumes the shared tracker budget. */
    public static List<CollisionContact> reduce(
            List<CollisionContact> contacts,
            Comparator<CollisionContact> stableOrder,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(tracker, "tracker");
        return reduceWithWorkCount(
                contacts, stableOrder, new TrackerWorkBudget(tracker)
        ).contacts();
    }

    /**
     * Reduction that also reports the total bounded comparison work, so tests
     * can prove neither the occlusion nor the plane-merge pass is a global
     * pairwise scan.
     */
    static ReduceResult reduceWithWorkCount(
            List<CollisionContact> contacts,
            Comparator<CollisionContact> stableOrder
    ) {
        return reduceWithWorkCount(
                contacts, stableOrder, new LocalWorkBudget()
        );
    }

    static ReduceResult reduceWithWorkCount(
            List<CollisionContact> contacts,
            Comparator<CollisionContact> stableOrder,
            WorkBudgetSink budget
    ) {
        List<CollisionContact> ordered = contacts.stream()
                .sorted(stableOrder)
                .toList();
        PrimitiveSpatialIndex index = new PrimitiveSpatialIndex(ordered, budget);
        List<CollisionContact> visible = new ArrayList<>();
        for (CollisionContact candidate : ordered) {
            if (!isOccludedStaticFace(candidate, index)) visible.add(candidate);
        }

        Map<NormalBucket, List<Integer>> planeBuckets = new HashMap<>();
        List<CollisionContact> reduced = new ArrayList<>();
        List<Aabb3d> reducedRegions = new ArrayList<>();
        int planeBucketChecks = 0;
        for (CollisionContact candidate : visible) {
            int[] checks = {0};
            int parallelIndex = parallelPlaneIndex(
                    candidate, reduced, reducedRegions,
                    planeBuckets, checks, budget
            );
            planeBucketChecks += checks[0];
            if (parallelIndex < 0) {
                planeBuckets.computeIfAbsent(
                        normalBucket(candidate.normal()),
                        ignored -> new ArrayList<>()
                ).add(reduced.size());
                reduced.add(candidate);
                reducedRegions.add(candidate.obstacle().broadphaseBounds());
                continue;
            }
            CollisionContact existing = reduced.get(parallelIndex);
            reducedRegions.set(
                    parallelIndex,
                    union(
                            reducedRegions.get(parallelIndex),
                            candidate.obstacle().broadphaseBounds()
                    )
            );
            if (prefer(candidate, existing, stableOrder)) {
                // The family's bucket entry follows the current holder so a
                // candidate within the exact cone can always find it in its
                // own or an adjacent normal cell.
                moveBucketEntry(planeBuckets, existing.normal(), candidate.normal(), parallelIndex);
                reduced.set(parallelIndex, candidate);
            }
        }
        reduced.sort(stableOrder);
        return new ReduceResult(
                List.copyOf(reduced),
                new ReduceWork(
                        index.spatialCandidateChecks(),
                        planeBucketChecks,
                        ordered.size(),
                        index.cellRegistrations(),
                        index.finePrimitives(),
                        index.coarsePrimitives(),
                        index.oversizedPrimitives()
                )
        );
    }

    private static int parallelPlaneIndex(
            CollisionContact candidate,
            List<CollisionContact> reduced,
            List<Aabb3d> reducedRegions,
            Map<NormalBucket, List<Integer>> planeBuckets,
            int[] checks,
            WorkBudgetSink budget
    ) {
        NormalBucket base = normalBucket(candidate.normal());
        int best = -1;
        for (int dx = -PLANE_BUCKET_RADIUS; dx <= PLANE_BUCKET_RADIUS; dx++) {
            for (int dy = -PLANE_BUCKET_RADIUS; dy <= PLANE_BUCKET_RADIUS; dy++) {
                for (int dz = -PLANE_BUCKET_RADIUS; dz <= PLANE_BUCKET_RADIUS; dz++) {
                    List<Integer> indices = planeBuckets.get(new NormalBucket(
                            base.x + dx, base.y + dy, base.z + dz
                    ));
                    if (indices == null) continue;
                    for (int index : indices) {
                        checks[0]++;
                        if (!budget.recordChecks(1)) {
                            throw workLimit();
                        }
                        if (index < 0 || index >= reduced.size()) continue;
                        CollisionContact existing = reduced.get(index);
                        // Quantized buckets are broadphase only. A near-equal
                        // normal does not prove that two contacts represent
                        // the same physical/coplanar voxel constraint.
                        if (equivalentCotemporalConstraint(
                                existing, candidate, reducedRegions.get(index)
                        )) {
                            if (best < 0 || index < best) best = index;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static CollisionComplexityLimitException workLimit() {
        return new CollisionComplexityLimitException(
                "voxel reducer work budget exceeded"
        );
    }

    private static void moveBucketEntry(
            Map<NormalBucket, List<Integer>> planeBuckets,
            Vector3d oldNormal,
            Vector3d newNormal,
            int index
    ) {
        NormalBucket oldBucket = normalBucket(oldNormal);
        List<Integer> oldList = planeBuckets.get(oldBucket);
        if (oldList != null) {
            oldList.remove((Integer) index);
            if (oldList.isEmpty()) planeBuckets.remove(oldBucket);
        }
        planeBuckets.computeIfAbsent(
                normalBucket(newNormal), ignored -> new ArrayList<>()
        ).add(index);
    }

    private static NormalBucket normalBucket(Vector3d normal) {
        return new NormalBucket(
                (int) Math.floor((normal.x + 1.0D) / PLANE_BUCKET_CELL_SIZE),
                (int) Math.floor((normal.y + 1.0D) / PLANE_BUCKET_CELL_SIZE),
                (int) Math.floor((normal.z + 1.0D) / PLANE_BUCKET_CELL_SIZE)
        );
    }

    private static boolean prefer(
            CollisionContact candidate,
            CollisionContact existing,
            Comparator<CollisionContact> stableOrder
    ) {
        double candidateMinimum = candidate.surfaceVelocity().dot(candidate.normal());
        double existingMinimum = existing.surfaceVelocity().dot(existing.normal());
        if (candidateMinimum > existingMinimum
                + CollisionTolerances.ENTERING_PLANE_EPSILON) return true;
        if (existingMinimum > candidateMinimum
                + CollisionTolerances.ENTERING_PLANE_EPSILON) return false;
        if (candidate.penetration() > existing.penetration()
                + CollisionTolerances.PENETRATION_EPSILON) return true;
        if (existing.penetration() > candidate.penetration()
                + CollisionTolerances.PENETRATION_EPSILON) return false;
        return stableOrder.compare(candidate, existing) < 0;
    }

    private static boolean isOccludedStaticFace(
            CollisionContact candidate,
            PrimitiveSpatialIndex index
    ) {
        if (!(candidate.obstacle() instanceof BlockObstacle candidateBlock)) {
            return false;
        }
        // Neighbor-voxel culling is valid only for a proven static
        // world-axis voxel face. Oblique SAT features and degenerate feature
        // provenance are conservatively retained.
        if (!isAxisAligned(candidate.normal())) return false;
        // Exact full-cube seam elimination: a face of a full cube that shares
        // its face with an adjacent full cube is interior. The shortcut only
        // applies to primitives proven to cover the full unit cell.
        if (isFullCell(candidateBlock)) {
            BlockPos neighborPos = candidateBlock.blockPos().offset(
                    (int) Math.signum(candidate.normal().x),
                    (int) Math.signum(candidate.normal().y),
                    (int) Math.signum(candidate.normal().z)
            );
            IdentityHashMap<CollisionContact, Boolean> seen =
                    new IdentityHashMap<>();
            for (CollisionContact other : index.queryFineRegion(
                    neighborPos, SPATIAL_QUERY_RADIUS, seen
            )) {
                index.countSpatialCheck();
                if (other.obstacle() instanceof BlockObstacle otherBlock
                        && otherBlock.blockPos().equals(neighborPos)
                        && isFullCell(otherBlock)
                        && !samePrimitive(candidateBlock, otherBlock)) {
                    return true;
                }
            }
        }

        Vector3d surfaceWitness = axisAlignedSurfaceWitness(candidate);
        if (surfaceWitness == null) return false;
        Vector3d outwardSample = new Vector3d(surfaceWitness).add(
                new Vector3d(candidate.normal()).mul(
                        CollisionTolerances.VOXEL_OCCLUSION_PROBE_DISTANCE
                ));
        IdentityHashMap<CollisionContact, Boolean> seen =
                new IdentityHashMap<>();
        BlockPos sampleCell = new BlockPos(
                queryCellIndex(outwardSample.x, FINE_CELL_SIZE),
                queryCellIndex(outwardSample.y, FINE_CELL_SIZE),
                queryCellIndex(outwardSample.z, FINE_CELL_SIZE)
        );
        for (CollisionContact other : index.queryFineRegion(
                sampleCell, SPATIAL_QUERY_RADIUS, seen
        )) {
            index.countSpatialCheck();
            if (containsOutwardSample(other, candidateBlock, outwardSample)) {
                return true;
            }
        }
        BlockPos sampleCoarseCell = new BlockPos(
                queryCellIndex(outwardSample.x, COARSE_CELL_SIZE),
                queryCellIndex(outwardSample.y, COARSE_CELL_SIZE),
                queryCellIndex(outwardSample.z, COARSE_CELL_SIZE)
        );
        for (CollisionContact other : index.queryCoarseRegion(
                sampleCoarseCell, SPATIAL_QUERY_RADIUS, seen
        )) {
            index.countSpatialCheck();
            if (containsOutwardSample(other, candidateBlock, outwardSample)) {
                return true;
            }
        }
        for (CollisionContact other : index.oversized()) {
            index.countSpatialCheck();
            if (containsOutwardSample(other, candidateBlock, outwardSample)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOutwardSample(
            CollisionContact other,
            BlockObstacle candidateBlock,
            Vector3d outwardSample
    ) {
        if (!(other.obstacle() instanceof BlockObstacle otherBlock)) {
            return false;
        }
        if (samePrimitive(candidateBlock, otherBlock)) return false;
        return strictlyContains(otherBlock.bounds(), outwardSample);
    }

    private static boolean samePrimitive(
            BlockObstacle first,
            BlockObstacle second
    ) {
        return first.blockPos().equals(second.blockPos())
                && first.bounds().equals(second.bounds());
    }

    /**
     * True only when two contacts provably describe the same cotemporal
     * affine constraint. Different obstacle regions are retained unless they
     * are adjacent/overlapping axis-aligned voxel faces on the same plane.
     */
    private static boolean equivalentCotemporalConstraint(
            CollisionContact first,
            CollisionContact second,
            Aabb3d firstRegion
    ) {
        if (first.normal().dot(second.normal())
                < CollisionTolerances.VOXEL_SEAM_NORMAL_DOT) {
            return false;
        }
        if (Math.abs(first.timeOfImpact() - second.timeOfImpact())
                > CollisionTolerances.TOI_EPSILON) {
            return false;
        }
        double firstMinimum = first.surfaceVelocity().dot(first.normal());
        double secondMinimum = second.surfaceVelocity().dot(second.normal());
        if (Math.abs(firstMinimum - secondMinimum)
                > CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return false;
        }
        if (CollisionObstacle.STABLE_COMPARATOR.compare(
                first.obstacle(), second.obstacle()
        ) == 0) {
            return true;
        }
        if (!(first.obstacle() instanceof BlockObstacle firstBlock)
                || !(second.obstacle() instanceof BlockObstacle secondBlock)
                || !isAxisAligned(first.normal())
                || !isAxisAligned(second.normal())) {
            return false;
        }

        int axis = axisIndex(first.normal());
        if (axis != axisIndex(second.normal())) return false;
        double firstPlane = faceCoordinate(firstBlock.bounds(), first.normal(), axis);
        double secondPlane = faceCoordinate(secondBlock.bounds(), second.normal(), axis);
        if (Math.abs(firstPlane - secondPlane)
                > CollisionTolerances.CONTACT_SLOP) {
            return false;
        }
        return tangentialBoundsOverlap(
                firstRegion, secondBlock.bounds(), axis
        );
    }

    private static Aabb3d union(Aabb3d first, Aabb3d second) {
        return new Aabb3d(
                Math.min(first.minX(), second.minX()),
                Math.min(first.minY(), second.minY()),
                Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()),
                Math.max(first.maxY(), second.maxY()),
                Math.max(first.maxZ(), second.maxZ())
        );
    }

    /** Builds a point guaranteed to lie on the candidate Aabb3d face. */
    private static Vector3d axisAlignedSurfaceWitness(CollisionContact candidate) {
        if (!(candidate.obstacle() instanceof BlockObstacle block)
                || !isAxisAligned(candidate.normal())) {
            return null;
        }
        Aabb3d bounds = block.bounds();
        Vector3d point = candidate.point();
        int axis = axisIndex(candidate.normal());
        return switch (axis) {
            case 0 -> new Vector3d(
                    faceCoordinate(bounds, candidate.normal(), axis),
                    clamp(point.y, bounds.minY(), bounds.maxY()),
                    clamp(point.z, bounds.minZ(), bounds.maxZ())
            );
            case 1 -> new Vector3d(
                    clamp(point.x, bounds.minX(), bounds.maxX()),
                    faceCoordinate(bounds, candidate.normal(), axis),
                    clamp(point.z, bounds.minZ(), bounds.maxZ())
            );
            case 2 -> new Vector3d(
                    clamp(point.x, bounds.minX(), bounds.maxX()),
                    clamp(point.y, bounds.minY(), bounds.maxY()),
                    faceCoordinate(bounds, candidate.normal(), axis)
            );
            default -> null;
        };
    }

    private static int axisIndex(Vector3d normal) {
        double x = Math.abs(normal.x);
        double y = Math.abs(normal.y);
        double z = Math.abs(normal.z);
        if (x >= y && x >= z) return 0;
        if (y >= z) return 1;
        return 2;
    }

    private static double faceCoordinate(Aabb3d bounds, Vector3d normal, int axis) {
        return switch (axis) {
            case 0 -> normal.x >= 0.0D ? bounds.maxX() : bounds.minX();
            case 1 -> normal.y >= 0.0D ? bounds.maxY() : bounds.minY();
            case 2 -> normal.z >= 0.0D ? bounds.maxZ() : bounds.minZ();
            default -> throw new IllegalArgumentException("invalid axis: " + axis);
        };
    }

    private static boolean tangentialBoundsOverlap(
            Aabb3d first,
            Aabb3d second,
            int normalAxis
    ) {
        double epsilon = CollisionTolerances.CONTACT_SLOP;
        return switch (normalAxis) {
            case 0 -> rangesOverlap(first.minY(), first.maxY(), second.minY(), second.maxY(), epsilon)
                    && rangesOverlap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ(), epsilon);
            case 1 -> rangesOverlap(first.minX(), first.maxX(), second.minX(), second.maxX(), epsilon)
                    && rangesOverlap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ(), epsilon);
            case 2 -> rangesOverlap(first.minX(), first.maxX(), second.minX(), second.maxX(), epsilon)
                    && rangesOverlap(first.minY(), first.maxY(), second.minY(), second.maxY(), epsilon);
            default -> false;
        };
    }

    private static boolean rangesOverlap(
            double firstMin,
            double firstMax,
            double secondMin,
            double secondMax,
            double epsilon
    ) {
        return firstMax + epsilon >= secondMin
                && secondMax + epsilon >= firstMin;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isFullCell(BlockObstacle obstacle) {
        BlockPos pos = obstacle.blockPos();
        Aabb3d bounds = obstacle.bounds();
        return within(bounds.minX(), pos.getX())
                && within(bounds.minY(), pos.getY())
                && within(bounds.minZ(), pos.getZ())
                && within(bounds.maxX(), pos.getX() + 1.0D)
                && within(bounds.maxY(), pos.getY() + 1.0D)
                && within(bounds.maxZ(), pos.getZ() + 1.0D);
    }

    private static boolean within(double value, double expected) {
        return Math.abs(value - expected) <= 1.0E-6D;
    }

    /** True when the normal is numerically a world-axis face direction. */
    private static boolean isAxisAligned(Vector3d normal) {
        double absX = Math.abs(normal.x);
        double absY = Math.abs(normal.y);
        double absZ = Math.abs(normal.z);
        double axisCount = (absX >= 1.0D - 1.0E-9D ? 1.0D : 0.0D)
                + (absY >= 1.0D - 1.0E-9D ? 1.0D : 0.0D)
                + (absZ >= 1.0D - 1.0E-9D ? 1.0D : 0.0D);
        return axisCount == 1.0D;
    }

    private static boolean strictlyContains(Aabb3d bounds, Vector3d point) {
        double epsilon = CollisionTolerances.VOXEL_OCCLUSION_PROBE_DISTANCE * 0.25D;
        return point.x > bounds.minX() + epsilon && point.x < bounds.maxX() - epsilon
                && point.y > bounds.minY() + epsilon && point.y < bounds.maxY() - epsilon
                && point.z > bounds.minZ() + epsilon && point.z < bounds.maxZ() - epsilon;
    }

    /**
     * Overflow-safe capped occupancy predicate. Answers
     * {@code spanX * spanY * spanZ <= maximum} without ever computing an exact
     * product that could overflow a {@code long}.
     */
    private static boolean occupiesAtMost(CellRange range, long maximum) {
        long x = range.spanX();
        long y = range.spanY();
        long z = range.spanZ();
        if (x <= 0L || y <= 0L || z <= 0L || maximum <= 0L) return false;
        if (x > maximum) return false;
        long remaining = maximum / x;
        if (y > remaining) return false;
        remaining /= y;
        return z <= remaining;
    }

    /**
     * Immutable inclusive cell range. Built from a validated, positive-extent
     * primitive Aabb3d; the exclusive upper world coordinate is projected with
     * {@link Math#nextDown} so an exact integer boundary stays in its own
     * cell while tiny positive-extent primitives never collapse away.
     */
    private record CellRange(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        long spanX() {
            return (long) this.maxX() - this.minX() + 1L;
        }

        long spanY() {
            return (long) this.maxY() - this.minY() + 1L;
        }

        long spanZ() {
            return (long) this.maxZ() - this.minZ() + 1L;
        }
    }

    /**
     * Returns the spatial-cell index when it is exactly representable by the
     * BlockPos-backed index.
     *
     * <p>Finite coordinates outside the int-backed cell domain are not malformed
     * geometry. They simply cannot participate in the fine/coarse BlockPos index
     * and must fall through to the bounded oversized tier.</p>
     *
     * @return the exact cell index, or {@code null} when the coordinate lies
     * outside the representable int cell domain
     */
    private static Integer tryCellIndex(double coord, double cellSize) {
        if (!Double.isFinite(coord)) {
            throw new IllegalArgumentException(
                    "non-finite spatial coordinate: " + coord
            );
        }
        if (!Double.isFinite(cellSize) || cellSize <= 0.0D) {
            throw new IllegalArgumentException(
                    "cellSize must be finite and positive: " + cellSize
            );
        }

        double cell = Math.floor(coord / cellSize);
        if (!Double.isFinite(cell)
                || cell < Integer.MIN_VALUE
                || cell > Integer.MAX_VALUE) {
            return null;
        }

        return (int) cell;
    }

    /**
     * Query-side cell projection.
     *
     * <p>This is intentionally saturating because an unrepresentable query point
     * cannot have any matching fine/coarse primitive: such primitives are routed
     * to the oversized tier during index construction. The oversized tier is
     * always queried separately using exact Aabb3d containment.</p>
     */
    private static int queryCellIndex(double coord, double cellSize) {
        if (!Double.isFinite(coord)) {
            throw new IllegalArgumentException(
                    "non-finite spatial coordinate: " + coord
            );
        }
        if (!Double.isFinite(cellSize) || cellSize <= 0.0D) {
            throw new IllegalArgumentException(
                    "cellSize must be finite and positive: " + cellSize
            );
        }

        double cell = Math.floor(coord / cellSize);
        if (cell <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (cell >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) cell;
    }

    /**
     * Builds an exactly representable inclusive cell range for one primitive.
     *
     * <p>{@code null} means that at least one endpoint lies outside the
     * BlockPos-backed spatial-index domain. This is a normal finite-geometry case:
     * the caller must try the next coarser tier and ultimately use the bounded
     * oversized list rather than truncating or saturating the primitive.</p>
     */
    private static CellRange tryCellRange(Aabb3d bounds, double cellSize) {
        Objects.requireNonNull(bounds, "bounds");

        requireFinite(bounds.minX());
        requireFinite(bounds.minY());
        requireFinite(bounds.minZ());
        requireFinite(bounds.maxX());
        requireFinite(bounds.maxY());
        requireFinite(bounds.maxZ());

        if (!(bounds.maxX() > bounds.minX())
                || !(bounds.maxY() > bounds.minY())
                || !(bounds.maxZ() > bounds.minZ())) {
            throw new IllegalArgumentException(
                    "degenerate collision primitive Aabb3d (zero-extent axis): "
                            + bounds
            );
        }

        Integer minX = tryCellIndex(bounds.minX(), cellSize);
        Integer minY = tryCellIndex(bounds.minY(), cellSize);
        Integer minZ = tryCellIndex(bounds.minZ(), cellSize);

        // Spatial cells are treated as half-open intervals. Math.nextDown keeps an
        // exact upper cell boundary out of the following cell without imposing a
        // fixed world-space epsilon that could erase very thin valid primitives.
        Integer maxX = tryCellIndex(Math.nextDown(bounds.maxX()), cellSize);
        Integer maxY = tryCellIndex(Math.nextDown(bounds.maxY()), cellSize);
        Integer maxZ = tryCellIndex(Math.nextDown(bounds.maxZ()), cellSize);

        if (minX == null || minY == null || minZ == null
                || maxX == null || maxY == null || maxZ == null) {
            return null;
        }

        CellRange range = new CellRange(
                minX, minY, minZ,
                maxX, maxY, maxZ
        );

        // A positive-extent finite Aabb3d must never collapse into an inverted range.
        // If this happens it is an internal indexing invariant failure rather than
        // ordinary geometric complexity.
        if (range.spanX() <= 0L
                || range.spanY() <= 0L
                || range.spanZ() <= 0L) {
            throw new IllegalStateException(
                    "positive-extent collision primitive produced invalid cell range: "
                            + "bounds=" + bounds
                            + ", cellSize=" + cellSize
                            + ", range=" + range
            );
        }

        return range;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "non-finite collision primitive coordinate: " + value
            );
        }
    }

    private static int compareStable(CollisionContact left, CollisionContact right) {
        int comparison = CollisionObstacle.STABLE_COMPARATOR.compare(
                left.obstacle(), right.obstacle()
        );
        if (comparison != 0) return comparison;
        comparison = compareVec(left.normal(), right.normal());
        if (comparison != 0) return comparison;
        comparison = compareVec(left.point(), right.point());
        if (comparison != 0) return comparison;
        return Double.compare(left.timeOfImpact(), right.timeOfImpact());
    }

    private static int compareVec(Vector3d left, Vector3d right) {
        int comparison = Double.compare(left.x, right.x);
        if (comparison != 0) return comparison;
        comparison = Double.compare(left.y, right.y);
        if (comparison != 0) return comparison;
        return Double.compare(left.z, right.z);
    }

    /** Total bounded reducer work; never a wall-clock benchmark. */
    record ReduceWork(
            int spatialCandidateChecks,
            int planeBucketChecks,
            int contactsProcessed,
            int cellRegistrations,
            int finePrimitives,
            int coarsePrimitives,
            int oversizedPrimitives
    ) {}

    /** Immutable reduction outcome including the bounded work performed. */
    record ReduceResult(List<CollisionContact> contacts, ReduceWork work) {}

    private record NormalBucket(int x, int y, int z) {}

    /** Checked work budget consumed by the reducer's comparison/index passes. */
    interface WorkBudgetSink {
        boolean recordChecks(int count);

        boolean recordRegistrations(int count);
    }

    /** Operation-local budget backed by the shared {@link CollisionWorkTracker}. */
    private static final class TrackerWorkBudget implements WorkBudgetSink {
        private final CollisionWorkTracker tracker;

        TrackerWorkBudget(CollisionWorkTracker tracker) {
            this.tracker = Objects.requireNonNull(tracker, "tracker");
        }

        @Override
        public boolean recordChecks(int count) {
            return this.tracker.recordVoxelReducerChecks(count);
        }

        @Override
        public boolean recordRegistrations(int count) {
            return this.tracker.recordVoxelIndexRegistrations(count);
        }
    }

    /** Reducer-local hard budget for one-shot/test callers without a tracker. */
    private static final class LocalWorkBudget implements WorkBudgetSink {
        private int checks;
        private int registrations;

        @Override
        public boolean recordChecks(int count) {
            if (count <= 0) return true;
            if (this.checks > LOCAL_MAX_COMPARISON_CHECKS - count) return false;
            this.checks += count;
            return true;
        }

        @Override
        public boolean recordRegistrations(int count) {
            if (count <= 0) return true;
            if (this.registrations > LOCAL_MAX_CELL_REGISTRATIONS - count) {
                return false;
            }
            this.registrations += count;
            return true;
        }
    }

    /**
     * Lookup-only spatial index over the cells that each {@link BlockObstacle}
     * primitive actually occupies. Ordinary primitives live in one-block fine
     * cells; oversized primitives fall back to four-block coarse cells and a
     * hard-capped global list. Iteration order never influences output.
     */
    private static final class PrimitiveSpatialIndex {
        private final Map<BlockPos, List<CollisionContact>> fine = new HashMap<>();
        private final Map<BlockPos, List<CollisionContact>> coarse = new HashMap<>();
        private final List<CollisionContact> oversized = new ArrayList<>();
        private final WorkBudgetSink budget;
        private int spatialCandidateChecks;
        private int cellRegistrations;
        private int finePrimitives;
        private int coarsePrimitives;
        private int oversizedPrimitives;

        PrimitiveSpatialIndex(
                List<CollisionContact> ordered,
                WorkBudgetSink budget
        ) {
            this.budget = Objects.requireNonNull(budget, "budget");
            for (CollisionContact contact : ordered) {
                if (!(contact.obstacle() instanceof BlockObstacle block)) {
                    continue;
                }
                Aabb3d bounds = block.bounds();

                CellRange fineRange = tryCellRange(bounds, FINE_CELL_SIZE);
                if (fineRange != null
                        && occupiesAtMost(
                        fineRange,
                        MAX_FINE_CELLS_PER_PRIMITIVE
                )) {
                    registerCells(fine, fineRange, contact);
                    this.finePrimitives++;
                    continue;
                }

                CellRange coarseRange = tryCellRange(bounds, COARSE_CELL_SIZE);
                if (coarseRange != null
                        && occupiesAtMost(
                        coarseRange,
                        MAX_COARSE_CELLS_PER_PRIMITIVE
                )) {
                    registerCells(coarse, coarseRange, contact);
                    this.coarsePrimitives++;
                    continue;
                }

                /*
                 * Either:
                 *
                 * 1. the primitive occupies too many fine/coarse cells, or
                 * 2. its finite coordinates lie outside the BlockPos-backed spatial-index
                 *    domain.
                 *
                 * Both cases are ordinary spatial complexity, not malformed geometry.
                 * Keep exact Aabb3d geometry in the bounded oversized tier.
                 */
                if (this.oversized.size() >= MAX_GLOBAL_OVERSIZED) {
                    throw new CollisionComplexityLimitException(
                            "voxel reducer oversized-primitive budget exceeded"
                    );
                }

                this.oversized.add(contact);
                this.oversizedPrimitives++;
            }
        }

        private void registerCells(
                Map<BlockPos, List<CollisionContact>> cells,
                CellRange range,
                CollisionContact contact
        ) {
            // Long loops with verified int casts: cell indices saturate at the
            // int bounds for absurdly huge finite coordinates, and occupancy
            // was already proven <= 27 cells, so the loop always terminates.
            for (long x = range.minX(); x <= (long) range.maxX(); x++) {
                for (long y = range.minY(); y <= (long) range.maxY(); y++) {
                    for (long z = range.minZ(); z <= (long) range.maxZ(); z++) {
                        if (!this.budget.recordRegistrations(1)) {
                            throw workLimit();
                        }
                        cells.computeIfAbsent(
                                new BlockPos((int) x, (int) y, (int) z),
                                ignored -> new ArrayList<>()
                        ).add(contact);
                        this.cellRegistrations++;
                    }
                }
            }
        }

        /**
         * Contacts whose primitives occupy any cell within {@code radius} of
         * the center cell, deduplicated by identity in deterministic order.
         */
        List<CollisionContact> queryFineRegion(
                BlockPos center,
                int radius,
                IdentityHashMap<CollisionContact, Boolean> seen
        ) {
            List<CollisionContact> result = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        List<CollisionContact> cell = fine.get(
                                center.offset(dx, dy, dz)
                        );
                        if (cell == null) continue;
                        for (CollisionContact contact : cell) {
                            if (seen.put(contact, Boolean.TRUE) == null) {
                                result.add(contact);
                            }
                        }
                    }
                }
            }
            return result;
        }

        List<CollisionContact> queryCoarseRegion(
                BlockPos center,
                int radius,
                IdentityHashMap<CollisionContact, Boolean> seen
        ) {
            List<CollisionContact> result = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        List<CollisionContact> cell = coarse.get(
                                center.offset(dx, dy, dz)
                        );
                        if (cell == null) continue;
                        for (CollisionContact contact : cell) {
                            if (seen.put(contact, Boolean.TRUE) == null) {
                                result.add(contact);
                            }
                        }
                    }
                }
            }
            return result;
        }

        List<CollisionContact> oversized() {
            return this.oversized;
        }

        void countSpatialCheck() {
            if (!this.budget.recordChecks(1)) {
                throw workLimit();
            }
            this.spatialCandidateChecks++;
        }

        int spatialCandidateChecks() {
            return this.spatialCandidateChecks;
        }

        int cellRegistrations() {
            return this.cellRegistrations;
        }

        int finePrimitives() {
            return this.finePrimitives;
        }

        int coarsePrimitives() {
            return this.coarsePrimitives;
        }

        int oversizedPrimitives() {
            return this.oversizedPrimitives;
        }
    }
}
