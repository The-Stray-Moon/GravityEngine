package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityAccelerationResolver;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState.CollisionOperationContext;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
/**
 * One gravity movement operation transaction.
 *
 * <p>An operation freezes one authoritative {@link GravityFrame}, one
 * collision scene and one simulation interval for the whole logical step.
 * Nested operations inherit the outer operation's frame/scene/interval
 * instead of capturing a second one, and closing the outer operation
 * publishes the transition that followed it.</p>
 */
public final class GravityOperation implements AutoCloseable {
    private final Entity entity;
    private final EntityGravityComponent component;
    private final GravityOperationType type;
    private final GravitySample sample;
    private final GravityRuntimeState.MoveScope operation;
    private final boolean outermost;

    /**
     * Endpoint-support evidence for character-control resolution in this
     * operation.
     *
     * Geometry preparation is allowed to invalidate the runtime's live endpoint
     * slot. PRESERVE_SUPPORT, however, has independently revalidated the previous
     * finite support face, so that transition may carry the pre-preparation
     * endpoint into this logical step.
     */
    private final Optional<Boolean> controlTerminalSupportAtStepStart;

    private boolean closed;

    private GravityOperation(
            Entity e,
            EntityGravityComponent c,
            GravityOperationType t,
            GravitySample s,
            GravityRuntimeState.MoveScope sc,
            boolean o,
            Optional<Boolean> controlTerminalSupportAtStepStart
    ) {
        this.entity = Objects.requireNonNull(e);
        this.component = Objects.requireNonNull(c);
        this.type = Objects.requireNonNull(t);
        this.sample = Objects.requireNonNull(s);
        this.operation = Objects.requireNonNull(sc);
        this.outermost = o;
        this.controlTerminalSupportAtStepStart =
                Objects.requireNonNull(
                        controlTerminalSupportAtStepStart,
                        "controlTerminalSupportAtStepStart"
                );
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint) {
        return open(e, t, samplePoint, 1.0D, defaultDomain(e));
    }

    /**
     * Opens an operation with an explicit simulation interval in ticks.
     * Ordinary vanilla travel uses {@code 1.0}; sub-tick or interval-spanning
     * operations supply their real interval.
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks
    ) {
        return open(e, t, samplePoint, intervalTicks, defaultDomain(e));
    }

    /**
     * Opens an outer operation with an explicit conservative capture domain.
     * The scene is captured from the Minecraft world exactly once before pose
     * preparation or any collision solve.
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomain domain
    ) {
        return open(e, t, samplePoint, intervalTicks, domain,
                t == GravityOperationType.LOAD_RESTORE
                        ? GravityGeometryTransitionService
                        .PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : GravityGeometryTransitionService
                        .PositionAuthorityPolicy.OPERATION_MAY_REANCHOR);
    }

    /**
     * Opens an outer operation whose translation proposal already has an
     * externally owned position anchor.
     *
     * <p>{@link GravityGeometryTransitionService.PositionAuthorityPolicy} is
     * supplied by every owner that translates the entity by a displacement
     * computed against a previously captured anchor - most importantly
     * Vanilla's packet loop, whose pre-move baseline, {@code lastGood}
     * bookkeeping and correction destination all assume the anchor it
     * captured. Such an operation must not re-anchor the pose; it still owns
     * one frozen frame/scene and its normal collision solve.</p>
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomain domain,
            GravityGeometryTransitionService.PositionAuthorityPolicy positionAuthority
    ) {
        Objects.requireNonNull(domain, "domain");
        return open(e, t, samplePoint, intervalTicks, (sample, frame) -> domain,
                positionAuthority);
    }

    @FunctionalInterface
    public interface CollisionCaptureDomainResolver {
        CollisionCaptureDomain resolve(GravitySample sample, GravityFrame proposedFrame);
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint,
                                                                                                     double intervalTicks, CollisionCaptureDomainResolver domainResolver) {
        return open(e, t, samplePoint, intervalTicks, domainResolver,
                t == GravityOperationType.LOAD_RESTORE
                        ? GravityGeometryTransitionService
                        .PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : GravityGeometryTransitionService
                        .PositionAuthorityPolicy.OPERATION_MAY_REANCHOR);
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint,
                                                                                                     double intervalTicks, CollisionCaptureDomainResolver domainResolver,
                                                                                                     GravityGeometryTransitionService.PositionAuthorityPolicy positionAuthority) {
        Objects.requireNonNull(e); Objects.requireNonNull(t); Objects.requireNonNull(samplePoint);
        Objects.requireNonNull(domainResolver, "domainResolver");
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be positive and finite: " + intervalTicks
            );
        }
        var c = GravityEntityAccess.cast(e).gravityengine$gravityComponent();
        var rt = c.runtime();

        RestingContactSnapshot softPositionSupportCandidate =
                rt.consumeSoftPositionSupportRevalidation()
                        .orElse(null);

        /*
         * Capture the completed movement fact before operation-frame preparation is
         * allowed to revise the installed collision axis and invalidate the live
         * endpoint slot.
         *
         * This value is immutable evidence belonging to the boundary between the
         * previous completed movement and the current logical step.
         */
        Optional<Boolean> completedEndpointBeforePreparation =
                rt.completedEndpointGround()
                        .map(
                                GravityRuntimeState.CompletedEndpointGround
                                        ::terminalSupported
                        );
        Optional<RestingContactSnapshot> completedSupportBeforePreparation =
                Optional.ofNullable(rt.restingContactSnapshot())
                        .filter(snapshot ->
                                snapshot.usableAtStepStart(
                                        e.level().getGameTime()
                                ));

