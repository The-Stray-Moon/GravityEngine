package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import javax.annotation.Nullable;
import java.util.*;

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
                current.worldFromBody(), Vec3.ZERO,
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
            Quaterniond worldFromBody,
            Vec3 angularVelocityWorld,
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
        public DebugSnapshot {
            worldFromBody = new Quaterniond(worldFromBody);
        }

        @Override public Quaterniond worldFromBody() {
            return new Quaterniond(worldFromBody);
        }

    }

}
