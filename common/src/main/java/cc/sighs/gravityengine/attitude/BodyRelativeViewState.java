package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Immutable controller orientation with derived Vanilla scalar projections.
 * Local yaw/pitch exist only for Vanilla-facing output and reference-aligned bootstrap.
 * Live replication, persistence and continuous input carry SemanticView's quaternion. */
public final class BodyRelativeViewState {
    /** Vanilla scalar projection range in degrees. */
    public static final float VANILLA_PITCH_PROJECTION_LIMIT = 90.0F;

    private final float localYaw;
    private final float localPitch;
    private final boolean initialized;
    private final SemanticView semantic;

    private BodyRelativeViewState(
            float localYaw,
            float localPitch,
            boolean initialized
    ) {
        this(localYaw, localPitch, initialized, null);
    }

    private BodyRelativeViewState(float localYaw, float localPitch, boolean initialized, SemanticView semantic) {
        this.localYaw = finite(localYaw, "localYaw");
        this.localPitch = finite(localPitch, "localPitch");
        this.initialized = initialized;
        this.semantic = semantic;
    }

    public SemanticView semantic(Quatd body) {
        return semantic != null ? semantic : new SemanticView(body, localLook());
    }

    /** Freeze the control reference before a body replacement, even at bootstrap. */
    public BodyRelativeViewState bind(Quatd body) {
        return !initialized || semantic != null ? this
                : new BodyRelativeViewState(localYaw, localPitch, true, semantic(body));
    }

    public static BodyRelativeViewState fromSemantic(SemanticView semantic, Quatd body,
            AttitudeSpaceTransform.LocalLookAngles fallback) {
        Objects.requireNonNull(semantic, "semantic");
        var local = AttitudeSpaceTransform.worldLookToBodyAngles(body, semantic.forward(), fallback);
        return new BodyRelativeViewState(wrap(local.yawDegrees()), clampPitch(local.pitchDegrees()), true, semantic);
    }

    /** Projection only: no input, clamp of semantic yaw, or camera mutation. */
    public BodyRelativeViewState relativeToBody(Quatd body) {
        return semantic == null ? this : fromSemantic(semantic, body, localLook());
    }

    public static BodyRelativeViewState uninitialized() {
        return new BodyRelativeViewState(
                0.0F, 0.0F, false);
    }

    /**
     * Atomic activation used when body-attitude ownership starts.
     *
     * <p>The world look before activation is converted into body-local joints
     * against the freshly displayed {@code worldFromBody}, so
     * {@code Qbody * localLook} reproduces the pre-activation world look with
     * no camera jump.</p>
     */
    public static BodyRelativeViewState activate(
            Quatd worldFromBody,
            Vec3d worldLookBeforeActivation,
            float fallbackYaw,
            float fallbackPitch
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(worldLookBeforeActivation,
                "worldLookBeforeActivation");
        AttitudeSpaceTransform.LocalLookAngles fallback =
                new AttitudeSpaceTransform.LocalLookAngles(
                        finite(fallbackYaw, "fallbackYaw"),
                        finite(fallbackPitch, "fallbackPitch"));
        AttitudeSpaceTransform.LocalLookAngles local =
                AttitudeSpaceTransform.worldLookToBodyAngles(
                        worldFromBody, worldLookBeforeActivation, fallback);
        return new BodyRelativeViewState(
                wrap(local.yawDegrees()),
                clampPitch(local.pitchDegrees()),
                true
        );
    }

    /**
     * Rebases only the body-local view joints against a new absolute body
     * orientation so they reproduce {@code desiredWorldForward}.
     *
     * <p>At a body-local view
     * pole the current local yaw is supplied as the inverse-transform fallback
     * so an undefined {@code atan2(0, 0)} cannot introduce yaw chatter.</p>
     *
     * <p>This is an ordinary continuous-step operation.  It is deliberately
     * separate from {@link #activate}, which owns lifecycle bootstrap and the
     * pre-activation display handoff.</p>
     */
    public BodyRelativeViewState rebaseToWorldForward(
            Quatd newWorldFromBody,
            Vec3d desiredWorldForward
    ) {
        Objects.requireNonNull(newWorldFromBody, "newWorldFromBody");
        Objects.requireNonNull(desiredWorldForward, "desiredWorldForward");
        if (!this.initialized) return this;
        if (semantic != null) {
            Quatd swing =
                    Quatd.rotationTo(
                            semantic.forward(),
                            desiredWorldForward
                    );
            return fromSemantic(new SemanticView(swing.multiply(semantic.worldFromController())), newWorldFromBody, localLook());
        }
        AttitudeSpaceTransform.LocalLookAngles local =
                AttitudeSpaceTransform.worldLookToBodyAngles(
                        newWorldFromBody,
                        desiredWorldForward,
                        this.localLook());

        return new BodyRelativeViewState(
                wrap(local.yawDegrees()),
                clampPitch(local.pitchDegrees()),
                true
        );
    }

    /** Uses explicit fallback angles only when no semantic view is initialized. */
    public AttitudeSpaceTransform.LocalLookAngles explicitLocalLook(
            float explicitYaw,
            float explicitPitch
    ) {
        float yaw = finite(explicitYaw, "explicitYaw");
        float pitch = finite(explicitPitch, "explicitPitch");
        if (!this.initialized) {
            return new AttitudeSpaceTransform.LocalLookAngles(
                    wrap(yaw), clampPitch(pitch));
        }
        // Reading aim never interprets Vanilla-facing scalar differences as input.
        return localLook();
    }

    public float localYaw() { return this.localYaw; }
    public float localPitch() { return this.localPitch; }
    public boolean initialized() { return this.initialized; }

    /** Control reference participates in equality; matching model joints alone
     * do not establish identical future mouse/control semantics. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BodyRelativeViewState that)) {
            return false;
        }
        return this.initialized == that.initialized
                && Float.compare(this.localYaw, that.localYaw) == 0
                && Float.compare(this.localPitch, that.localPitch) == 0
                && Objects.equals(this.semantic, that.semantic);
    }

    @Override
    public int hashCode() {
        int result = this.initialized ? 1 : 0;
        result = 31 * result + Float.hashCode(this.localYaw);
        result = 31 * result + Float.hashCode(this.localPitch);
        return 31 * result + Objects.hashCode(semantic);
    }

    /** The local view joints themselves, without any explicit-scalar delta. */
    public AttitudeSpaceTransform.LocalLookAngles localLook() {
        return new AttitudeSpaceTransform.LocalLookAngles(
                this.localYaw, this.localPitch);
    }

    private static float wrap(float degrees) {
        float value = finite(degrees, "degrees") % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private static float clampPitch(float degrees) {
        float value = finite(degrees, "pitch");
        return Math.max(
                -VANILLA_PITCH_PROJECTION_LIMIT,
                Math.min(VANILLA_PITCH_PROJECTION_LIMIT, value));
    }

    private static float finite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }
}
