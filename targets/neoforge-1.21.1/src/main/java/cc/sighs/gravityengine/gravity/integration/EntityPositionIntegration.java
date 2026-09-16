package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Position discontinuity and exact-body geometry repair integration.
 *
 * <p>Owns the classification of externally imposed position writes, the
 * discontinuity handling they require and the geometry repairs that follow a
 * Vanilla position or dimension write. None of these seams sample gravity;
 * they consume the already authoritative environmental frame.</p>
 */
public final class EntityPositionIntegration {
    private EntityPositionIntegration() {}

    private static final double SOFT_POSITION_CORRECTION_DISTANCE =
            1.0E-4D;

    private static final double SOFT_POSITION_CORRECTION_DISTANCE_SQUARED =
            SOFT_POSITION_CORRECTION_DISTANCE
                    * SOFT_POSITION_CORRECTION_DISTANCE;

    /**
     * Semantic seam for one logical external position replacement whose positionAnchor
     * actually changed (the outermost composite {@code Entity.setPos} /
     * {@code Entity.moveTo} completion, teleport/level-change payloads,
     * respawn placement and the standalone {@code setPosRaw} fallback).
     *
     * <p>Movement continuity is invalidated exactly once, then the exact
     * gravity-oriented body is rebuilt exactly once at the new positionAnchor using the
     * existing authoritative environmental frame.  No {@code GravityMoveResult}
     * is fabricated, no collision solve runs, and no new environmental
     * gravity evidence is sampled here: the next outer movement scope
     * owns environmental resampling.  This is deliberately separate from the
     * ordinary resolved-translation commit performed by the owning movement
     * operation: an owned {@code Entity.move} commit is movement, not a
     * discontinuity, and never routes here.</p>
     *
     * <p>Calling this inside an active movement/geometry owner is a caller
     * bug; {@link #invalidateMovementContinuity(Entity)} rejects it rather
     * than silently superseding the live transaction.</p>
     */
    public static void commitPositionDiscontinuity(
            Entity entity,
            Vec3 newPositionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(newPositionAnchor, "newPositionAnchor");
        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return;
        }
        EntityMovementIntegration.invalidateMovementContinuity(entity);
        // Vanilla teleport changes coordinates without moving through collision.
        // The old support block cannot seed material queries at the destination.
        GravityEntityAccess.cast(entity).gravityengine$restoreVanillaCollisionState(
                new cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState(
                        false, false, false, false, false, java.util.Optional.empty()));
        rebuildExactBodyAfterVanillaPositionWrite(entity, newPositionAnchor);
    }

    /**
     * Final custom-geometry repair after a Vanilla composite position write
     * has completely returned ({@code setPos} ran its own
     * {@code setBoundingBox(makeBoundingBox())}, or the {@code moveTo} chain
     * ended in {@code reapplyPosition()} -> {@code setPos}).
     *
     * <p>Vanilla may have replaced the custom enclosing AABB even when the
     * positionAnchor did not change, so the exact body is restored here unconditionally
     * for custom-body entities.  Movement continuity is deliberately
     * untouched: provenance decides whether the completed write is a
     * discontinuity, not this geometry repair.</p>
     */
    private static void rebuildExactBodyAfterVanillaPositionWrite(
            Entity entity,
            Vec3 positionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return;
        }
        /*
         * Geometry repair is not a gravity-sampling operation.  The
         * installed/completed authoritative frame stays valid as geometry and
         * reference continuity across the position write; the next outer
         * operation performs the next legitimate environmental evidence
         * resolution.  Never synthesize a replacement frame from the
         * synchronized applied GravityState while an authoritative frame
         * exists.
         */
        GravityFrame frame = GravityFrameAccess.authoritativeFrame(entity);
        GravityEntityGeometry.installFromPositionAnchor(entity, frame, positionAnchor);
    }

    /**
     * Outermost-completion classification for a Vanilla composite position
     * write that has fully returned (see {@link
     * #rebuildExactBodyAfterVanillaPositionWrite(Entity, Vec3)}).  The Mixin
     * calls this exactly once per logical {@code setPos}/{@code moveTo}
     * operation, never from nested raw/composite implementation details.
     *
     * <p>Decision order (explicit ownership, never the runtime flags alone):
     * not a custom body -&gt; no-op; an internal exact-geometry primitive write
     * ({@code isApplyingGeometry}) -&gt; owned by its geometry transaction;
     * a resolved movement commit -&gt; the owning movement transaction
     * performs the final geometry publication; an unowned position
     * replacement inside a live physics owner -&gt; supersede the uncommitted
     * result and defer geometry to the outer handoff; otherwise the independent
     * position-write classification below.</p>
     */
    public static void onCompositePositionWriteCompleted(
            Entity entity,
            Vec3 beforePositionAnchor,
            Vec3 afterPositionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(
                beforePositionAnchor,
                "beforePositionAnchor"
        );
        Objects.requireNonNull(
                afterPositionAnchor,
                "afterPositionAnchor"
        );

        GravityRuntimeState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        if (runtime.consumeExternalSupportTransportPositionWrite(
                beforePositionAnchor,
                afterPositionAnchor
        )) {
            /*
             * Explicit support transport is an owned accepted translation,
             * not an external discontinuity.
             */
            if (GravityInfluencePolicy.usesCustomBody(entity)) {
                GravityEntityGeometry.reanchorAfterVanillaTranslation(
                        entity,
                        runtime.activeFrame()
                );
            }
            return;
        }

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return;
        }

        if (runtime.isApplyingGeometry()) {
            return;
        }

        /*
         * Any external position mutation during an active movement transaction
         * still supersedes that movement, regardless of how small it is.
         */
        if (runtime.isInMove()) {
            if (positionChanged(
                    beforePositionAnchor,
                    afterPositionAnchor
            )) {
                runtime.supersedeMovement(
                        afterPositionAnchor
                );
            } else if (runtime.discontinuityDestination() == null) {
                GravityEntityGeometry.reanchorAfterVanillaTranslation(
                        entity,
                        runtime.activeFrame()
                );
            }
            return;
        }

        if (!positionChanged(
                beforePositionAnchor,
                afterPositionAnchor
        )) {
            rebuildExactBodyAfterVanillaPositionWrite(
                    entity,
                    afterPositionAnchor
            );
            return;
        }

        if (continuityBreakingPositionChange(
                beforePositionAnchor,
                afterPositionAnchor
        )) {
            commitPositionDiscontinuity(
                    entity,
                    afterPositionAnchor
            );
            return;
        }

        commitSoftPositionCorrection(
                entity,
                afterPositionAnchor
        );
    }

    /**
     * Fallback classification for a truly standalone raw position-field write
     * with no enclosing composite owner.
     *
     * <p>{@code setPosRaw} is a primitive write used by unrelated Vanilla
     * systems; it does not intrinsically mean teleport.  This fallback exists
     * only for direct callers that bypass {@code setPos}/{@code moveTo}: a
     * changed raw write receives one standalone discontinuity handling path,
     * while an unchanged raw write invalidates nothing and reinstalls no
     * geometry.  Raw writes that occur inside a live movement/geometry owner
     * (for example exact-body commits, which write positionAnchor plus proxy under
     * their own geometry scope) are owned by that transaction; the classifier
     * reaches them and returns without publishing.</p>
     */
    public static void onStandalonePositionWrite(
            Entity entity,
            Vec3 beforePositionAnchor,
            Vec3 afterPositionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(
                beforePositionAnchor,
                "beforePositionAnchor"
        );
        Objects.requireNonNull(
                afterPositionAnchor,
                "afterPositionAnchor"
        );

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return;
        }

        if (!positionChanged(
                beforePositionAnchor,
                afterPositionAnchor
        )) {
            return;
        }

        GravityRuntimeState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        if (runtime.isApplyingGeometry()) {
            return;
        }

        if (runtime.isInMove()) {
            runtime.supersedeMovement(
                    afterPositionAnchor
            );
            return;
        }

        if (continuityBreakingPositionChange(
                beforePositionAnchor,
                afterPositionAnchor
        )) {
            commitPositionDiscontinuity(
                    entity,
                    afterPositionAnchor
            );
        } else {
            commitSoftPositionCorrection(
                    entity,
                    afterPositionAnchor
            );
        }
    }

    private static boolean positionChanged(
            Vec3 before,
            Vec3 after
    ) {
        return before.x != after.x
                || before.y != after.y
                || before.z != after.z;
    }

    private static boolean continuityBreakingPositionChange(
            Vec3 before,
            Vec3 after
    ) {
        double distanceSquared =
                before.distanceToSqr(after);

        return !Double.isFinite(distanceSquared)
                || distanceSquared
                > SOFT_POSITION_CORRECTION_DISTANCE_SQUARED;
    }

    /**
     * Small external correction:
     *
     * - exact geometry must follow the new position;
     * - previous movement/endpoint authority is no longer valid;
     * - previous support face may be retained only as a one-shot reacquisition
     *   candidate.
     */
    private static void commitSoftPositionCorrection(
            Entity entity,
            Vec3 newPositionAnchor
    ) {
        GravityRuntimeState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        RestingContactSnapshot previousSupport =
                runtime.restingContactSnapshot();

        EntityMovementIntegration.invalidateMovementContinuity(entity);

        if (previousSupport != null) {
            runtime.stageSoftPositionSupportRevalidation(
                    previousSupport
            );
        }

        rebuildExactBodyAfterVanillaPositionWrite(
                entity,
                newPositionAnchor
        );
    }

    /**
     * Semantic seam for {@code Entity.refreshDimensions} on a custom-body
     * entity after an independently owned Vanilla dimensions refresh.
     *
     * <p>The positionAnchor anchor is preserved and the exact body is rebuilt using the
     * already-authoritative environmental frame.  This method never samples a
     * gravity field and never reconstructs a frame from the synchronized applied
     * {@code GravityState}; the next outer movement scope owns any
     * environmental resampling.</p>
     *
     * <p>If {@code refreshDimensions} runs inside an enclosing geometry
     * transaction, that transaction must perform its own final exact-body commit
     * instead of calling this seam.</p>
     */
    public static void rebuildBodyAfterDimensionsChanged(
            Entity entity,
            Vec3 preservedPositionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(preservedPositionAnchor, "preservedPositionAnchor");

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return;
        }

        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(entity);

        GravityEntityGeometry.installFromPositionAnchor(
                entity,
                frame,
                preservedPositionAnchor
        );
    }
}
