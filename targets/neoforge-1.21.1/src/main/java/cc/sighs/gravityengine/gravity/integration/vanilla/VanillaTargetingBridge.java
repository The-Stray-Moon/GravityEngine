package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import java.util.function.Predicate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Gameplay targeting bridge: view rays, hit-result selection and projectile
 * candidate refinement over captured actor snapshots.
 *
 * <p>Reproduces the exact Vanilla 21.1.249 selection policy of each call site
 * while expressing its operands in the actor's reference frame.</p>
 */
public final class VanillaTargetingBridge {
    private VanillaTargetingBridge() {}

    /**
     * Which 1.21.1 {@code ProjectileUtil.getEntityHitResult} overload the
     * caller is reproducing.  The two overloads differ in observable policy:
     * {@link #PICK} applies {@code Entity.getPickRadius()} inflation, inside-
     * origin selection and root-vehicle/rider-interaction tie rules;
     * {@link #PROJECTILE_VIEW} applies a caller margin and nearest-hit
     * selection and constructs the result without a clipped point.
     */
    public enum SelectionPolicy {
        PICK,
        PROJECTILE_VIEW
    }

    /**
     * The result entity and the exact location returned to Vanilla.  A
     * {@code null} location is preserved when the reproduced overload
     * constructs {@code EntityHitResult(entity)} (the 1.21.1
     * Level-inflation overload), which then reads {@code entity.position()}.
     */
    private record EntitySelection(
            Entity entity,
            @javax.annotation.Nullable Vec3 location
    ) {}

    /** One immutable gameplay view ray. */
    public record ViewRay(
            Vec3 origin,
            Vec3 direction,
            Vec3 end
    ) {
        public ViewRay {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(end, "end");

            double lengthSquared =
                    direction.lengthSqr();

            if (!Double.isFinite(lengthSquared)
                    || lengthSquared <= 1.0E-20D) {
                throw new IllegalArgumentException(
                        "view direction must be non-degenerate"
                );
            }
        }
    }

    public static ViewRay viewRay(
            VanillaActorSnapshot actor,
            double distance
    ) {
        Objects.requireNonNull(actor, "actor");

        if (!Double.isFinite(distance)
                || distance < 0.0D) {
            throw new IllegalArgumentException(
                    "view distance must be finite and non-negative"
            );
        }

        Vec3 origin = actor.eye();
        Vec3 direction = actor.viewForward();

        return new ViewRay(
                origin,
                direction,
                origin.add(
                        direction.scale(distance)
                )
        );
    }

    /**
     * Exact 1.21.1 {@code ProjectileUtil.getEntityHitResult(Entity,...)}
     * selection policy used by client picking.
     *
     * <p>Pick radius, inside-origin behavior, zero-distance behavior,
     * same-root-vehicle handling and {@code canRiderInteract()} retain their
     * Vanilla ordering. Only custom-body clip/contains geometry changes.</p>
     */
    @Nullable
    public static EntityHitResult selectPickingEntityTarget(
            Level level,
            Entity shooter,
            Vec3 start,
            Vec3 end,
            AABB corridor,
            Predicate<Entity> filter,
            double maximumSquared
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(shooter, "shooter");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(filter, "filter");

        return toEntityHitResult(
                selectPickingEntityTargetInternal(
                        level,
                        shooter,
                        start,
                        end,
                        corridor,
                        filter,
                        maximumSquared
                )
        );
    }

