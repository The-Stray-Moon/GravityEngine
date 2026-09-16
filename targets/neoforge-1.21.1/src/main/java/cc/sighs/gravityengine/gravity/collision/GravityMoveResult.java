package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of exactly one custom collision resolution for one
 * {@code Entity.move} invocation.
 *
 * <p>Supporting contact during the move and physical support at the endpoint
 * have separate lifetimes. Gameplay grounding is the determinate union of stable movement and endpoint support for
 * this commit only; terminal support identity, material, walkability and
 * velocity constraints describe only the solved endpoint. Recovery alone
 * cannot establish supporting movement contact.</p>
 *
 * <p>Physical translation is split into explicit provenance:</p>
 * <ul>
 *   <li>{@code recoveryMovement} — explicit geometric correction (recovery or selected-face floor snap). It is
 *       geometry repair, never player locomotion, and must not feed
 *       fall-distance/fall-damage accumulation or directional progress.</li>
 *   <li>{@code locomotionMovement} — the accepted ordinary/step route. All
 *       gameplay flags (blocked/terminalGrounded/support/step) are derived from this
 *       route alone.</li>
 * </ul>
 * {@code resolvedMovement} is always {@code recoveryMovement +
 * locomotionMovement}, enforced by the canonical constructor.
 *
 * <p>{@code indeterminate} distinguishes ordinary collision (movement clipped,
 * solve converged) from degraded completion (no-progress or budget
 * exhaustion). A degraded result still carries every displacement already
 * proven legal: the marker means only that a later stage could not be
 * established, so the integration layer suppresses derived facts (grounding,
 * support, contact velocity) instead of rejecting the translation.</p>
 * <p>{@code indeterminateReason} retains the first failing solver stage. It is
 * diagnostic provenance; the solved displacement remains the only translation
 * authority for this invocation.</p>
 *
 * <p>{@code contactVelocityConstraints} is the detached endpoint response for
 * the direction resolved by this movement. Each plane preserves its sampled
 * affine surface velocity and real hard-contact normal, including WALLs.
 * No Entity, Level, CollisionObstacle
 * or scene is retained. A later callback, gravity or friction velocity can have
 * different finite-feature entry, so production velocity boundaries revalidate
 * that actual velocity using the existing operation scene and exact endpoint.</p>
 *
 * <p>{@code tangentBlockingNormals} remain the canonical, deduplicated
 * gravity-tangent normals that constrained the accepted route (walls and
 * ceilings only; walkable floor normals are never exported here) for
 * diagnostics. They are diagnostic provenance only and are never production
 * velocity authority. {@code blockedUp} records an upward leg's true CEILING
 * impact; losing an up component to WALL projection is not above-collision.
 * An empty {@code contactVelocityConstraints} list means no active endpoint
 * planes for the requested direction; it does not authorize other directions.
 * {@code supportFollowRise} is the cumulative gravity-up correction accepted
 * by the route while walking on walkable support; it is never
 * {@code stepHeight}. {@code supportContact} is the final-body support normal
 * and contact-point surface velocity used by the resting-contact policy. It
 * never retains the obstacle.</p>
 */
