package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * Operation-local step-start support re-verification.
 *
 * <p>Re-verifies support on the current body when the previous completed
 * movement has no usable support publication. A soft-position hint is verified
 * on the installed body before geometry planning; control can query again on
 * the prepared body after the transition.</p>
 *
 * <p>The query reuses the caller's captured scene, query context and obstacle
 * time and asks the real {@link GravityGroundProbe} entry point, so a joint
 * {@link GravitySupportContact.SupportGeometryKind#MANIFOLD} result is
 * obtained by exactly the same classifier the movement route uses. It performs
 * no position change, no second world capture, no gravity resample and no
 * nested platform move.</p>
 */
public final class StepStartSupportQuery {
    private StepStartSupportQuery() {}

    /**
     * Operation-local step-start support evidence.
     *
     * @param support       a re-validated support plane, or empty when the body
     *                      is provably unsupported
     * @param indeterminate true when the solve could not prove either outcome
     */
    public record Result(
            Optional<RestingContactSnapshot> support,
            boolean indeterminate
    ) {
        public static final Result UNSUPPORTED =
                new Result(Optional.empty(), false);
        public static final Result UNKNOWN =
                new Result(Optional.empty(), true);

        public Result {
            Objects.requireNonNull(support, "support");
            if (indeterminate && support.isPresent()) {
                throw new IllegalArgumentException(
                        "an indeterminate step-start support result carries no support");
            }
        }

        public static Result supported(RestingContactSnapshot snapshot) {
            return new Result(
                    Optional.of(
                            Objects.requireNonNull(snapshot, "snapshot")),
                    false
            );
        }
    }

    /**
     * One step-start support re-verification against an already frozen scene.
     *
     * @param body                current installed or prepared body
     * @param frame               reference frame owning that body
     * @param scene               the operation's frozen collision scene
     * @param queryContext        the operation's shared geometry context
     * @param velocity            the entity's current world velocity
     * @param gameTick            the scene's logical game tick
     * @param continuityCandidate a previous support snapshot usable as a
     *                            one-shot wider reacquisition hint, or
     *                            {@code null}
     */
    public static Result query(
            CollisionBody body,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext queryContext,
            Vec3d velocity,
            long gameTick,
            RestingContactSnapshot continuityCandidate
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(velocity, "velocity");

        /*
         * A soft external correction may use the previous exact face as a
         * one-shot wider reacquisition hint.
         *
         * It still queries the CURRENT body against the CURRENT frozen scene.
         * The historical snapshot never directly grants ground.
         */
        if (continuityCandidate != null
                && continuityCandidate.faceIdentity() != null
                && continuityCandidate.usableAtStepStart(gameTick)) {

            FeetSupportQuery.Result continuityProbe;

            try {
                continuityProbe =
                        FeetSupportQuery.query(
                                body,
                                frame,
                                scene,
                                0.0D,
                                GravityGroundProbe
                                        .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE,
                                continuityCandidate.faceIdentity(),
                                queryContext
                        );
            } catch (CollisionSceneCoverageException unavailable) {
                return Result.UNKNOWN;
            }

            if (continuityProbe.indeterminate()) {
                return Result.UNKNOWN;
            }

            FeetSupportQuery.Candidate reacquired =
                    continuityProbe.candidates()
                            .stream()
                            .filter(candidate ->
                                    Objects.equals(
                                            candidate.identity(),
                                            continuityCandidate
                                                    .faceIdentity()
                                    ))
                            .filter(candidate ->
                                    candidate.upDot()
                                            >= TerrainTraversalPolicy
                                            .MIN_CONTINUOUS_SUPPORT_UP_DOT)
                            .findFirst()
                            .orElse(null);

            if (reacquired != null) {
                GravitySupportContact contact = reacquired.support();

                if (separatingFromSupport(velocity, contact)) {
                    return Result.UNSUPPORTED;
                }

                return Result.supported(
                        snapshotFromSupport(
                                contact,
                                Optional.ofNullable(
                                        reacquired.identity().block()
                                ),
                                gameTick
                        )
                );
            }
        }

        /*
         * No valid continuity candidate: ordinary fresh ground acquisition
         * remains strict and continues to use GravityGroundProbe.PROBE_DISTANCE.
         */
        GravityGroundProbe.Result probe =
                GravityGroundProbe.probe(
                        body,
                        frame,
                        scene,
                        0.0D,
                        queryContext
                );

        if (probe.indeterminate()) {
            return Result.UNKNOWN;
        }

        if (!probe.stableGround()) {
            return Result.UNSUPPORTED;
        }

        GravitySupportContact contact =
                probe.supportContact()
                        .orElseThrow();

        if (separatingFromSupport(velocity, contact)) {
            return Result.UNSUPPORTED;
        }

        return Result.supported(
                snapshotFromSupport(
                        contact,
                        Optional.ofNullable(probe.supportBlock()),
                        gameTick
                )
        );
    }

    /**
     * A body already moving away from a support plane must not acquire ground
     * from it, even when the geometric contact still exists.
     */
    private static boolean separatingFromSupport(
            Vec3d velocity,
            GravitySupportContact contact
    ) {
        Vec3d separation =
                velocity.subtract(contact.surfaceVelocity());

        return separation.dot(contact.normal())
                > CollisionTolerances.ENTERING_PLANE_EPSILON;
    }

    private static RestingContactSnapshot snapshotFromSupport(
            GravitySupportContact contact,
            Optional<CellPos> supportBlock,
            long gameTick
    ) {
        return new RestingContactSnapshot(
                contact.normal(),
                contact.surfaceVelocity(),
                contact.contactPoint(),
                contact.geometryKind(),
                supportBlock,
                gameTick,
                contact.faceIdentity()
        );
    }
}