        boolean customBody = c.appliedPlan().usesCustomBody()
                || cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy
                .usesCustomBody(e);

        // Nested operation: reuse existing frame
        if (rt.isInMove()) {
            // A shared CollisionScene corresponds to exactly one
            // KinematicStepContext; the nested operation must inherit the
            // parent's gameTick/intervalTicks/sceneRevision and cannot
            // silently substitute a different interval.
            CollisionOperationContext parentOperation =
                    rt.collisionOperation();
            if (parentOperation == null) {
                throw new IllegalStateException(
                        "nested movement transaction has no parent collision operation"
                );
            }
            requireNestedIntervalMatches(
                    parentOperation.time(), intervalTicks
            );
            if (customBody && rt.installedCollisionUp() == null || customBody && rt.installedCollisionUp().distanceToSqr(rt.activeFrame().up()) > 1e-20) {
                throw new IllegalStateException(
                        "nested gravity operation frame does not own live geometry"
                );
            }
            if (customBody) {
                GravityEntityGeometry.requireGeometryMatchesFrame(
                        e, rt.activeFrame(), "nested operation start"
                );
            }
            var scope = rt.openMove(
                    rt.activeFrame(),
                    rt.activeSample(),
                    e.level().getGameTime(),
                    t
            );
            return new GravityOperation(
                    e,
                    c,
                    t,
                    rt.activeSample(),
                    scope,
                    false,
                    customBody && GravityInfluencePolicy.requiresReferenceGeometry(rt.activeFrame())
                            ? completedEndpointBeforePreparation : Optional.empty()
            );
        }

        if (!customBody) {
            return openVanillaBodyOperation(
                    e, c, t, samplePoint, intervalTicks, domainResolver
            );
        }

        /*
         * Completed history seeds tangent continuity only. Physical sampling
         * and fallback begin from the frame that actually owns live geometry.
         */
        var installedFrame = GravityGeometryTransitionService.installedFallback(rt);
        GravityEntityGeometry.requireGeometryMatchesFrame(
                e, installedFrame, "outer operation start"
        );

        Vec3 exactSamplePoint = GravityEntityGeometry.bodyCenter(
                e, installedFrame
        );

