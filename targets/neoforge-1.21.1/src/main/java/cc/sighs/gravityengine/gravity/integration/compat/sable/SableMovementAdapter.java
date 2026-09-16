package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelSupportEvidence;
import cc.sighs.gravityengine.gravity.runtime.SubLevelMovementPolicy;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Exact Sable 2.0.5 movement adapter. Loaded only through
 * {@link SableMovementCompatibility}.
 */
final class SableMovementAdapter {
    private static final String SABLE_ROOT = "dev.ryanhcode.sable.Sable";

    private SableMovementAdapter() {}

    static ExternalSubLevelMoveEvidence capture(
            Entity entity,
            Vec3 parentSolverMovement
    ) {
        if (!(entity instanceof EntityMovementExtension extension)) {
            return null;
        }
        SubLevelEntityCollision.CollisionInfo info =
                extension.sable$getCollisionInfo();
        if (info == null
                || info.motion == null
                || !info.motion.equals(parentSolverMovement)) {
            return null;
        }

        Vec3 currentVelocity = entity.getDeltaMovement();
        Vec3 preMoveVelocity = info.preDeltaMovement == null
                ? currentVelocity
                : info.preDeltaMovement;
        boolean serverPlayerTrackingFastPath =
                entity instanceof ServerPlayer
                        && info.trackingSubLevel != null
                        && info.firstCollisions == null;

        if (serverPlayerTrackingFastPath
                && GravityInfluencePolicy
                .usesCustomBody(entity)) {
            throw new IllegalStateException(
                    "Sable ServerPlayer tracking fast path survived "
                            + "GravityEngine custom collision adaptation; "
                            + "SubLevel hard collision would be absent"
            );
        }

        boolean worldYVelocitySuppressed =
                serverPlayerTrackingFastPath
                        && worldYWasSuppressed(
                                currentVelocity,
                                preMoveVelocity
                        );
        boolean hasSubLevelContact =
                info.firstCollisions != null
                        && !info.firstCollisions.isEmpty();

        Optional<ExternalSubLevelSupportEvidence> supportEvidence =
                captureSupportEvidence(
                        entity,
                        info
                );

        GravityDebugLog.log(
                entity,
                "sable-sublevel-evidence",
                "collisionInfoId=%08x customBody=%s "
                        + "serverPlayerTrackingFastPathInferred=%s "
                        + "firstCollisionsNull=%s firstCollisionCount=%d "
                        + "trackingSubLevelPresent=%s preTrackingSubLevelPresent=%s "
                        + "hasSubLevelContact=%s verticalCollisionBelow=%s supportEvidence=%s",
                System.identityHashCode(info),
                GravityInfluencePolicy.usesCustomBody(entity),
                serverPlayerTrackingFastPath,
                info.firstCollisions == null,
                info.firstCollisions == null ? -1 : info.firstCollisions.size(),
                info.trackingSubLevel != null,
                info.preTrackingSubLevel != null,
                hasSubLevelContact,
                info.verticalCollisionBelow,
                supportEvidence.isPresent()
        );

        return new ExternalSubLevelMoveEvidence(
                parentSolverMovement,
                preMoveVelocity,
                info.verticalCollisionBelow,
                info.subLevelHorizontalCollision,
                info.verticalCollision,
                info.verticalCollisionBelow,
                info.minorHorizontalCollision,
                hasSubLevelContact,
                serverPlayerTrackingFastPath,
                worldYVelocitySuppressed,
                subLevelId((Object) info.trackingSubLevel),
                subLevelId((Object) info.preTrackingSubLevel),
                supportEvidence
        );
    }

