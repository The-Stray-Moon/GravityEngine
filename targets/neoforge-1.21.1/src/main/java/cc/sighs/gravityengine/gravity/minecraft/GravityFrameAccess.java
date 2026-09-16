package cc.sighs.gravityengine.gravity.minecraft;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Minecraft-facing frame access seam.
 *
 * <p>{@link #authoritativeFrame(Entity)} resolves the authoritative gameplay
 * frame from the entity-owned component/runtime state and is the single entry
 * that other target code should use for that policy.
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
                component.runtime(),
                component.appliedState(),
                GravityEntityGeometry.proxyCenter(entity)
        );
    }

    /**
     * Resolves operation evidence against the installed collision axis without
     * resampling gravity. An active move owns its frame; otherwise the last
     * completed frame is re-expressed on the installed axis.
     */
    private static GravityFrame resolve(
            GravityRuntimeState runtime,
            GravityState initialState,
            Vec3 initialSamplePoint
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(initialSamplePoint, "initialSamplePoint");

        if (runtime.isInMove()) return runtime.activeFrame();

        GravityFrame evidence = runtime.lastCompletedFrame();
        if (evidence == null) {
            evidence = GravityFrame.fromState(initialState, initialSamplePoint);
        }
        return runtime.installedCollisionUp() == null
                ? evidence
                : runtime.frameForInstalledAxis(evidence);
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
        GravityRuntimeState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .runtime();
        GravityMoveResult result = runtime.currentMoveResult();
        if (result != null) return result.frame();
        if (runtime.isInMove()) return runtime.activeFrame();
        return runtime.geometryReferenceFrame();
    }
}
