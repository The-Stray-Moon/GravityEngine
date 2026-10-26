package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import java.util.Objects;
import java.util.Optional;

/** Endpoint support selection over real feet faces or a static multi-contact load-bearing cone.
 * Hard OBB contacts belong to CurrentContactQuery, independently of this result.
 * Result.stableGround denotes stable terminal support only; movement integration owns onGround.
 */
public final class GravityGroundProbe {
    public static final double PROBE_DISTANCE =
            Math.max(
                    CollisionTolerances.CONTACT_SKIN * 8.0D,
                    CollisionTolerances.CONTACT_SLOP * 16.0D
            );

    /**
     * Reacquisition band used only when a previous authoritative support face
     * already exists.
     *
     * Fresh ground acquisition must continue to use PROBE_DISTANCE.
     */
    public static final double SUPPORT_CONTINUITY_REACQUIRE_DISTANCE =
            Math.max(
                    PROBE_DISTANCE * 16.0D,
                    CollisionTolerances.CONTACT_SKIN * 128.0D
            );

    private GravityGroundProbe() {}

    public enum SupportKind {
        CONTINUOUS_SUPPORT,
        PHYSICAL_SUPPORT,
        MULTI_CONTACT_SUPPORT,
        AIRBORNE,
        INDETERMINATE
    }

    public record Result(
            SupportKind kind,
            boolean stableGround,
            boolean tractionEligible,
            boolean walkableGround,
            CellPos supportBlock,
            boolean indeterminate,
            boolean dependsOnMovingObstacle,
            Optional<GravitySupportContact> supportContact
    ) {
        public Result {
            Objects.requireNonNull(
                    kind,
                    "kind"
            );
            Objects.requireNonNull(supportContact, "supportContact");
            supportContact = supportContact.map(Objects::requireNonNull);
            if ((stableGround || tractionEligible || walkableGround) && supportContact.isEmpty()) {
                throw new IllegalArgumentException("stable ground requires current physical support evidence");
            }
            if (tractionEligible && !stableGround) {
                throw new IllegalArgumentException("traction requires stable ground");
            }
            if (walkableGround
                    && kind != SupportKind.CONTINUOUS_SUPPORT) {
                throw new IllegalArgumentException(
                        "walkable ground requires a selected real face: "
                                + "support identity: kind=" + kind);
            }
        }

        public boolean physicalSupport() { return supportContact.isPresent(); }

        public static final Result AIRBORNE =
                new Result(
                        SupportKind.AIRBORNE,
                        false,
                        false,
                        false,
                        null,
                        false,
                        false,
                        Optional.empty()
                );

        private static Result indeterminate(
                boolean movingObstacle
        ) {
            return new Result(
                    SupportKind.INDETERMINATE,
                    false,
                    false,
                    false,
                    null,
                    true,
                    movingObstacle,
                    Optional.empty()
            );
        }
    }

    public static Result probe(
            CollisionBody body,
            GravityFrame frame,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        var result = FeetSupportQuery.query(body, frame, scene, obstacleTimeTicks,
                0.0D, context.preferredSupportFace(), context);
        if (result.indeterminate()) return Result.indeterminate(false);
        var selected = result.selected();
        if (selected != null
                && selected.upDot() >= TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT) {
            var support = selected.support();
            return new Result(SupportKind.CONTINUOUS_SUPPORT, true, true, true,
                    selected.identity().block(), false,
                    support.surfaceVelocity().lengthSquared() > 0, Optional.of(support));
        }
        var manifold = MultiContactSupport.probe(body, frame, scene, obstacleTimeTicks, context);
        if (manifold.support().isPresent()) {
            return new Result(SupportKind.MULTI_CONTACT_SUPPORT, true, true, false,
                    manifold.block(), false, false, manifold.support());
        }
        // Uncertain multi-contact support cannot authorize grounded/jump/step.
        // A proven single steep contact still keeps its physical material witness.
        if (selected == null) return manifold.indeterminate()
                ? Result.indeterminate(false) : Result.AIRBORNE;
        var support = selected.support();
        return new Result(SupportKind.PHYSICAL_SUPPORT, false, false, false,
                selected.identity().block(), false,
                support.surfaceVelocity().lengthSquared() > 0, Optional.of(support));
    }
}