    /**
     * Converts Sable's completed first-collision publication for the selected
     * tracking body into GravityEngine's neutral support evidence.
     *
     * <p>Only a real, non-horizontal first contact whose published normal
     * agrees with the authoritative operation GravityFrame becomes support.
     * The representative point is deliberately not promoted into a reusable
     * block-face witness.</p>
     */
    private static Optional<ExternalSubLevelSupportEvidence>
    captureSupportEvidence(
            Entity entity,
            SubLevelEntityCollision.CollisionInfo info
    ) {
        if (!info.verticalCollisionBelow
                || info.trackingSubLevel == null
                || info.firstCollisions == null) {
            return Optional.empty();
        }

        SubLevelEntityCollision.FirstCollisionInfo contact =
                info.firstCollisions.get(
                        info.trackingSubLevel
                );

        /*
         * FirstCollisionInfo records the first collision with one SubLevel.
         * If that first event was horizontal, it is not safe to reinterpret it
         * as the later floor collision which selected trackingSubLevel.
         */
        if (contact == null
                || contact.horizontal()) {
            return Optional.empty();
        }

        Vector3d normalJoml =
                new Vector3d(
                        contact.globalDirection()
                );

        if (!normalJoml.isFinite()
                || !(normalJoml.lengthSquared()
                > 1.0E-24D)) {
            return Optional.empty();
        }

        normalJoml.normalize();

        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        if (!runtime.isInMove()) {
            return Optional.empty();
        }

        Vec3 up =
                runtime.activeFrame().up();

        Vec3 normal =
                new Vec3(
                        normalJoml.x,
                        normalJoml.y,
                        normalJoml.z
                );

        /*
         * Sable classified this as vertical relative to the collision
         * orientation supplied above. Recheck against the authoritative
         * operation frame before publishing it as support.
         */
        if (normal.dot(up) <= 0.60D) {
            return Optional.empty();
        }

        Optional<UUID> subLevelId =
                subLevelId((Object) info.trackingSubLevel);

        if (subLevelId.isEmpty()) {
            return Optional.empty();
        }

        Vector3d worldPoint =
                subLevelLocalToWorld(
                        info.trackingSubLevel,
                        contact.localLocation()
                );

        Vector3d velocity =
                subLevelSurfaceVelocity(
                        entity.level(),
                        info.trackingSubLevel,
                        contact.localLocation()
                );

        /*
         * Sable getVelocity is blocks/second in this path.
         * GravityEngine translational velocities are blocks/tick.
         */
        velocity.mul(1.0D / 20.0D);

        return Optional.of(
                new ExternalSubLevelSupportEvidence(
                        subLevelId.get(),
                        normal,
                        new Vec3(
                                velocity.x,
                                velocity.y,
                                velocity.z
                        ),
                        new Vec3(
                                worldPoint.x,
                                worldPoint.y,
                                worldPoint.z
                        )
                )
        );
    }

