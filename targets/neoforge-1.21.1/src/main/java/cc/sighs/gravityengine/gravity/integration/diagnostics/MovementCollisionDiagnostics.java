package cc.sighs.gravityengine.gravity.integration.diagnostics;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static cc.sighs.gravityengine.gravity.debug.GravityDebugLog.*;

/**
 * Optional local observation of the real movement solve.
 *
 * <p>Observation lifetime is {@code Entity.move -> actual solve -> position
 * write}. Thread-local weak identity keys and explicit leave/discontinuity cleanup; values never
 * retain entities and nothing here feeds a movement decision. There is no
 * client/server pairing, chronology or transport: Vanilla owns the ordinary
 * position packet and GravityEngine does not validate it.</p>
 */
public final class MovementCollisionDiagnostics {
    private static final MovementDiagnosticSpans<Span> STATES = new MovementDiagnosticSpans<>();
    private MovementCollisionDiagnostics() {}

    public static Span begin(Entity entity, MoverType type, Vec3 wrapper) {
        if (!shouldLogMovement(entity)) return null;
        var token = STATES.begin(entity, sequence -> new Span(type, wrapper, sequence));
        Span span = token.value();
        span.token = token;
        return span;
    }

    public static void clear(Entity entity) { STATES.clear(entity); }

    /** Position discontinuities discard positional evidence, retaining the
     * monotonically increasing diagnostic sequence for this entity object. */
    public static void discontinuity(Entity entity) {
        STATES.discontinuity(entity);
    }

    /** Capture once at the ordinary solve boundary and carry through its result.
     * One solve owns a move's record; auxiliary/unpublished solves pass no span. */
    public static Span capture(Entity entity) {
        if (!shouldLogMovement(entity)) return null;
        var token = STATES.active(entity);
        return token == null || token.value().context != null ? null : token.value();
    }

    private static boolean writable(Entity entity, Span span) {
        return span != null && span.token.active(entity) && shouldLogMovement(entity);
    }