        /*
         * The committed application plan is the sole acceleration authority.
         *
         * FIELD  -> sample the composed world field.
         * DIRECT -> sample the committed direct GravityState.
         * NONE   -> zero sample.
         *
         * GravityOperation must never directly choose GravityFieldService, otherwise
         * DIRECT character operations would silently fall back to FIELD authority.
         */
        long gameTick = e.level().getGameTime();

        AccelerationQuery accelerationQuery =
                new AccelerationQuery(
                        e,
                        exactSamplePoint,
                        e.getDeltaMovement(),
                        gameTick,
                        intervalTicks
                );

        /*
         * One environmental evidence resolution for the whole outer
         * operation.  FIELD authority performs exactly one composed-field
         * query here; acceleration and reference-orientation semantics are
         * pure interpretations of the same immutable evidence and never
         * trigger a second world sample.
         */
        GravitySample evidence =
                GravityAccelerationResolver
                        .sampleCharacterOperationEvidence(
                                accelerationQuery,
                                c.appliedPlan()
                        );
        GravitySample accelerationSample =
                GravityAccelerationResolver.characterAcceleration(
                        evidence,
                        c.appliedPlan()
                );

        var previousFrameForContinuity = rt.lastCompletedFrame();
        var proposedFrame = GravityFrame.fromEnvironmentalEvidence(
                evidence.samplePoint(),
                evidence.accelerationVector(),
                c.appliedState().down(),
                previousFrameForContinuity
        );