public record GravityMoveResult(
        Vector3d requestedMovement,
        Vector3d resolvedMovement,
        Vector3d recoveryMovement,
        Vector3d locomotionMovement,
        boolean blockedDown,
        boolean blockedUp,
        boolean blockedTangent,

        /*
         * Gameplay/path evidence: a valid gravity-down collision occurred during
         * this move. This does not imply that endpoint support still exists.
         */
        boolean supportingContactDuringMove,

        /*
         * Same-operation physical support selected at the gravity-down movement
         * leg. Unlike supportContact, this contact may survive only as movement
         * evidence after the later tangent leg has left the surface.
         *
         * This exists specifically so native landing/material callbacks can use
         * the real contacted surface normal instead of reconstructing one from
         * GravityFrame. It is never cross-tick support authority.
         */
        Optional<GravitySupportContact> movementSupportContact,

        boolean terminalGrounded,
        boolean walkableGround,
        Optional<BlockPos> supportBlock,
        double stepHeight,
        boolean indeterminate,
        List<Vector3d> tangentBlockingNormals,
        double supportFollowRise,
        GravityFrame frame,

        /*
         * Final-body physical support only.
         */
        Optional<GravitySupportContact> supportContact,

        List<ContactConstraintProjector.Constraint> contactVelocityConstraints,
        MovementIndeterminateReason indeterminateReason
) {
    private static final double PROVENANCE_EPSILON = 1.0E-6D;

    /** Maximum gravity-up component of an exported canonical tangent normal. */
    public static final double TANGENT_NORMAL_MAX_UP_DOT = 1.0E-9D;
    
    public GravityMoveResult {
        Objects.requireNonNull(indeterminateReason, "indeterminateReason");
        if (indeterminate != (indeterminateReason != MovementIndeterminateReason.NONE)) {
            throw new IllegalArgumentException("indeterminate flag and provenance disagree");
        }
        Objects.requireNonNull(requestedMovement, "requestedMovement");
        Objects.requireNonNull(resolvedMovement, "resolvedMovement");
        Objects.requireNonNull(recoveryMovement, "recoveryMovement");
        Objects.requireNonNull(locomotionMovement, "locomotionMovement");
        Objects.requireNonNull(supportBlock, "supportBlock");
        Objects.requireNonNull(
                movementSupportContact,
                "movementSupportContact"
        );
        Objects.requireNonNull(
                tangentBlockingNormals,
                "tangentBlockingNormals"
        );
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(supportContact, "supportContact");
        Objects.requireNonNull(
                contactVelocityConstraints,
                "contactVelocityConstraints"
        );

        supportBlock =
                supportBlock.map(BlockPos::immutable);

        movementSupportContact =
                movementSupportContact.map(Objects::requireNonNull);

        supportContact =
                supportContact.map(Objects::requireNonNull);
        /*
         * supportContact is physical endpoint identity.
         *
         * supportBlock is deliberately narrower: it is the Vanilla-compatible
         * standing block and therefore exists only for stable terminal ground.
         */
        if (supportBlock.isPresent() && !terminalGrounded) {
            throw new IllegalArgumentException(
                    "supportBlock requires stable terminal ground"
            );
        }

        if (supportBlock.isPresent() && supportContact.isEmpty()) {
            throw new IllegalArgumentException(
                    "supportBlock requires a physical support contact"
            );
        }

        if ((supportContact.isPresent() || supportBlock.isPresent())
                && indeterminate) {
            throw new IllegalArgumentException(
                    "supportContact requires a determinate result"
            );
        }
        if (indeterminate && (supportingContactDuringMove || terminalGrounded)) {
            throw new IllegalArgumentException("indeterminate result cannot publish ground facts");
        }
        if (supportingContactDuringMove && !blockedDown) {
            throw new IllegalArgumentException("supporting movement contact requires blocked down motion");
        }
        /*
         * This contact comes from the gravity-down leg. It may be non-walkable and
         * need not survive as terminal support, but it cannot exist if no down motion
         * was actually blocked.
         */
        if (movementSupportContact.isPresent() && !blockedDown) {
            throw new IllegalArgumentException(
                    "movementSupportContact requires blockedDown"
            );
        }

        if (movementSupportContact.isPresent() && indeterminate) {
            throw new IllegalArgumentException(
                    "indeterminate result cannot publish movement support contact"
            );
        }
        if (walkableGround && !terminalGrounded) {
            throw new IllegalArgumentException(
                    "walkableGround requires terminalGrounded stable support"
            );
        }

        List<Vector3d> canonicalTangents =
                new java.util.ArrayList<>();

        Vector3d frameUp =
                frame.orientation().axisY(new Vector3d());

        for (Vector3d normal : tangentBlockingNormals) {
            requireNormalized(normal);

            if (Math.abs(normal.dot(frameUp))
                    > TANGENT_NORMAL_MAX_UP_DOT) {
                throw new IllegalArgumentException(
                        "tangent blocking normal must be gravity-tangent: "
                                + "normal=" + normal
                                + ", frameUp=" + frameUp
                                + ", dot=" + normal.dot(frameUp)
                );
            }

            boolean duplicate = false;

            for (Vector3d existing : canonicalTangents) {
                if (CollisionTolerances.sameConstraintIdentity(
                        existing,
                        normal
                )) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) {
                canonicalTangents.add(
                        new Vector3d(normal)
                );
            }
        }

        tangentBlockingNormals =
                List.copyOf(canonicalTangents);

        /*
         * Exported velocity constraints are merged deterministically:
         * same-facing near-parallel planes keep the stricter (larger)
         * minimumDot and the list is sorted so contact enumeration order can
         * never change downstream physics. Each element is an immutable
         * normalized constraint snapshot.
         */
        contactVelocityConstraints =
                CurrentContactConstraintBuilder.merge(
                        contactVelocityConstraints
                );

        requireFinite(
                requestedMovement,
                "requestedMovement"
        );
        requireFinite(
                resolvedMovement,
                "resolvedMovement"
        );
        requireFinite(
                recoveryMovement,
                "recoveryMovement"
        );
        requireFinite(
                locomotionMovement,
                "locomotionMovement"
        );

        if (!Double.isFinite(stepHeight)
                || stepHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "stepHeight must be finite and non-negative: "
                            + stepHeight
            );
        }

        if (!Double.isFinite(supportFollowRise)
                || supportFollowRise < 0.0D) {
            throw new IllegalArgumentException(
                    "supportFollowRise must be finite and non-negative: "
                            + supportFollowRise
            );
        }

        double provenanceError =
                new Vector3d(recoveryMovement)
                        .add(locomotionMovement)
                        .sub(resolvedMovement)
                        .lengthSquared();

        if (provenanceError
                > PROVENANCE_EPSILON
                * PROVENANCE_EPSILON) {
            throw new IllegalArgumentException(
                    "resolvedMovement must equal recoveryMovement + "
                            + "locomotionMovement: resolved="
                            + resolvedMovement
                            + ", recovery="
                            + recoveryMovement
                            + ", locomotion="
                            + locomotionMovement
                            + ", error="
                            + Math.sqrt(provenanceError)
            );
        }

        /*
         * Vector3d is mutable. A completed move result must own its complete
         * vector snapshot rather than retaining solver-local vectors.
         */
        requestedMovement =
                new Vector3d(requestedMovement);
        resolvedMovement =
                new Vector3d(resolvedMovement);
        recoveryMovement =
                new Vector3d(recoveryMovement);
        locomotionMovement =
                new Vector3d(locomotionMovement);

    }

    /** Physical endpoint identity does not grant grounding or platform traction. */
    public boolean physicalSupport() { return supportContact.isPresent(); }
    public boolean tractionEligible() { return !indeterminate && terminalGrounded; }

    /** Gameplay ground continuity lasts for this movement commit, never another tick. */
    public boolean gameplayGrounded() {
        return !indeterminate && (terminalGrounded || supportingContactDuringMove);
    }

    @Override
    public Vector3d requestedMovement() {
        return new Vector3d(requestedMovement);
    }

    @Override
    public Vector3d resolvedMovement() {
        return new Vector3d(resolvedMovement);
    }

    @Override
    public Vector3d recoveryMovement() {
        return new Vector3d(recoveryMovement);
    }

    @Override
    public Vector3d locomotionMovement() {
        return new Vector3d(locomotionMovement);
    }

    @Override
    public List<Vector3d> tangentBlockingNormals() {
        return tangentBlockingNormals.stream()
                .map(Vector3d::new)
                .toList();
    }

    /**
     * The detached requested-direction velocity constraints of this move.
     *
     * <p>The returned list is immutable and each element is an immutable
     * normalized constraint snapshot
     * ({@code constraintNormal dot velocity >= minimumDot}).
     * An empty list means no active planes for this movement's requested
     * direction. Later velocities require finite revalidation.</p>
     */
    public List<ContactConstraintProjector.Constraint>
    contactVelocityConstraints() {
        return contactVelocityConstraints;
    }

    public Vector3d down() {
        return frame.orientation()
                .axisY(new Vector3d())
                .negate();
    }

    /**
     * A determinate custom collision result that may drive gameplay consumers.
     * A degraded completion still carries its proven displacement but never
     * publishes derived gameplay facts.
     */
    public boolean isAuthoritative() {
        return !indeterminate();
    }

    /**
     * Authoritative Vanilla-compatible stable terminal support block.
     *
     * <p>A physical steep contact may retain supportContact identity for contact
     * response, but it is not an on-ground block and is therefore never returned
     * here.</p>
     */
    public Optional<BlockPos> authoritativeSupport() {
        if (!isAuthoritative() || !terminalGrounded()) {
            return Optional.empty();
        }

        return supportBlock();
    }

    /**
     * Authoritative landing: the solve converged, gravity-down movement was
     * physically clipped, and walkable ground was actually established.
     * Traction-only (steep, non-walkable) support is a physical landing, not
     * a walkable landing, so it never sets this flag.
     */
    public boolean landedOnWalkableSupport() {
        return isAuthoritative()
                && supportingContactDuringMove()
                && walkableGround();
    }

    /**
     * Authoritative gameplay landing: the solve converged and gravity-down
     * movement was blocked with stable finite-foot support. Steep normal
     * reaction alone does not terminate Vanilla fall accumulation.
     */
    public boolean landedOnStableSupport() {
        return isAuthoritative()
                && supportingContactDuringMove();
    }

    /**
     * Gravity-up component of the accepted locomotion route only.
     *
     * <p>Vanilla 21.1.249 feeds {@code Entity.move}'s resolved world-Y into
     * {@code checkFallDamage}. Recovery is depenetration, not locomotion, so
     * it must never contribute to fall-distance/fall-damage accumulation.</p>
     */
    public double fallDistanceVertical() {
        return locomotionMovement().dot(
                MinecraftGeometryAdapter.toJoml(
                        frame.up(), new Vector3d()));
    }

    private static void requireFinite(
        Vector3d vector,
        String name
    ) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }

    private static void requireNormalized(
            Vector3d normal
    ) {
        if (!Double.isFinite(normal.x)
                || !Double.isFinite(normal.y)
                || !Double.isFinite(normal.z)
                || Math.abs(
                normal.lengthSquared() - 1.0D
        ) > 1.0E-6D) {
            throw new IllegalArgumentException(
                    "tangent blocking normal must be finite and normalized: "
                            + normal
            );
        }
    }

}