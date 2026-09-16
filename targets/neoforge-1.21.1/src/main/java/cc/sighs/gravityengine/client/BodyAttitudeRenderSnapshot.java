package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import net.minecraft.util.Mth;
import cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.Objects;

/** Immutable resolved render pose. It owns no input, prediction or history. */
public final class BodyAttitudeRenderSnapshot {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;
    private final Quaterniond worldFromBody;
    private final CharacterAttitudeContract attitudeContract;
    private final Vec3 semanticWorldForward;
    private final SemanticView cameraView;
    private final float viewLocalYaw;
    private final float viewLocalPitch;
    private final long localStateRevision;
    private final long lastLocalSimulationStep;
    private final long lifecycleEpoch;
    private final long authoritativeRevision;
    private final long authoritativeServerGameTick;
    private final long authoritativeStreamEpoch;
    private final long authoritativeConfigGeneration;

    /** Resolved body and controller orientations; scalar angles select pole projections only. */
    public BodyAttitudeRenderSnapshot(Quaterniond worldFromBody, Vec3 semanticWorldForward,
            SemanticView semanticView, float fallbackLocalYaw, float fallbackLocalPitch,
            long localStateRevision, long lastLocalSimulationStep, long lifecycleEpoch,
            long authoritativeRevision, long authoritativeServerGameTick,
            long authoritativeStreamEpoch, long authoritativeConfigGeneration, CharacterAttitudeContract attitudeContract) {
        this.attitudeContract = Objects.requireNonNull(attitudeContract, "attitudeContract");
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        double lengthSquared = worldFromBody.lengthSquared();
        if (!Double.isFinite(worldFromBody.x) || !Double.isFinite(worldFromBody.y)
                || !Double.isFinite(worldFromBody.z) || !Double.isFinite(worldFromBody.w)
                || !Double.isFinite(lengthSquared) || lengthSquared <= MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException("worldFromBody must be finite and non-degenerate");
        }
        Objects.requireNonNull(semanticWorldForward, "semanticWorldForward");
        if (!Double.isFinite(semanticWorldForward.x)
                || !Double.isFinite(semanticWorldForward.y)
                || !Double.isFinite(semanticWorldForward.z)
                || semanticWorldForward.lengthSqr() <= MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "semanticWorldForward must be finite and non-degenerate");
        }
        if (!Float.isFinite(fallbackLocalYaw) || !Float.isFinite(fallbackLocalPitch)
                || fallbackLocalPitch < -BodyRelativeViewState.VANILLA_PITCH_PROJECTION_LIMIT
                || fallbackLocalPitch > BodyRelativeViewState.VANILLA_PITCH_PROJECTION_LIMIT) {
            throw new IllegalArgumentException(
                    "render snapshot view angles must be finite and vanilla-bounded");
        }
        if (localStateRevision < 0L || lifecycleEpoch < 0L
                || lastLocalSimulationStep < BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP
                || authoritativeRevision < BodyAttitudeComponent.NO_AUTHORITATIVE_REVISION
                || authoritativeServerGameTick < BodyAttitudeComponent.NO_AUTHORITATIVE_SERVER_GAME_TICK
                || authoritativeStreamEpoch < BodyAttitudeComponent.NO_AUTHORITATIVE_STREAM_EPOCH
                || authoritativeConfigGeneration
                < BodyAttitudeComponent.NO_AUTHORITATIVE_CONFIG_GENERATION) {
            throw new IllegalArgumentException("invalid render snapshot counters");
        }
        this.worldFromBody = new Quaterniond(worldFromBody).normalize();
        this.semanticWorldForward = semanticWorldForward.normalize();
        AttitudeSpaceTransform.LocalLookAngles coherent =
                AttitudeSpaceTransform.worldLookToBodyAngles(
                        this.worldFromBody,
                        this.semanticWorldForward,
                        new AttitudeSpaceTransform.LocalLookAngles(
                                fallbackLocalYaw, fallbackLocalPitch));
        this.viewLocalYaw = Mth.wrapDegrees(coherent.yawDegrees());
        this.viewLocalPitch = coherent.pitchDegrees();
        this.cameraView = Objects.requireNonNull(semanticView, "semanticView");
        this.localStateRevision = localStateRevision;
        this.lastLocalSimulationStep = lastLocalSimulationStep;
        this.lifecycleEpoch = lifecycleEpoch;
        this.authoritativeRevision = authoritativeRevision;
        this.authoritativeServerGameTick = authoritativeServerGameTick;
        this.authoritativeStreamEpoch = authoritativeStreamEpoch;
        this.authoritativeConfigGeneration = authoritativeConfigGeneration;
    }

    public CharacterAttitudeContract attitudeContract() { return attitudeContract; }
    public Quaterniond worldFromBody() { return new Quaterniond(this.worldFromBody); }
    public Vec3 semanticWorldForward() { return this.semanticWorldForward; }
    public SemanticView cameraView() { return cameraView; }
    public float viewLocalYaw() { return this.viewLocalYaw; }
    public float viewLocalPitch() { return this.viewLocalPitch; }
    public long localStateRevision() { return this.localStateRevision; }
    public long lastLocalSimulationStep() { return this.lastLocalSimulationStep; }
    public long lifecycleEpoch() { return this.lifecycleEpoch; }
    public long authoritativeRevision() { return this.authoritativeRevision; }
    public long authoritativeServerGameTick() { return this.authoritativeServerGameTick; }
    public long authoritativeStreamEpoch() { return this.authoritativeStreamEpoch; }
    public long authoritativeConfigGeneration() { return this.authoritativeConfigGeneration; }

    public AttitudeSpaceTransform.LocalLookAngles coherentLocalLook() {
        return new AttitudeSpaceTransform.LocalLookAngles(
                this.viewLocalYaw, this.viewLocalPitch);
    }

    /** Pure display follow keeps the immediate controller preview inside the displayed root joint.
     * This cannot install actor state or advance simulation. Camera/controller are unchanged. */
    public BodyAttitudeRenderSnapshot boundedJoint(Quaterniond previousController, double limit) {
        if (!attitudeContract.viewBodyJointFollow()) return this;
        var body = cc.sighs.gravityengine.attitude.BodyViewConstraintSolver.follow(worldFromBody,
                previousController, cameraView.worldFromController(), limit).body();
        return new BodyAttitudeRenderSnapshot(body, semanticWorldForward, cameraView, viewLocalYaw, viewLocalPitch,
                localStateRevision, lastLocalSimulationStep, lifecycleEpoch, authoritativeRevision,
                authoritativeServerGameTick, authoritativeStreamEpoch, authoritativeConfigGeneration, attitudeContract);
    }

    /** Free attitude owns the full controller joint. Elytra owns only semantic local yaw/pitch. */
    public Quaterniond headJoint() {
        if (attitudeContract.elytraAlignment()) return AttitudeSpaceTransform.localViewRotation(coherentLocalLook());
        return new Quaterniond(worldFromBody).conjugate().mul(cameraView.worldFromController()).normalize();
    }
    public org.joml.Vector3d modelHeadRotation() {
        if (attitudeContract.elytraAlignment()) {
            // These are centralized body-local direction projections, not world Euler angles.
            // Controller/camera up and roll cannot cancel the model root's Qbody roll.
            return new org.joml.Vector3d(Math.toRadians(viewLocalPitch), Math.toRadians(viewLocalYaw), 0);
        }
        Quaterniond joint = headJoint();
        return cc.sighs.gravityengine.attitude.BodyViewConstraintSolver.localJointAngles(
                new Quaterniond(joint.x, -joint.y, -joint.z, joint.w));
    }
    public AttitudeSpaceTransform.LocalLookAngles modelLook(float maxHeadDegrees) {
        var angles = modelHeadRotation();
        return new AttitudeSpaceTransform.LocalLookAngles((float)Math.toDegrees(angles.y),
                (float)Math.toDegrees(angles.x));
    }
}