    /** Called with exactly the body, frame, scene and reconciled request about
     * to enter the production solver. Never reconstructs movement ownership. */
    public static void input(Entity entity, Span span, cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody body, KinematicMoveRequest request,
                             GravityFrame frame, CollisionScene scene, String route) {
        if (!writable(entity, span) || span.context != null) return;
        var owner = entity instanceof Player player
                ? cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player) : null;
        var attitude = owner == null ? null : owner.snapshot();
        long generation = entity.level().isClientSide()
                ? cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.client().map(cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.ClientGeneration::generation).orElse(-1L)
                : cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.server().generation();
        var counts = scene.diagnostics();
        String sceneSummary = "tick:" + scene.tick() + ",revision:" + scene.revision()
                + ",blocks:" + counts.blockPrimitiveCount() + ",dynamic:" + counts.dynamicSurfaceSnapshots()
                + ",spheres:" + counts.sourceSphereSnapshots() + ",interval:" + scene.time().intervalTicks();
        if (scene instanceof CapturedCollisionScene captured) {
            sceneSummary += ",geometryHash:" + Long.toUnsignedString(captured.diagnosticGeometryFingerprint(), 16)
                    + ",staticBounds:" + captured.domain().staticBounds().toString().replace(" ", "")
                    + ",dynamicBounds:" + captured.domain().dynamicEntityBounds().toString().replace(" ", "");
        }
        // Display bounds are never passed back to the physical solver. The exact
        // shape (including capsule axis/radius/segment) is recorded separately.
        sceneSummary += ",exactBody:" + body;
        var displayBounds = OrientedBox.axisAligned(body.enclosingAabb());
        span.context = new Sample.Context(span.sequence, entity.tickCount, entity.position(),
                span.wrapper,
                MinecraftMathAdapter.toMinecraft(request.actualMovement()),
                span.moverType.name(), request.channel().name(),
                request.ownership(), frame.down(),
                BodyOrientation3d.quaternion(frame.orientation()),
                frame.strength(),
                BodyOrientation3d.quaternion(displayBounds.orientation()),
                MinecraftMathAdapter.toMinecraft(body.center()),
                MinecraftMathAdapter.toMinecraft(displayBounds.halfExtents()),
                attitude == null ? -1 : attitude.state().revision(),
                attitude == null ? -1 : attitude.authoritativeRevision(), generation, entity.onGround(),
                entity.maxUpStep(), route, sceneSummary);
        logInput(entity, span.context);
    }

    private static void logInput(Entity entity, Sample.Context c) {
        String fields = "sequence=%s tick=%s positionAnchorBefore=%s wrapperMove=%s requestedMove=%s moverType=%s channel=%s "
                + "gravityDown=%s referenceOrientation=%s gravityStrength=%s displayBoundsOrientation=%s bodyCenter=%s displayBoundsHalfExtents=%s "
                + "frameRevision=unavailable attitudeRevision=%s authoritativeAttitudeRevision=%s configGeneration=%s "
                + "onGroundBefore=%s maxStepHeight=%s collisionRoute=%s scene=%s";
        Object[] values = {c.sequence(), c.tick(), exactVec(c.positionAnchorBefore()), exactVec(c.wrapperMove()),
                exactVec(c.requestedMove()), c.moverType(), c.channel(), exactVec(c.gravityDown()),
                quaternion(c.referenceOrientation()), c.gravityStrength(), quaternion(c.bodyOrientation()),
                exactVec(c.bodyCenter()), exactVec(c.bodyHalfExtents()), c.attitudeRevision(),
                c.authoritativeAttitudeRevision(), c.configGeneration(), c.onGroundBefore(), c.maxStepHeight(),
                c.collisionRoute(), c.scene()};
        movement(entity, family(entity), "move-input", fields, values);
        var o = c.ownership();
        movement(entity, family(entity), "ownership", "sequence=%s channel=%s selfWalk=%s externalPush=%s passive=%s supportMotion=%s",
                c.sequence(), c.channel(), exactVec(o == null ? null : o.selfWalk()), exactVec(o == null ? null : o.externalPush()),
                exactVec(o == null ? null : o.passive()), exactVec(o == null ? null : o.supportMotion()));
    }

    /** Vanilla's actual world-axis AABB, request and return value are useful
     * even when the other side chooses custom collision. No custom ownership
     * or step result is invented for a route that never produced those facts. */
    public static void vanillaInput(Entity entity, Span span, Vec3 requested) {
        if (!writable(entity, span) || span.context != null) return;
        var body = OrientedBox.axisAligned(MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox()));
        span.context = new Sample.Context(span.sequence, entity.tickCount, entity.position(),
                span.wrapper, requested, span.moverType.name(), "VANILLA", null, GravityFrame.DEFAULT.down(),
                Quatd.IDENTITY, Double.NaN, Quatd.IDENTITY,
                MinecraftMathAdapter.toMinecraft(body.center()), MinecraftMathAdapter.toMinecraft(body.halfExtents()),
                -1, -1, -1, entity.onGround(), entity.maxUpStep(), "VANILLA", "VANILLA_LIVE_WORLD");
        logInput(entity, span.context);
    }

    public static void vanillaResult(Entity entity, Span span, Vec3 resolved) {
        if (writable(entity, span) && span.context != null) span.vanillaResolved = resolved;
    }

    public static void result(Entity entity, Span span, GravityMoveResult result, ObbQueryContext context) {
        if (writable(entity, span) && span.context != null) {
            var trace = context.collisionTrace();
            span.collision = result;
            span.step = context.stepDecision();
            span.collisionTrace = trace;
            boolean zeroToi = trace != null && trace.sweeps().stream().anyMatch(sweep ->
                    sweep.earliestTimeOfImpact() == 0 && !sweep.contacts().isEmpty());
            if (entity.onGround() != result.gameplayGrounded() || zeroToi
                    || result.blockedDown() && !result.supportingContactDuringMove()) {
                var normals = trace == null ? List.of() : trace.sweeps().stream()
                        .flatMap(sweep -> sweep.contacts().stream())
                        .filter(contact -> contact.disposition().equals("EARLIEST_CONTACT"))
                        .limit(8).map(contact -> exactVec(contact.normal())).toList();
                movement(entity, "SUPPORT", "contact-state",
                        "up=%s requested=%s resolved=%s blockedDown=%s zeroTOI=%s SAT=%s "
                                + "physicalSupport=%s stableGround=%s gameplayGrounded=%s tractionEligible=%s "
                                + "terminal=%s supportingContactDuringMove=%s footFaces=%s truncated=%s",
                        exactVec(result.frame().up()), exactVec(MinecraftMathAdapter.toMinecraft(result.requestedMovement())),
                        exactVec(MinecraftMathAdapter.toMinecraft(result.resolvedMovement())),
                        result.blockedDown(), zeroToi, normals, result.physicalSupport(), result.terminalGrounded(),
                        result.gameplayGrounded(), result.tractionEligible(), support(result),
                        result.supportingContactDuringMove(), context.footFaceObservations(), context.footFaceObservationsTruncated() || trace != null && trace.truncated());
            }
        }
    }

    public static Object observed(Object value) { return value == null ? "unavailable" : value; }

    /** Observe the actual committed production response; never execute a solver here. */
    public static void recordVelocityResolution(Entity entity, GravityMoveResult result,
            ContactVelocityResolution resolution) {
        if (!shouldLogMovement(entity)) return;
        var token = STATES.active(entity);
        if (token == null) return;
        Span span = token.value();
        if (!token.active(entity) || span.collision != result) return;
        span.velocityResponseFallback = Boolean.TRUE.equals(span.velocityResponseFallback)
                || resolution.fallbackApplied();
        movement(entity, family(entity), "velocity-resolution",
                "sequence=%s desired=%s committed=%s fallbackApplied=%s infeasible=%s constraints=%s",
                span.sequence, exactVec(resolution.desiredVelocity()), exactVec(resolution.velocity()),
                resolution.fallbackApplied(), resolution.infeasible(), resolution.constraints());
    }

    private static String family(Entity entity) { return entity.level().isClientSide() ? "CLIENT" : "SERVER"; }

    public static String support(GravityMoveResult result) {
        String block = result.supportBlock().map(p -> "block:(" + p.x() + "," + p.y() + "," + p.z() + ")")
                .orElse("no_block");
        return result.supportContact().map(c -> block + ",kind:" + c.geometryKind()
                + ",normal:" + exactVec(MinecraftMathAdapter.toMinecraft(c.normal()))
                + ",point:" + exactVec(MinecraftMathAdapter.toMinecraft(c.contactPoint()))
                + ",velocity:" + exactVec(MinecraftMathAdapter.toMinecraft(c.surfaceVelocity())))
                .orElse("none");
    }

    /** Bounded diagnostic values from a real solve. No entity, scene, transport,
     * input execution, or gameplay authority. Quaternion access is defensive.
     * Null ownership/step/provenance fields mean Vanilla publishes no such fact. */
    private record Sample(Context context, Result result) {
        private record Context(
                long sequence,
                int tick,
                Vec3 positionAnchorBefore,
                Vec3 wrapperMove,
                Vec3 requestedMove,
                String moverType,
                String channel,
                cc.sighs.gravityengine.gravity.kinematic.OwnedMotion ownership,
                cc.sighs.gravityengine.api.math.Vec3d gravityDown,
                Quatd referenceOrientation,
                double gravityStrength,
                Quatd bodyOrientation,
                Vec3 bodyCenter,
                Vec3 bodyHalfExtents,
                long attitudeRevision,
                long authoritativeAttitudeRevision,
                long configGeneration,
                boolean onGroundBefore,
                double maxStepHeight,
                String collisionRoute,
                String scene
        ) {
            private Context {
                referenceOrientation = new Quatd(referenceOrientation);
                bodyOrientation = new Quatd(bodyOrientation);
            }

            @Override
            public Quatd referenceOrientation() {
                return new Quatd(referenceOrientation);
            }

            @Override
            public Quatd bodyOrientation() {
                return new Quatd(bodyOrientation);
            }
        }

        private record Result(
                Vec3 resolvedMove,
                Vec3 positionAnchorAfter,
                boolean blockedTangent,
                boolean blockedDown,
                boolean blockedUp,
                boolean onGroundAfter,
                Boolean stepped,
                Double stepHeight,
                String stepDecision,
                String support,
                Vec3 recoveryMove,
                Vec3 locomotionMove,
                Double supportFollowRise,
                boolean indeterminate,
                Boolean velocityResponseFallback
        ) {}
    }

    public static final class Span {
        private MovementDiagnosticSpans.Token<Span> token;
        private final MoverType moverType;
        private final Vec3 wrapper;
        private final long sequence;
        private Sample.Context context;
        private GravityMoveResult collision;
        private Boolean velocityResponseFallback;
        private Vec3 vanillaResolved;
        private ObbQueryContext.StepDecision step;
        private ObbQueryContext.CollisionTrace collisionTrace;
        private Span(MoverType type, Vec3 wrapper, long sequence) {
            this.moverType = type;
            this.wrapper = wrapper; this.sequence = sequence;
        }

        /** Retire an exceptional movement without publishing a partial result. */
        public void abort(Entity entity) { token.finish(entity); }

        /** After Vanilla position write and the real custom collision commit. */
        public void finish(Entity entity) {
            if (!token.finish(entity) || !shouldLogMovement(entity)) return;
            if (context == null || (collision == null && vanillaResolved == null)) {
                // noPhysics/short-circuit/unsupported route: never reuse a stale
                // endpoint as if this invocation had solved it.
                movement(entity, family(entity), "move-without-solve",
                        "sequence=%s moverType=%s wrapperMove=%s positionAnchorAfter=%s collisionRoute=not_observed",
                        sequence, moverType, exactVec(wrapper), exactVec(entity.position()));
                return;
            }
            var r = collision == null
                    ? new Sample.Result(vanillaResolved, entity.position(), entity.horizontalCollision,
                    entity.verticalCollisionBelow, entity.verticalCollision && !entity.verticalCollisionBelow,
                    entity.onGround(), null, null, "unavailable_vanilla", "unavailable_vanilla",
                    null, null, null, false, null)
                    : new Sample.Result(MinecraftMathAdapter.toMinecraft(collision.resolvedMovement()),
                    entity.position(), collision.blockedTangent(), collision.blockedDown(), collision.blockedUp(),
                    entity.onGround(), collision.stepHeight() > 0, collision.stepHeight(),
                    step == null ? "unavailable" : "attempted:" + step.attempted() + ",accepted:" + step.accepted()
                            + ",reason:" + step.reason() + ",baselineProgress:" + step.baselineProgress()
                            + ",candidateProgress:" + step.candidateProgress(),
                    support(collision), MinecraftMathAdapter.toMinecraft(collision.recoveryMovement()),
                    MinecraftMathAdapter.toMinecraft(collision.locomotionMovement()), collision.supportFollowRise(),
                    collision.indeterminate(), velocityResponseFallback);
            logCollisionTrace(entity, sequence, collisionTrace);
            movement(entity, family(entity), "move-result",
                    "sequence=%s positionAnchorBefore=%s requestedMove=%s resolvedMove=%s positionAnchorAfter=%s blockedTangent=%s "
                            + "blockedDown=%s blockedUp=%s onGroundAfter=%s stepped=%s stepHeight=%s stepDecision=%s "
                            + "support=%s collisionRoute=%s recoveryMove=%s locomotionMove=%s supportFollowRise=%s indeterminate=%s velocityResponseFallback=%s indeterminateReason=%s",
                    sequence, exactVec(context.positionAnchorBefore()), exactVec(context.requestedMove()), exactVec(r.resolvedMove()),
                    exactVec(r.positionAnchorAfter()), r.blockedTangent(), r.blockedDown(), r.blockedUp(), r.onGroundAfter(),
                    observed(r.stepped()), observed(r.stepHeight()), r.stepDecision(), r.support(), context.collisionRoute(),
                    exactVec(r.recoveryMove()), exactVec(r.locomotionMove()), observed(r.supportFollowRise()), r.indeterminate(), observed(r.velocityResponseFallback()),
                    collision == null ? "unavailable" : collision.indeterminateReason());
        }
    }

    private static void logCollisionTrace(
            Entity entity,
            long sequence,
            ObbQueryContext.CollisionTrace trace
    ) {
        if (trace == null) return;
        String detail = formatCollisionTrace(trace);
        movement(entity, family(entity), "collision-kernel",
                "sequence=%s %s", sequence, detail);
    }

    static String formatCollisionTrace(ObbQueryContext.CollisionTrace trace) {
        StringBuilder value = new StringBuilder(512);
        value.append("baselineObstacleCount=")
                .append(trace.baselineObstacleCount())
                .append(" baselineContacts=");
        appendContacts(value, trace.baselineContacts());
        value.append(" sweeps=[");
        for (int index = 0; index < trace.sweeps().size(); index++) {
            if (index > 0) value.append(',');
            ObbQueryContext.SweepObservation sweep = trace.sweeps().get(index);
            value.append("{ordinal=").append(sweep.ordinal())
                    .append(",phase=").append(sweep.phase())
                    .append(",bodyCenter=").append(exact(sweep.bodyCenter()))
                    .append(",movement=").append(exact(sweep.movement()))
                    .append(",windowStartTicks=").append(sweep.windowStartTicks())
                    .append(",windowDurationTicks=").append(sweep.windowDurationTicks())
                    .append(",obstacleCount=").append(sweep.obstacleCount())
                    .append(",earliestToi=").append(sweep.earliestTimeOfImpact())
                    .append(",overlapping=").append(sweep.overlapping())
                    .append(",indeterminate=").append(sweep.indeterminate())
                    .append(",reason=").append(sweep.indeterminateReason())
                    .append(",contacts=");
            appendContacts(value, sweep.contacts());
            value.append('}');
        }
        return value.append("] traceTruncated=")
                .append(trace.truncated()).toString();
    }

    private static void appendContacts(
            StringBuilder value,
            List<ObbQueryContext.ContactObservation> contacts
    ) {
        value.append('[');
        for (int index = 0; index < contacts.size(); index++) {
            if (index > 0) value.append(',');
            ObbQueryContext.ContactObservation contact = contacts.get(index);
            value.append("{disposition=").append(contact.disposition())
                    .append(",obstacle=").append(contact.obstacle())
                    .append(",normal=").append(exact(contact.normal()))
                    .append(",point=").append(exact(contact.point()))
                    .append(",penetration=").append(contact.penetration())
                    .append(",toi=").append(contact.timeOfImpact())
                    .append('}');
        }
        value.append(']');
    }

    private static String exact(cc.sighs.gravityengine.api.math.Vec3d vector) {
        return exactVec(vector);
    }
}