    /**
     * Exact 21.1.249 {@code ProjectileUtil.getEntityHitResult(Level, Entity,
     * Vec3, Vec3, AABB, Predicate, float)} reproduction.
     *
     * <p>This is the stable Level-based entity-selection seam reached by the
     * private {@code getHitResult(...)} helper (both move-vector overloads
     * and the view-vector overload), by the no-margin Level overload that
     * {@code AbstractArrow.findHitEntity} calls directly (arrows, tridents
     * and the whole arrow family), and by other 21.1.249 callers with the
     * same segment ray contract such as {@code PlayerPredicate}. The
     * {@code ProjectileUtilMixin} wraps exactly this public overload, so one
     * authoritative exact-body implementation covers every projectile
     * caller.</p>
     *
     * <p>Vanilla 1.21.1 semantics preserved exactly: candidate broad phase
     * is {@code level.getEntities(projectile, boundingBox, filter)}, each
     * candidate is inflated by {@code (double) margin}, the narrow phase is
     * {@code AABB.clip(start, end)} with a strict nearest-distance tie, and
     * the result is the two-argument-less {@code EntityHitResult(entity)}
     * form whose location reads {@code entity.position()}. A {@code null}
     * result is returned when nothing clips.</p>
     *
     * <p>Ordinary Vanilla AABB candidates retain their exact AABB behavior.
     * Only custom exact bodies substitute their oriented volume (inflated by
     * the same margin) for the enclosing-AABB narrow phase, so an
     * enclosing-AABB-only corner can never become a phantom hit while the
     * result entity/location and ordering stay Vanilla-identical.</p>
     */
    @Nullable
    public static EntityHitResult selectProjectileSegmentTarget(
            Level level,
            Entity projectile,
            Vec3 start,
            Vec3 end,
            AABB corridor,
            Predicate<Entity> filter,
            float margin
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(projectile, "projectile");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(filter, "filter");

        double bestSquared = Double.MAX_VALUE;
        Entity selected = null;

        List<Entity> entities =
                level.getEntities(
                        projectile,
                        corridor,
                        filter
                );

        for (Entity candidate : entities) {
            CandidateGeometry geometry =
                    candidateGeometry(
                            candidate,
                            margin,
                            start,
                            end
                    );

            Optional<Vec3> clipped =
                    geometry.clipped();

            if (clipped.isEmpty()) {
                continue;
            }

            Vec3 point = clipped.get();
            double candidateSquared =
                    start.distanceToSqr(point);

            if (candidateSquared < bestSquared) {
                selected = candidate;
                bestSquared = candidateSquared;
            }
        }

        return selected == null
                ? null
                : new EntityHitResult(selected);
    }

    @Nullable
    private static EntitySelection selectPickingEntityTargetInternal(
            Level level,
            Entity shooter,
            Vec3 start,
            Vec3 end,
            AABB corridor,
            Predicate<Entity> filter,
            double maximumSquared
    ) {
        double bestSquared =
                maximumSquared;

        Entity selected = null;
        Vec3 selectedPoint = null;

        List<Entity> entities =
                level.getEntities(
                        shooter,
                        corridor,
                        filter
                );

        for (Entity candidate : entities) {
            double inflation = candidate.getPickRadius();

            CandidateGeometry geometry =
                    candidateGeometry(
                            candidate,
                            inflation,
                            start,
                            end
                    );

            Optional<Vec3> clipped =
                    geometry.clipped();

            if (geometry.inside()) {
                if (bestSquared >= 0.0D) {
                    selected = candidate;
                    selectedPoint =
                            clipped.orElse(start);
                    bestSquared = 0.0D;
                }
                continue;
            }

            if (clipped.isEmpty()) {
                continue;
            }

            Vec3 point = clipped.get();
            double candidateSquared =
                    start.distanceToSqr(point);

            if (candidateSquared < bestSquared
                    || bestSquared == 0.0D) {
                boolean sameRootVehicle =
                        candidate.getRootVehicle()
                                == shooter.getRootVehicle();

                if (sameRootVehicle
                        && !candidate.canRiderInteract()) {
                    if (bestSquared == 0.0D) {
                        selected = candidate;
                        selectedPoint = point;
                    }
                } else {
                    selected = candidate;
                    selectedPoint = point;
                    bestSquared = candidateSquared;
                }
            }
        }

        return selected == null
                ? null
                : new EntitySelection(selected, selectedPoint);
    }

    @Nullable
    private static EntityHitResult toEntityHitResult(
            @Nullable EntitySelection selection
    ) {
        if (selection == null) {
            return null;
        }
        return selection.location() == null
                ? new EntityHitResult(selection.entity())
                : new EntityHitResult(
                selection.entity(),
                selection.location()
        );
    }

