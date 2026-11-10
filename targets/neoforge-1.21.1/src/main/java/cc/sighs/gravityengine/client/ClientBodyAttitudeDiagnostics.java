package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AngularMomentumState;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Objects;

/** Read-only diagnostics of local control and installed remote state. No display pose or animation state. */
public final class ClientBodyAttitudeDiagnostics {
    private ClientBodyAttitudeDiagnostics() {}

    /** Read-only F3/debug view. No timeline is created when none exists. */
    @Nullable
    public static DebugSnapshot debugSnapshot(Player player) {
        Objects.requireNonNull(player, "player");
        if (player.isLocalPlayer()) {
            BodyAttitudeComponent owner = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);
            if (owner == null) return null;
            BodyAttitudeComponent.Snapshot component =
                    owner.snapshot();
            var input = ClientBodyAttitudeControl.pendingLook(player);
            return new DebugSnapshot(
                    AuthorityRole.LOCAL_CONTROL,
                    constraintOf(component.decision()),
                    component.continuity(),
                    component.lifecycleEpoch(),
                    component.lastLocalSimulationStep(),
                    component.authoritativeServerGameTick(),
                    component.state().revision(),
                    component.authoritativeRevision(),
                    component.authoritativeStreamEpoch(),
                    component.authoritativeConfigGeneration(),
                    input.rollAxis(),
                    component.state().currentWorldFromBody(),
                    component.state().angularVelocityWorld(),
                    component.state().hasAngularDynamics(),
                    component.state().angularMomentum()
                            .map(AngularMomentumState::angularMomentumWorld)
                            .orElse(Vec3d.ZERO),
                    component.state().effectiveInertia()
                            .map(cc.sighs.gravityengine.attitude
                                    .EffectiveAngularInertia::isotropic)
                            .orElse(0.0D),
                    component.view().localYaw(),
                    component.view().localPitch(),
                    0L, ClientBodyAttitudeSync.pendingCount(), component.authoritativeStreamOpen() ? "LOCAL_STATE" : "NO_STREAM",
                    owner.ownership(),
                    component.lastStepResult() == null ? Double.NaN
                            : component.lastStepResult().rotationErrorRadians(),
                    GravityPresentationIntegration.presentationAuthority(player),
                    component.decision().suspensionReason());
        }

        RemoteBodyAttitudeLerp timeline = RemoteBodyAttitudeLerp.get(player);
        if (timeline == null) return null;
        RemoteBodyAttitudeLerp.DebugState remote = timeline.debugState();
        ClientboundBodyAttitudeStatePayload current = remote.current();
        if (current == null) return null;
        AngularMomentumState remoteDynamics =
                current.dynamicStatePresent()

                        ? BodyAttitudeRuntime.Config
                        .clientFor(
                                current.authoritativeConfigGeneration()
                        )
                        .map(config ->
                             new AngularMomentumState(
                                     current.angularMomentumWorld(),
                                     config.simulation()
                                             .elytraEffectiveAngularInertia()
                             )
                        )
                        .orElse(null)

                        : null;
        var projection = BodyRelativeViewState.fromSemantic(new SemanticView(current.worldFromController()),
                current.worldFromBody(), new AttitudeSpaceTransform.LocalLookAngles(0, 0));
        return new DebugSnapshot(
                AuthorityRole.REMOTE_SNAPSHOT,
                null, current.continuity(), 0L,
                BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP,
                current.authoritativeServerGameTick(),
                current.authoritativeRevision(),
                current.authoritativeRevision(), current.streamEpoch(),
                current.authoritativeConfigGeneration(),
                0.0D,
                current.worldFromBody(),
                remoteDynamics == null
                        ? Vec3d.ZERO
                        : remoteDynamics.angularVelocityWorld(current.worldFromBody()),
                remoteDynamics != null,
                remoteDynamics == null
                        ? Vec3d.ZERO
                        : remoteDynamics.angularMomentumWorld(),
                remoteDynamics == null ? 0.0D : remoteDynamics.inertia().isotropic(),
                projection.localYaw(), projection.localPitch(),
                remote.snapshotAgeTicks(),
                ClientBodyAttitudeSync.pendingCount(), remote.lastSyncReason(),
                current.ownership(), Double.NaN,
                GravityPresentationIntegration.presentationAuthority(player),
                current.suspensionReason());
    }

    @Nullable
    private static BodyAttitudeConstraintKind constraintOf(
            BodyAttitudeDecision decision
    ) {
        return decision.active()
                ? decision.profile().constraint()
                : null;
    }

    public enum AuthorityRole { LOCAL_CONTROL, REMOTE_SNAPSHOT }

    public record DebugSnapshot(
            AuthorityRole authorityRole,
            @Nullable BodyAttitudeConstraintKind constraint,
            BodyAttitudeContinuity continuity,
            long lifecycleEpoch,
            long lastLocalSimulationStep,
            long authoritativeServerGameTick,
            long localRevision,
            long authoritativeRevision,
            long streamEpoch,
            long configGeneration,
            double rollInput,
            Quatd worldFromBody,
            /** Derived {@code omega_world} in rad/s; zero for kinematic ownership. */
            Vec3d angularVelocityWorld,
            /** Whether the canonical physical state is {@code q + L_world + I_body}. */
            boolean dynamicAngularOwnership,
            /** Durable {@code L_world} in {@code inertia-unit * rad / s}. */
            Vec3d angularMomentumWorld,
            /** Isotropic effective inertia in rotational-inertia game units, or zero. */
            double effectiveAngularInertia,
            float viewLocalYaw,
            float viewLocalPitch,
            long remoteSnapshotAgeTicks,
            int pendingPacketCount,
            String lastSyncReason,
            BodyAttitudeOwnership ownership,
            double rotationErrorRadians,
            PresentationAuthority presentationAuthority,
            BodyAttitudeSuspensionReason suspensionReason
    ) {
    }
}
