package cc.sighs.gravityengine.gravity.minecraft;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Minecraft-facing frame access seam.
 *
 * <p>{@link #authoritativeFrame(Entity)} resolves the environmental/control
 * reference from the entity-owned component/runtime state.
 * {@link #installedLocomotionFrame(Entity)} derives collision/support operands
 * around installed geometry without changing that reference.
 * {@link #reliableFrame(Entity)} only looks up an already-owned transaction
 * frame for diagnostics. Neither method samples gravity and neither resamples
 * an active operation.</p>
 */
public final class GravityFrameAccess {
    private GravityFrameAccess() {}

    public static GravityFrame authoritativeFrame(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        return resolve(
                component.operationState(),
                component.state().hasActiveGravityReference(),
                component.state().appliedState(),
                GravityEntityGeometry.proxyCenter(entity)
        );
    }

    /**
     * Resolves environmental evidence without resampling gravity or consulting
     * collision orientation. An active move owns its captured reference.
     */
    private static GravityFrame resolve(
            GravityOperationState runtime,
            boolean referenceActive,
            GravityState initialState,
            Vec3 initialSamplePoint
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(initialSamplePoint, "initialSamplePoint");

        if (runtime.isInMove()) return runtime.activeReferenceFrame();

        GravityFrame evidence = runtime.lastCompletedFrame();
        if (evidence == null) {
            evidence = GravityFrame.fromState(
                    initialState,
                    MinecraftMathAdapter.toVec3d(
                            initialSamplePoint
                    )
            );
        } else if (referenceActive) {
            /*
             * The environmental reference remains under the same authority.
             * Re-resolve the current state but transport the previous tangent
             * gauge; a non-canonical basis must not flip merely because the
             * committed state was republished or a body fallback occurred.
             */
            evidence = GravityFrame.fromState(
                    initialState,
                    MinecraftMathAdapter.toVec3d(
                            initialSamplePoint
                    ),
                    evidence
            );
        } else {
            evidence = GravityFrame.fromState(
                    initialState,
                    MinecraftMathAdapter.toVec3d(
                            initialSamplePoint
                    )
            );
        }
        return evidence;
    }

    /** Collision/support operand derived from installed geometry, never a reference publication. */
    public static GravityFrame installedLocomotionFrame(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        if (runtime.isInMove()) return runtime.activeFrame();
        GravityFrame reference = authoritativeFrame(entity);
        return runtime.installedCollisionUp() == null ? reference : runtime.frameForInstalledAxis(reference);
    }

    /**
     * Frame that is already owned by the entity's current transaction: the
     * committed move result's frame when one exists, otherwise the live
     * operation frame, otherwise the installed geometry frame.
     *
     * <p>This is a lookup of already-produced state, never a resolution or a
     * resample. Callers that need the authoritative gameplay frame use
     * {@link #authoritativeFrame(Entity)} instead.</p>
     */
    public static GravityFrame reliableFrame(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        GravityOperationState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .operationState();
        GravityMoveResult result = runtime.currentMoveResult();
        if (result != null) return result.frame();
        if (runtime.isInMove()) return runtime.activeFrame();
        return runtime.geometryReferenceFrame();
    }
}