    /**
     * {@code SubLevel.logicalPose()} returns the nested companion API's pose
     * type and {@code SubLevel} itself implements a companion interface; both
     * are delivered through Sable's nested jar-in-jar, which is not a direct
     * Gradle compile dependency (see {@link #isRemoved(Object)}). The single
     * local-to-world conversion therefore stays a narrow reflective call
     * inside this adapter and no pose handle is ever retained.
     */
    private static Vector3d subLevelLocalToWorld(
            Object subLevel,
            Vector3dc local
    ) {
        try {
            Object pose = subLevel.getClass()
                    .getMethod("logicalPose")
                    .invoke(subLevel);
            if (pose == null) {
                throw new IllegalStateException(
                        "Sable SubLevel had no logical pose during collision"
                );
            }
            return (Vector3d) pose.getClass()
                    .getMethod(
                            "transformPosition",
                            Vector3d.class
                    )
                    .invoke(pose, new Vector3d(local));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Sable 2.0.5 SubLevel.logicalPose/"
                            + "Pose3dc.transformPosition contract changed",
                    failure
            );
        }
    }

    /**
     * Material-point velocity of the selected SubLevel at one local point.
     *
     * <p>{@code Sable.HELPER.getVelocity(...)} cannot be referenced directly:
     * its declaring type implements the nested companion interface, which is
     * not a Gradle compile dependency. The version-matched 2.0.5 overload is
     * resolved by shape at the call site and no helper or handle is
     * retained.</p>
     */
    private static Vector3d subLevelSurfaceVelocity(
            Level level,
            Object subLevel,
            Vector3dc local
    ) {
        Object helper = sableHelper();

        for (Method method : helper.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("getVelocity")
                    || parameters.length != 4
                    || !parameters[0].equals(Level.class)
                    || !parameters[1].isInstance(subLevel)
                    || !parameters[2].equals(Vector3dc.class)
                    || !parameters[3].equals(Vector3d.class)) {
                continue;
            }
            try {
                return (Vector3d) method.invoke(
                        helper,
                        level,
                        subLevel,
                        local,
                        new Vector3d()
                );
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "Sable 2.0.5 SubLevel surface velocity call failed",
                        failure
                );
            }
        }

        throw new IllegalStateException(
                "Sable 2.0.5 getVelocity(Level, SubLevelAccess, "
                        + "Vector3dc, Vector3d) contract changed"
        );
    }

    private static Object sableHelper() {
        try {
            return Class.forName(
                            SABLE_ROOT,
                            false,
                            SableMovementAdapter.class.getClassLoader()
                    )
                    .getField("HELPER")
                    .get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Sable 2.0.5 Sable.HELPER contract changed",
                    failure
            );
        }
    }

    static void restoreTrackingAfterParentWorldWorldYChange(
            Entity entity
    ) {
        if (!(entity instanceof EntityMovementExtension extension)) {
            return;
        }
        SubLevelEntityCollision.CollisionInfo info =
                extension.sable$getCollisionInfo();
        if (info == null) {
            return;
        }
        /*
         * Mirror Sable's own field-selection order immediately before its
         * parent-world world-Y comparison. The comparison is the only clear
         * this adapter is allowed to undo.
         */
        SubLevel intended;
        if (info.trackingSubLevel != null) {
            intended = info.verticalCollisionBelow
                    ? info.trackingSubLevel
                    : info.preTrackingSubLevel;
        } else {
            intended = entity instanceof ServerPlayer
                    ? info.preTrackingSubLevel
                    : null;
        }
        if (intended == null) {
            return;
        }
        boolean shouldRestore =
                SubLevelMovementPolicy.shouldRestoreTracking(
                                info.trackingSubLevel != null,
                                info.verticalCollisionBelow,
                                info.preTrackingSubLevel != null,
                                extension.sable$getTrackingSubLevel() != null,
                                entity instanceof ServerPlayer,
                                isRemoved((Object) intended)
                        );
        if (!shouldRestore) {
            return;
        }
        /*
         * Sable cleared this field only after its SubLevel solve had already
         * selected the same body. The remaining clear path in 2.0.5 is the
         * parent-world world-Y inequality, which is not a valid reason under
         * an arbitrary GravityFrame.
         */
        extension.sable$setTrackingSubLevel(intended);
    }

    static boolean isInheritedSupportTransport(
            Entity entity,
            Vec3 movement
    ) {
        if (!(entity instanceof EntityMovementExtension extension)) {
            return false;
        }

        SubLevelEntityCollision.CollisionInfo info =
                extension.sable$getCollisionInfo();

        return info != null
                && info.inheritedMotion != null
                && info.inheritedMotion.equals(movement);
    }

    static boolean finalizedMinorHorizontalCollision(
            Entity entity,
            boolean baseMinorHorizontalCollision
    ) {
        if (!(entity instanceof EntityMovementExtension extension)) {
            return baseMinorHorizontalCollision;
        }

        SubLevelEntityCollision.CollisionInfo info =
                extension.sable$getCollisionInfo();

        return info == null
                ? baseMinorHorizontalCollision
                : baseMinorHorizontalCollision
                  || info.minorHorizontalCollision;
    }

    /**
     * {@code SubLevel.isRemoved()} is inherited from Sable's nested companion
     * API, which is not a direct Gradle compile dependency. Reflection keeps
     * this exact 2.0.5 guard inside the adapter without adding a live foreign
     * handle or widening the main compile classpath.
     */
    private static boolean isRemoved(Object subLevel) {
        try {
            return (Boolean) subLevel.getClass()
                    .getMethod("isRemoved")
                    .invoke(subLevel);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Sable 2.0.5 SubLevel.isRemoved contract changed",
                    failure
            );
        }
    }

    private static boolean worldYWasSuppressed(
            Vec3 current,
            Vec3 before
    ) {
        return current.y == 0.0D
                && before.y < 0.0D
                && Double.isFinite(before.y)
                && current.x == before.x
                && current.z == before.z;
    }

    private static Optional<UUID> subLevelId(Object subLevel) {
        if (subLevel == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    (UUID) subLevel.getClass()
                            .getMethod("getUniqueId")
                            .invoke(subLevel)
            );
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Sable 2.0.5 SubLevel.getUniqueId contract changed",
                    failure
            );
        }
    }
}
