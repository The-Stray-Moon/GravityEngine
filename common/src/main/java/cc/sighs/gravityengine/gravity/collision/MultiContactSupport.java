package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single controller joint-support implementation for arbitrary gravity.
 *
 * <p>Several real static contacts may carry the character together even
 * though no single one of them reaches the walkable slope allowance. The
 * joint reaction of those contacts is the controller's static-traction
 * result: {@code stableGround}, ordinary jump and discrete step qualification
 * are granted, while continuous floor following, plane retention and hard
 * collision clipping still require a real walkable face. A joint result is
 * therefore published as
 * {@link GravitySupportContact.SupportGeometryKind#MANIFOLD} with no face
 * identity, and it never becomes a synthetic collision plane.</p>
 *
 * <p>Classification never uses {@code contactCount >= 2}, a zero requested
 * motion, an observed zero velocity or a previous {@code onGround}. It probes
 * the unit gravity load itself.</p>
 *
 * <p>This extension covers {@link CharacterCapsule} against static
 * parent-world block geometry. Moving obstacles keep their existing
 * support/carry policy: unrelated surface velocities are never averaged into
 * a fictitious stationary support, and optional external-rigid contact
 * classification stays outside this class.</p>
 */
public final class MultiContactSupport {
    /** Bounded distinct load directions considered for one support answer. */
    private static final int MAX_DISTINCT_NORMALS = 16;

    /** Moving/squeezing surfaces are not a static-rest certificate. */
    private static final double STATIC_SPEED_SQUARED = 1.0E-12D;

    private MultiContactSupport() {}

    /** Same-instant joint support evidence. Never supplies a collision plane. */
    public record Result(Optional<GravitySupportContact> support,
                         CellPos block,
                         boolean indeterminate) {
        static final Result NONE = new Result(Optional.empty(), null, false);
        static final Result UNKNOWN = new Result(Optional.empty(), null, true);
    }

    /** Tri-state of one joint-load classification. */
    private enum LoadKind { NONE, STABLE, UNKNOWN }

    /**
     * Bounded projection of the unit gravity load onto the static contact
     * half-spaces. A failure carries no projection.
     */
    private record LoadProjection(ContactConstraintProjector.Result projection,
                                  Vec3d up,
                                  LoadKind failure) {
        static LoadProjection failed(LoadKind kind) {
            return new LoadProjection(null, null, kind);
        }

        boolean usable() {
            return failure == null;
        }
    }

    /**
     * Controller joint-traction predicate for one set of static contact
     * directions.
     *
     * <p>This is the same classification core as {@link #probe} and
     * {@link #classify}: the unit gravity load is projected onto
     * {@code n_i dot x >= 0} and the resulting joint reaction must satisfy the
     * authored static-traction slope allowance. It is deliberately NOT a
     * frictionless rigid-body equilibrium test; see
     * {@link #frictionlessEquilibrium} for that separate named predicate.</p>
     */
    public static boolean supportsGravity(Vec3d down, List<Vec3d> normals) {
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(normals, "normals");
        LoadProjection load = projectLoad(down.negate(), normals);
        return load.usable() && jointReaction(load).isPresent();
    }

    /**
     * Strict frictionless static equilibrium of the same contact set: the unit
     * gravity load must already be admissible in the contact cone, so the
     * projection needs no correction and no joint reaction exists.
     *
     * <p>This is a named mathematical predicate kept separate from controller
     * groundedness. It is deliberately never used as a support gate: a real
     * in-place pit is load-bearing for the controller while failing this
     * stricter condition.</p>
     */
    static boolean frictionlessEquilibrium(Vec3d down, List<Vec3d> normals) {
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(normals, "normals");
        LoadProjection load = projectLoad(down.negate(), normals);
        return load.usable()
                && load.projection().status()
                == ContactConstraintProjector.Status.UNCONSTRAINED;
    }

    /**
     * Deterministic classification over already-collected real contacts.
     *
     * <p>Package-local seam for focused regression fixtures. Production callers
     * reach the identical core through {@link #probe} (and therefore
     * {@link GravityGroundProbe}); this method performs no scene capture and
     * owns no work budget.</p>
     */
    static Result classify(CharacterCapsule body, GravityFrame frame,
                           List<CollisionContact> currentContacts) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(currentContacts, "currentContacts");
        return classifyContacts(body.center(), unitUp(frame), currentContacts);
    }

    /**
     * Static joint support over the current captured scene.
     *
     * <p>Real walkable feet faces keep their priority in
     * {@link GravityGroundProbe}; this query only certifies a joint
     * load-bearing manifold of static block contacts. It never creates a
     * collision plane, never moves the body and never consumes the
     * hard-collision work budget.</p>
     */
    public static Result probe(CollisionBody body, GravityFrame frame,
                               CollisionScene scene, double time, ObbQueryContext owner) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(owner, "owner");
        if (!Double.isFinite(time)
                || time < 0.0D
                || time > scene.time().intervalTicks()) {
            throw new IllegalArgumentException(
                    "obstacleTimeTicks outside operation: " + time
            );
        }

        if (body instanceof CharacterCapsule capsule) {
            return probeCapsule(capsule, frame, scene, time, owner);
        }

        /*
         * Existing non-capsule/other-shape route: the current-instant hard
         * contact set still classifies through the SAME joint-load core. The
         * captured scene and instant are reused; only the SUPPORT work tracker
         * is charged.
         */
        ObbQueryContext scratch = new ObbQueryContext();
        scratch.setWorkTracker(owner.supportWorkTracker());
        CollisionScene supportScene = new CollisionScene() {
            @Override public List<CollisionObstacle> query(
                    CollisionBody b, Vec3d v, SweepTimeWindow w) {
                return scene.querySupport(b, v, w);
            }
            @Override public KinematicStepContext time() { return scene.time(); }
        };
        var current = CurrentContactQuery.contacts(body, supportScene, time, scratch);
        if (current.indeterminate() || owner.supportWorkTracker().limitExceeded()) {
            return Result.UNKNOWN;
        }
        if (CurrentContactQuery.hasMeaningfulPenetration(current)) return Result.NONE;
        return classifyContacts(body.center(), unitUp(frame), current.contacts());
    }

    private static Result probeCapsule(CharacterCapsule capsule, GravityFrame frame,
                                       CollisionScene scene, double time, ObbQueryContext owner) {
        var work = owner.supportWorkTracker();
        if (work.limitExceeded()) return Result.UNKNOWN;

        /*
         * Current signed distance, not a future sweep. Include the same tiny
         * numerical resting gap as the existing feet probe: hard CCD often
         * intentionally leaves CONTACT_SKIN, which exceeds CONTACT_SLOP, and a
         * real contact must not be lost to that band.
         */
        CollisionBody envelope = OrientedBox.axisAligned(
                capsule.enclosingAabb().inflate(GravityGroundProbe.PROBE_DISTANCE));
        List<CollisionObstacle> obstacles = scene.querySupport(
                envelope, Vec3d.ZERO, new SweepTimeWindow(time, 0.0D));
        if (work.limitExceeded()) return Result.UNKNOWN;

        List<CollisionContact> contacts = new ArrayList<>();
        for (CollisionObstacle obstacle : obstacles) {
            /*
             * This extension certifies static parent-world voxel manifolds.
             * Moving obstacles keep the existing real-face support route.
             */
            if (!(obstacle instanceof BlockObstacle block)) continue;
            if (!work.recordNarrowPhaseTest()) return Result.UNKNOWN;
            var geometry = CapsuleAabbCollision.contactGeometry(capsule, block.bounds());
            double gap = geometry.signedGap();
            if (!Double.isFinite(gap)) return Result.UNKNOWN;
            /* Recovery/invalid overlap cannot manufacture resting support. */
            if (gap < -CollisionTolerances.PENETRATION_EPSILON) return Result.NONE;
            if (gap > GravityGroundProbe.PROBE_DISTANCE) continue;
            if (!work.recordContacts(1)) return Result.UNKNOWN;
            contacts.add(new CollisionContact(
                    block, geometry.pointOnObstacle(), geometry.normal(),
                    Math.max(0.0D, -gap), 0.0D, Vec3d.ZERO,
                    time / scene.time().intervalTicks()));
        }
        return classifyContacts(capsule.center(), unitUp(frame), contacts);
    }

    /**
     * The one joint-support classification core. Every entry point and every
     * shape route funnels through here.
     */
    private static Result classifyContacts(Vec3d center, Vec3d up,
                                           List<CollisionContact> currentContacts) {
        List<CollisionContact> ordered = currentContacts.stream()
                .filter(contact -> contact.obstacle() instanceof BlockObstacle)
                .sorted(KinematicSweepKernel.CONTACT_ORDER)
                .toList();

        List<Vec3d> normals = new ArrayList<>(ordered.size());
        for (CollisionContact contact : ordered) {
            /*
             * Moving/squeezing surfaces are not a static-rest certificate. A
             * moving obstacle is never averaged into a fictitious stationary
             * manifold.
             */
            if (contact.surfaceVelocity().lengthSquared() > STATIC_SPEED_SQUARED) {
                return Result.NONE;
            }
            normals.add(contact.normal());
        }

        LoadProjection load = projectLoad(up, normals);
        if (!load.usable()) {
            return load.failure() == LoadKind.UNKNOWN ? Result.UNKNOWN : Result.NONE;
        }
        Optional<Vec3d> jointReaction = jointReaction(load);
        if (jointReaction.isEmpty()) return Result.NONE;

        CollisionContact representative = null;
        double bestUp = CollisionTolerances.ENTERING_PLANE_EPSILON;
        for (CollisionContact contact : ordered) {
            if (!bearsLoad(load.projection(), contact.normal())) continue;
            double normalUp = contact.normal().dot(up);
            /*
             * At least one load-bearing contact must be on the lower half of
             * the body: a head-only brace is not a standing support. Equal
             * candidates keep the first deterministic order, so the witness
             * never depends on the scene's obstacle order.
             */
            if (normalUp > bestUp
                    && contact.point().subtract(center).dot(up)
                    <= GravityGroundProbe.PROBE_DISTANCE) {
                representative = contact;
                bestUp = normalUp;
            }
        }
        if (representative == null) return Result.NONE;

        /*
         * MANIFOLD is synthetic controller evidence: the joint reaction with a
         * real load-bearing witness point, no fabricated plane and no face
         * identity. Hard velocity response still rebuilds the REAL endpoint
         * contacts.
         */
        GravitySupportContact support = new GravitySupportContact(
                jointReaction.get(),
                Vec3d.ZERO,
                representative.point(),
                GravitySupportContact.SupportGeometryKind.MANIFOLD
        );
        CellPos block = ((BlockObstacle) representative.obstacle()).blockPos();
        return new Result(Optional.of(support), block, false);
    }

    /**
     * Builds the bounded, unit-normalized, de-duplicated static contact
     * half-spaces and projects the unit gravity load onto them.
     *
     * <p>De-duplication uses the project's existing same-constraint identity
     * rule, and {@link ContactConstraintProjector} owns the deterministic
     * constraint order used for enumeration and active-set reporting.</p>
     */
    private static LoadProjection projectLoad(Vec3d upDirection,
                                              List<? extends Vec3d> normals) {
        if (!isFinite(upDirection)
                || upDirection.lengthSquared()
                <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            return LoadProjection.failed(LoadKind.NONE);
        }
        Vec3d up = upDirection.normalized();

        List<ContactConstraintProjector.Constraint> constraints = new ArrayList<>();
        for (Vec3d normal : normals) {
            if (!isFinite(normal)
                    || normal.lengthSquared()
                    <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
                return LoadProjection.failed(LoadKind.NONE);
            }
            ContactConstraintProjector.Constraint canonical =
                    new ContactConstraintProjector.Constraint(normal, 0.0D);
            boolean duplicate = constraints.stream().anyMatch(existing ->
                    CollisionTolerances.sameConstraintIdentity(
                            existing.normal(), canonical.normal()));
            if (duplicate) continue;
            if (constraints.size() >= MAX_DISTINCT_NORMALS) {
                return LoadProjection.failed(LoadKind.UNKNOWN);
            }
            constraints.add(canonical);
        }
        if (constraints.size() < 2) {
            /* One real face keeps its own existing single-face classification. */
            return LoadProjection.failed(LoadKind.NONE);
        }

        /*
         * Probe the unit gravity LOAD, never the player's input, requested
         * motion or observed velocity: p = nearest admissible displacement to
         * -up subject to n_i dot p >= 0.
         */
        ContactConstraintProjector.Result projection =
                ContactConstraintProjector.project(
                        up.negate(), constraints);
        return projection.infeasible()
                ? LoadProjection.failed(LoadKind.UNKNOWN)
                : new LoadProjection(projection, up, null);
    }

    /**
     * The joint reaction {@code p + up} of a usable projection, or empty when
     * fewer than two independent constraints bear the load or the joint
     * reaction fails the authored static-traction slope allowance.
     *
     * <p>This is controller static traction, not a frictionless rigid-body
     * equilibrium claim.</p>
     */
    private static Optional<Vec3d> jointReaction(LoadProjection load) {
        ContactConstraintProjector.Result projection = load.projection();
        if (projection.activeRank() < 2) return Optional.empty();

        Vec3d reaction =
                projection.requireProjectedVector().add(load.up());
        double reactionLengthSquared = reaction.lengthSquared();
        if (!Double.isFinite(reactionLengthSquared)
                || reactionLengthSquared
                <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            return Optional.empty();
        }
        reaction = reaction.normalized();
        if (reaction.dot(load.up())
                < TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT) {
            return Optional.empty();
        }
        return Optional.of(reaction);
    }

    /** Whether one real contact direction belongs to the selected active set. */
    private static boolean bearsLoad(ContactConstraintProjector.Result projection,
                                     Vec3d normal) {
        return projection.activePlanes().stream().anyMatch(plane ->
                CollisionTolerances.sameConstraintIdentity(plane.normal(), normal));
    }

    private static Vec3d unitUp(GravityFrame frame) {
        return new Vec3d(
                frame.up().x(), frame.up().y(), frame.up().z()
        ).normalized();
    }

    private static boolean isFinite(Vec3d vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }
}