        /*
         * One operation-local scene (one CollisionContext epoch, one block
         * cache, one border snapshot, one work tracker) is created before pose
         * preparation. Frame preparation and the subsequent movement solve
         * consume this same scene; no second one-shot world query is created.
         */
        long sceneRevision =
                rt.nextSceneRevision();

        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                );

        KinematicStepContext time =
                new KinematicStepContext(
                        gameTick,
                        intervalTicks,
                        sceneRevision
                );

        CollisionScene scene =
                MinecraftCollisionSceneCapture.capture(
                        e,
                        domainResolver.resolve(
                                accelerationSample,
                                proposedFrame),
                        gameTick,
                        sceneRevision,
                        time,
                        tracker
                );

        /*
         * Geometry preparation and the subsequent movement solve share both the
         * captured scene and query contexts. Hard collision and support classification
         * still have distinct trackers inside this context.
         */
        ObbQueryContext queryContext =
                new ObbQueryContext();

        queryContext.setWorkTracker(
                tracker
        );

        var prepResult =
                GravityGeometryTransitionService
                        .prepareOperationFrame(
                                e,
                                proposedFrame,
                                scene,
                                positionAuthority,
                                queryContext
                        );

        var selectedFrame =
                prepResult.selectedFrame();

        if (rt.installedCollisionUp() == null || rt.installedCollisionUp().distanceToSqr(selectedFrame.up()) > 1e-20) {
            throw new IllegalStateException(
                    "selected operation frame does not own installed geometry: selected="
                            + selectedFrame
                            + ", installed="
                            + rt.geometryReferenceFrame()
            );
        }

        GravityEntityGeometry
                .requireGeometryMatchesFrame(
                        e,
                        selectedFrame,
                        "prepared operation start"
                );

        /*
         * For ordinary geometry changes the runtime's post-preparation endpoint is the
         * authority:
         *
         *   UNCHANGED / DEADBAND
         *       -> live endpoint was never invalidated.
         *
         *   DIRECT_AT_ANCHOR
         *       -> changed geometry invalidated the endpoint, so support is unknown.
         *
         * Only PRESERVE_SUPPORT is allowed to carry the pre-preparation endpoint
         * through the invalidation. That path has already:
         *
         *   - found the previous RestingContactSnapshot,
         *   - rejected discontinuous axis changes,
         *   - verified the original static support plane still exists,
         *   - queried feet support against the proposed body/frame,
         *   - required a walkable supporting face,
         *   - committed the support-preserving pose.
         *
         * Therefore carrying the previous completed endpoint here does not manufacture
         * ground from stale geometry.
         */
        Optional<Boolean> controlTerminalSupport =
                prepResult.transitionKind()
                        == GravityGeometryTransitionService
                        .GeometryTransitionKind.PRESERVE_SUPPORT
                        ? completedEndpointBeforePreparation
                        : rt.completedEndpointGround()
                        .map(
                                GravityRuntimeState.CompletedEndpointGround
                                        ::terminalSupported
                        );
        Optional<RestingContactSnapshot> controlSupport =
                controlTerminalSupport
                        .filter(Boolean::booleanValue)
                        .flatMap(ignored ->
                                completedSupportBeforePreparation);

        /*
         * A joint multi-contact support is deliberately never persisted as a
         * cross-tick resting plane, so an at-rest character carried by one has
         * no carried snapshot at all. Re-verify the CURRENT prepared body
         * against this operation's already captured scene instead of
         * inheriting the previous step's unsupported/true claim.
         *
         * The query runs before the move scope opens: it never changes P,
         * never captures a second world scene, never resamples gravity and
         * never calls Entity.move.
         */
        boolean exactContactOwner =
                GravityInfluencePolicy.requiresReferenceGeometry(selectedFrame);
        if (!exactContactOwner) {
            // CHARACTER may still use Vanilla AABB collision. Vanilla owns
            // grounded/traction for that route; empty means no override.
            controlSupport = Optional.empty();
            controlTerminalSupport = Optional.empty();
        } else if (controlSupport.isEmpty()) {
            StepStartSupport revalidated =
                    reverifyStepStartSupport(
                            e,
                            selectedFrame,
                            scene,
                            queryContext,
                            softPositionSupportCandidate
                    );
            if (revalidated.indeterminate()) {
                /*
                 * Support classification uncertainty is not a grounded claim:
                 * an unproven query never carries the previous step's `true`
                 * forward, while an already known unsupported endpoint stays
                 * known-unsupported.
                 */
                controlTerminalSupport =
                        controlTerminalSupport.filter(Boolean.FALSE::equals);
            } else {
                controlSupport = revalidated.support();
                controlTerminalSupport = Optional.of(controlSupport.isPresent());
            }
        }

        var scope =
                rt.openMove(
                        selectedFrame,
                        accelerationSample,
                        gameTick,
                        t
                );

        try {
            rt.setCollisionOperation(
                    new CollisionOperationContext(
                            selectedFrame,
                            time,
                            scene,
                            queryContext,
                            tracker
                    )
            );
            rt.setStepStartSupport(controlSupport.orElse(null));
        } catch (RuntimeException | Error failure) {
            /* No failure path may leave an open outer move scope behind. */
            scope.close();
            throw failure;
        }

        return new GravityOperation(
                e,
                c,
                t,
                accelerationSample,
                scope,
                true,
                controlTerminalSupport
        );
    }

    /**
     * Operation-local step-start control support re-verified on the already
     * prepared body.
     */
    private record StepStartSupport(Optional<RestingContactSnapshot> support,
                                    boolean indeterminate) {
        static final StepStartSupport UNSUPPORTED =
                new StepStartSupport(Optional.empty(), false);
        static final StepStartSupport UNKNOWN =
                new StepStartSupport(Optional.empty(), true);

        static StepStartSupport supported(RestingContactSnapshot snapshot) {
            return new StepStartSupport(Optional.of(snapshot), false);
        }
    }

    /**
     * Re-verifies step-start control support on the current prepared body when
     * the previous completed movement published no usable support snapshot.
     *
     * <p>The query reuses THIS operation's captured scene, query context and
     * obstacle time and asks the real {@link GravityGroundProbe} entry point,
     * so a joint {@link GravitySupportContact.SupportGeometryKind#MANIFOLD}
     * result is obtained by exactly the same classifier the movement route
     * uses. It performs no position change, no second world capture, no
     * gravity resample and no nested Entity.move.</p>
     */
    private static StepStartSupport reverifyStepStartSupport(
            Entity entity,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext queryContext,
            RestingContactSnapshot continuityCandidate
    ) {
        /*
         * A soft external correction may use the previous exact face as a
         * one-shot wider reacquisition hint.
         *
         * It still queries the CURRENT body against the CURRENT frozen scene.
         * The historical snapshot never directly grants ground.
         */
        if (continuityCandidate != null
                && continuityCandidate.faceIdentity() != null
                && continuityCandidate.usableAtStepStart(
                entity.level().getGameTime()
        )) {

            FeetSupportQuery.Result continuityProbe;

            try {
                continuityProbe =
                        FeetSupportQuery.query(
                                GravityEntityGeometry.body(
                                        entity,
                                        frame
                                ),
                                frame,
                                scene,
                                0.0D,
                                GravityGroundProbe
                                        .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE,
                                continuityCandidate.faceIdentity(),
                                queryContext
                        );
            } catch (CollisionSceneCoverageException unavailable) {
                return StepStartSupport.UNKNOWN;
            }

            if (continuityProbe.indeterminate()) {
                return StepStartSupport.UNKNOWN;
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
                GravitySupportContact contact =
                        reacquired.support();

                if (separatingFromSupport(
                        entity,
                        frame,
                        contact
                )) {
                    return StepStartSupport.UNSUPPORTED;
                }

                return StepStartSupport.supported(
                        snapshotFromSupport(
                                entity,
                                contact,
                                Optional.ofNullable(
                                        reacquired.identity()
                                                .block()
                                )
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
                        GravityEntityGeometry.body(
                                entity,
                                frame
                        ),
                        frame,
                        scene,
                        0.0D,
                        queryContext
                );

        if (probe.indeterminate()) {
            return StepStartSupport.UNKNOWN;
        }

        if (!probe.stableGround()) {
            return StepStartSupport.UNSUPPORTED;
        }

        GravitySupportContact contact =
                probe.supportContact()
                        .orElseThrow();

        if (separatingFromSupport(
                entity,
                frame,
                contact
        )) {
            return StepStartSupport.UNSUPPORTED;
        }

        return StepStartSupport.supported(
                snapshotFromSupport(
                        entity,
                        contact,
                        Optional.ofNullable(
                                probe.supportBlock()
                        )
                )
        );
    }

    private static boolean separatingFromSupport(
            Entity entity,
            GravityFrame frame,
            GravitySupportContact contact
    ) {
        Vec3 separation =
                entity.getDeltaMovement()
                        .subtract(
                                MinecraftGeometryAdapter.toMinecraft(
                                        contact.surfaceVelocity()
                                )
                        );

        return separation.dot(frame.up())
                > CollisionTolerances
                .ENTERING_PLANE_EPSILON;
    }

    private static RestingContactSnapshot snapshotFromSupport(
            Entity entity,
            GravitySupportContact contact,
            Optional<BlockPos> supportBlock
    ) {
        return new RestingContactSnapshot(
                MinecraftGeometryAdapter.toMinecraft(
                        contact.normal()
                ),
                MinecraftGeometryAdapter.toMinecraft(
                        contact.surfaceVelocity()
                ),
                MinecraftGeometryAdapter.toMinecraft(
                        contact.contactPoint()
                ),
                contact.geometryKind(),
                supportBlock,
                entity.level().getGameTime(),
                contact.faceIdentity()
        );
    }

    /** Opens a frozen-frame/scene operation without installing character geometry. */
    private static GravityOperation openVanillaBodyOperation(
            Entity entity,
            EntityGravityComponent component,
            GravityOperationType type,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomainResolver domainResolver
    ) {
        GravityRuntimeState runtime = component.runtime();
        Vec3 exactSamplePoint = entity.getBoundingBox().getCenter();
        long gameTick = entity.level().getGameTime();

        AccelerationQuery accelerationQuery =
                new AccelerationQuery(
                        entity,
                        exactSamplePoint,
                        entity.getDeltaMovement(),
                        gameTick,
                        intervalTicks
                );

        GravitySample authoritySample =
                GravityAccelerationResolver.sampleForPlan(
                        accelerationQuery,
                        component.appliedPlan()
                );
        GravityFrame selectedFrame = GravityFrame.fromEnvironmentalEvidence(
                authoritySample.samplePoint(),
                authoritySample.accelerationVector(),
                component.appliedState().down(),
                runtime.lastCompletedFrame()
        );

        long sceneRevision = runtime.nextSceneRevision();
        CollisionWorkTracker tracker = new CollisionWorkTracker(
                CollisionWorkBudget.defaults()
        );
        KinematicStepContext time = new KinematicStepContext(
                gameTick, intervalTicks, sceneRevision
        );
        CollisionScene scene = MinecraftCollisionSceneCapture.capture(
                entity, domainResolver.resolve(authoritySample, selectedFrame), gameTick, sceneRevision, time, tracker
        );
        GravityRuntimeState.MoveScope scope = runtime.openMove(
                selectedFrame, authoritySample, gameTick, type
        );
        runtime.setCollisionOperation(new CollisionOperationContext(
                selectedFrame, time, scene, new ObbQueryContext(), tracker
        ));
        return new GravityOperation(
                entity,
                component,
                type,
                authoritySample,
                scope,
                true,
                Optional.empty()
        );
    }

    /**
     * Nested operations share the outer operation's simulation interval.
     * Intervals come from API-defined durations, so an exact {@code double}
     * comparison is used rather than an epsilon that could mask a contract
     * mismatch.
     */

    public EntityGravityComponent component() { return component; }
    public static void requireNestedIntervalMatches(KinematicStepContext parentTime, double intervalTicks) {
        if (Double.compare(parentTime.intervalTicks(), intervalTicks) != 0) {
            throw new IllegalStateException("nested gravity operation interval mismatch: outer="
                    + parentTime.intervalTicks() + " nested=" + intervalTicks);
        }
    }
    public GravityOperationType type() { return type; }
    public GravitySample sample() { return sample; }
    public GravityFrame frame() { return operation.frame(); }
    public boolean outermost() { return outermost; }
    /**
     * Immutable terminal-support evidence that character control must consume for
     * this logical step.
     */
    public Optional<Boolean> controlTerminalSupportAtStepStart() {
        return controlTerminalSupportAtStepStart;
    }

    /**
     * Stable support plane carried from the previous completed movement
     * through this step's geometry preparation. Present only when the
     * previous endpoint was terminal-supported and its support witness
     * survived the transition.
     */
    public Optional<RestingContactSnapshot> controlSupportAtStepStart() {
        return operation.stepStartSupport();
    }

    /** Conservative domain around the current entity proxy with no requested translation. */
    public static CollisionCaptureDomain defaultDomain(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return CollisionCaptureDomain.forTranslation(
                MinecraftGeometryAdapter.toAabb3d(entity.getBoundingBox()),
                MinecraftGeometryAdapter.toJoml(
                        Vec3.ZERO, new org.joml.Vector3d()),
                entity.maxUpStep());
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException(
                    "GravityOperation already closed"
            );
        }

                closed = true;
                var runtime = component.runtime();
        Vec3 discontinuity = outermost ? runtime.discontinuityDestination() : null;
        Vec3 discontinuityVelocity = outermost
                ? runtime.consumePostDiscontinuityVelocity()
                : null;

        try {
            // Outer close publishes the reference frame. Vanilla owns position history;
            // rendering derives geometry from that history and current dimensions.
            operation.close();
            if (discontinuity != null) {
                EntityPositionIntegration.commitPositionDiscontinuity(entity, discontinuity);
                if (discontinuityVelocity != null) {
                    entity.setDeltaMovement(discontinuityVelocity);
                }
            }
        } finally {
            var rt = component.runtime();

            if (outermost
                    && !rt.isInMove()
                    && !rt.isApplyingGeometry()) {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyPending(entity);
            }
        }
    }
}