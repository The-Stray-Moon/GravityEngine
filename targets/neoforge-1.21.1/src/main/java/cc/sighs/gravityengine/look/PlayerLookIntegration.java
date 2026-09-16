package cc.sighs.gravityengine.look;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyLookControlSample;
import cc.sighs.gravityengine.attitude.BodyLookResolver;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Canonical semantic-look authority boundary.
 *
 * <p>Resolution order is active BodyAttitude, gravity-local semantic look,
 * then Vanilla look. Production consumers obtain one immutable
 * {@link SemanticLookSnapshot} through {@link #capture(Entity, GravityFrame)}
 * or its explicit-scalar overload and reuse that snapshot for the whole
 * logical operation.</p>
 *
 * <p>{@link #usesGravityEngineLook(Entity)} and
 * {@link #hasActiveAttitudeLook(Entity)} are ownership predicates only. They
 * never consume pending input or run {@link BodyLookResolver}.</p>
 */
public final class PlayerLookIntegration {
    private PlayerLookIntegration() {}

    /** True when this entity's look must come from GravityEngine authority. */
    public static boolean usesGravityEngineLook(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return hasActiveAttitudeLook(entity)
                || GravityInfluencePolicy.usesGravityLocalLook(entity);
    }

    /** True when the player component currently owns a body-relative view. */
    public static boolean hasActiveAttitudeLook(Entity entity) {
        return activeAttitudeSnapshot(entity) != null;
    }

    /**
     * One immutable semantic look snapshot resolved from the entity's current
     * yaw/pitch scalars.  Callers that already own an
     * authoritative {@link GravityFrame} must pass it explicitly.
     */
    public static SemanticLookSnapshot capture(
            Entity entity,
            GravityFrame frame
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        return captureInternal(entity, frame, entity.getYRot(), entity.getXRot());
    }

    /** Snapshot capture with explicit Vanilla/gravity-local scalars. */
    public static SemanticLookSnapshot capture(
            Entity entity,
            GravityFrame frame,
            float explicitYaw,
            float explicitPitch
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        return captureInternal(
                entity, frame, explicitYaw, explicitPitch);
    }

    private static SemanticLookSnapshot captureInternal(
            Entity entity,
            GravityFrame frame,
            float explicitYaw,
            float explicitPitch
    ) {
        AttitudeLookSample attitude =
                activeAttitudeSample(entity);

        if (attitude != null) {
            return attitudeSnapshot(attitude, frame);
        }

        if (GravityInfluencePolicy.usesGravityLocalLook(entity)) {
            return gravityLocalSnapshot(
                    frame,
                    explicitYaw,
                    explicitPitch
            );
        }

        return vanillaSnapshot(
                entity,
                explicitYaw,
                explicitPitch
        );
    }

    /**
     * Cheap, deterministic authority predicate. Answering whether
     * {@code BODY_ATTITUDE} currently owns semantic look must never consume
     * pending mouse input or advance transient look state, so this
     * helper reads only component ownership/lifecycle snapshot fields.
     */
    @Nullable
    private static BodyAttitudeComponent.Snapshot activeAttitudeSnapshot(
            Entity entity
    ) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        BodyAttitudeComponent component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);
        if (component == null) {
            return null;
        }
        BodyAttitudeComponent.Snapshot snapshot = component.snapshot();
        if (snapshot.ownership() == BodyAttitudeOwnership.INACTIVE
                || !snapshot.decision().active()
                || snapshot.continuity() != BodyAttitudeContinuity.CONTINUOUS
                || !snapshot.state().initialized()
                || !snapshot.view().initialized()) {
            return null;
        }
        return snapshot;
    }

    /**
     * One coherent active-attitude semantic sample.  This is the only path
     * that reads pending input and runs {@link BodyLookResolver}; authority
     * predicates never call it.
     */
    @Nullable
    private static AttitudeLookSample activeAttitudeSample(
            Entity entity
    ) {
        BodyAttitudeComponent.Snapshot snapshot =
                activeAttitudeSnapshot(entity);

        if (snapshot == null
                || !(entity instanceof Player player)) {
            return null;
        }

        BodyAttitudeInput pending =
                pendingLook(player);

        var config = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.forPlayer(player);
        BodyLookControlSample resolved =
                BodyLookResolver.resolve(
                        snapshot.view(),
                        pending,
                        snapshot.state()
                                .currentWorldFromBody(),
                        config.orElse(cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot.DEFAULT),
                        snapshot.decision().controllerRoll() && config.isPresent()
                                ? cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Service.GAME_TICK_SECONDS : 0);

        return new AttitudeLookSample(
                snapshot,
                resolved
        );
    }

    private static SemanticLookSnapshot attitudeSnapshot(
            AttitudeLookSample attitude, GravityFrame frame
    ) {
        BodyAttitudeState state = attitude.snapshot().state();
        Quaterniond worldFromBody = state.currentWorldFromBody();
        var semantic = attitude.resolved().nextViewState().semantic(worldFromBody);
        OrthonormalFrame3d local = BodyOrientation3d.frame(semantic.worldFromController());
        // Angles here describe the identity direction in controller coordinates, never absolute look.
        var requested = new AttitudeSpaceTransform.LocalLookAngles(0, 0);
        Vec3 tangent = semantic.forward().subtract(frame.up().scale(semantic.forward().dot(frame.up())));
        if (tangent.lengthSqr() < 1e-12) tangent = semantic.up().subtract(frame.up().scale(semantic.up().dot(frame.up())));
        if (tangent.lengthSqr() < 1e-12) tangent = semantic.left().cross(frame.up());
        Vec3 zeroPitch = tangent.normalize();
        return new SemanticLookSnapshot(
                SemanticLookSnapshot.Source.BODY_ATTITUDE,
                local,
                requested,
                attitude.resolved().requestedWorldForward(),
                attitude.resolved().requestedWorldUp(),
                zeroPitch
        );
    }

    private static SemanticLookSnapshot gravityLocalSnapshot(
            GravityFrame frame,
            float explicitYaw,
            float explicitPitch
    ) {
        GravityLocalLook.WorldLook look =
                GravityLocalLook.toWorld(frame, explicitYaw, explicitPitch);
        return new SemanticLookSnapshot(
                SemanticLookSnapshot.Source.GRAVITY_LOCAL,
                frame.orientation(),
                new AttitudeSpaceTransform.LocalLookAngles(
                        explicitYaw, explicitPitch),
                look.forward(),
                look.up(),
                GravityLocalLook.toWorld(frame, explicitYaw, 0.0F).forward()
        );
    }

    private static SemanticLookSnapshot vanillaSnapshot(
            Entity entity,
            float explicitYaw,
            float explicitPitch
    ) {
        return new SemanticLookSnapshot(
                SemanticLookSnapshot.Source.VANILLA,
                OrthonormalFrame3d.IDENTITY,
                new AttitudeSpaceTransform.LocalLookAngles(
                        explicitYaw, explicitPitch),
                entity.calculateViewVector(explicitPitch, explicitYaw),
                entity.calculateViewVector(explicitPitch - 90.0F, explicitYaw),
                entity.calculateViewVector(0.0F, explicitYaw)
        );
    }

    /**
     * Client-local players expose their raw/current preview through the
     * {@link cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Input} entity seam.  Every other player has no
     * pending look input.
     */
    private static BodyAttitudeInput pendingLook(Player player) {
        return cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Input.pendingLook(player);
    }

    /** One already-read attitude component/snapshot/pending-input sample. */
    private record AttitudeLookSample(
            BodyAttitudeComponent.Snapshot snapshot,
            BodyLookControlSample resolved
    ) {}
}