    /**
     * Refines one Vanilla projectile candidate's already-computed AABB clip with
     * authoritative exact-body geometry when such geometry exists.
     *
     * <p>The caller MUST execute Vanilla {@code AABB.clip(start, end)} first and
     * pass that result here. Ordinary entities return that result unchanged.
     * Unsupported/non-standard inflation values also deliberately fall back to
     * Vanilla instead of imposing a new public-API validation policy.</p>
     *
     * <p>For normal 1.21.1 projectile callers the inflation is 0.0F or 0.3F, so
     * custom exact bodies use the same inflation amount in their own body frame
     * before segment clipping.</p>
     */
    /**
     * Point-to-target distance used by server interaction validation.
     *
     * <p>Ordinary entities preserve Vanilla AABB semantics. A custom exact
     * body uses its OBB closest point. No target look state is captured.</p>
     */
    public static double squaredDistanceToTargetBody(
            Entity target,
            Vec3 point
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(point, "point");

        CharacterCapsule exact =
                VanillaActorBridge
                        .exactBodyForQuery(target);

        if (exact == null) {
            return target.getBoundingBox()
                    .distanceToSqr(point);
        }

        Vector3d closest =
                exact.closestPointTo(
                        new Vector3d(
                                point.x,
                                point.y,
                                point.z
                        )
                );

        return point.distanceToSqr(
                new Vec3(
                        closest.x,
                        closest.y,
                        closest.z
                )
        );
    }

    public static Optional<Vec3> refineProjectileCandidateClip(
            Entity candidate,
            double inflation,
            Vec3 start,
            Vec3 end,
            Optional<Vec3> vanillaClip
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(vanillaClip, "vanillaClip");

        CharacterCapsule exact =
                VanillaActorBridge.exactBodyForQuery(candidate);

        if (exact == null) {
            return vanillaClip;
        }

        /*
         * Vanilla's public method does not reject unusual margin values.
         *
         * Exact-body inflation has a stronger geometry contract, so for a
         * third-party caller supplying NaN/negative values preserve Vanilla's
         * already-computed AABB result rather than throwing a new exception.
         */
        if (!Double.isFinite(inflation) || inflation < 0.0D) {
            return vanillaClip;
        }

        return exactCandidateGeometry(
                exact,
                inflation,
                start,
                end
        ).clipped();
    }

    /**
     * Temporary query volume only. Inflation never mutates collision authority.
     */
    private static CandidateGeometry candidateGeometry(
            Entity entity,
            double radius,
            Vec3 start,
            Vec3 end
    ) {
        CharacterCapsule exact =
                VanillaActorBridge
                        .exactBodyForQuery(entity);

        /*
         * Do not introduce stricter validation than the Vanilla carrier.
         * Unsupported inflation values keep Vanilla AABB semantics.
         */
        if (exact == null
                || !Double.isFinite(radius)
                || radius < 0.0D) {
            AABB volume =
                    entity.getBoundingBox()
                            .inflate(radius);

            return new CandidateGeometry(
                    volume.contains(start),
                    volume.clip(start, end)
            );
        }

        return exactCandidateGeometry(
                exact,
                radius,
                start,
                end
        );
    }

    private static CandidateGeometry exactCandidateGeometry(
            CharacterCapsule exact,
            double radius,
            Vec3 start,
            Vec3 end
    ) {
        CharacterCapsule volume = exact.inflated(radius);
        Vector3d from = new Vector3d(start.x, start.y, start.z);
        Vector3d to = new Vector3d(end.x, end.y, end.z);
        double[] interval = volume.segmentInterval(from, to);
        boolean contains = volume.closestPointTo(from).distanceSquared(from) <= 1e-24;
        return new CandidateGeometry(contains, interval == null ? Optional.empty()
                : Optional.of(start.lerp(end, interval[0])));

    }

    private record CandidateGeometry(
            boolean inside,
            Optional<Vec3> clipped
    ) {}

    private static final double TANGENT_EPSILON = 1.0E-12D;
}
