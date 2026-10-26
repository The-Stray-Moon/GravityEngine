package cc.sighs.gravityengine.gravity.minecraft.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Installed physical body authority and custom-gravity geometry construction.
 *
 * Contract:
 * - P = entity.position() is Vanilla's network/world position anchor;
 * - C = P + WORLD_UP * dimensions.height/2, independent of frame and Qbody;
 * - Fg = C - gravityUp * dimensions.height/2 is a support reference,
 *   not a support witness or the anatomical lower endpoint of arbitrary Qbody;
 * - installed non-default reference geometry uses a rounded axial {@code CharacterCapsule} built from
 *   installed dimensions, native positionAnchor, and the installed collision axis;
 * - Vanilla-owned geometry uses its actual AABB as an exact box, independently of solver routing;
 * - for custom capsules Entity#getBoundingBox is only the conservative enclosing AABB;
 * - the exact body and public AABB always share the same center.
 */
public final class GravityEntityGeometry {
    /** Internal exact-body/proxy agreement tolerance (one micrometre). */
    public static final double GEOMETRY_FRAME_EPSILON = 1.0E-6D;
    public static final double GEOMETRY_FRAME_EPSILON_SQUARED =
            GEOMETRY_FRAME_EPSILON * GEOMETRY_FRAME_EPSILON;

    private GravityEntityGeometry() {}

    /** Vanilla's current dimensions, including NeoForge Size event overrides. */
    public static EntityDimensions dimensions(Entity entity) {
        return GravityEntityAccess.cast(entity).gravityengine$installedDimensions();
    }

    public record InterpolatedBody(Vec3 positionAnchor, Vec3 center, GravityFrame frame) {}

    public static Vec3 positionAnchor(Entity entity) {
        return entity.position();
    }

    /** P -> C. Height is EntityDimensions height, never enclosing-AABB height. */
    public static Vec3 bodyCenterFromPositionAnchor(Vec3 positionAnchor, double height) {
        return positionAnchor.add(0.0D, height * 0.5D, 0.0D);
    }

    /** C -> P, including after a dimensions transaction. */
    public static Vec3 positionAnchorFromBodyCenter(Vec3 bodyCenter, double height) {
        return bodyCenter.subtract(0.0D, height * 0.5D, 0.0D);
    }

    /** C -> Fg. This reference point does not establish physical support. */
    public static Vec3 gravityFeetFromBodyCenter(Vec3 bodyCenter, double height, Vec3 gravityDown) {
        return bodyCenter.add(gravityDown.normalize().scale(height * 0.5D));
    }

    /** Fg -> C; presentation/reference conversion only, never network decoding. */
    public static Vec3 bodyCenterFromGravityFeet(Vec3 gravityFeet, double height, Vec3 gravityDown) {
        return gravityFeet.subtract(gravityDown.normalize().scale(height * 0.5D));
    }

    public static Vec3 gravityFeet(Entity entity, GravityFrame frame) {
        return gravityFeetFromBodyCenter(bodyCenter(entity),
                dimensions(entity).height(),
                MinecraftMathAdapter.toMinecraft(frame.down()));
    }

    /**
     * Broad-phase center of the installed enclosing proxy. Exact custom
     * bodies must use {@link #bodyCenter(Entity)} so orientation
     * and dimensions remain authoritative.
     */
    public static Vec3 proxyCenter(Entity entity) {
        return entity.getBoundingBox().getCenter();
    }

    public static Vec3 bodyCenter(Entity entity) {
        return bodyCenterFromPositionAnchor(entity.position(), dimensions(entity).height());
    }

    /**
     * Exact custom collision body from installed dimensions, native anchor and installed axis. The
     * entity convenience lives here (the physics adapter layer) so the pure
     * geometry model stays free of {@code Entity} dependencies.
     */
    public static CharacterCapsule exactBody(Entity entity) {
        return candidateBody(dimensions(entity), entity.position(), installedUp(entity));
    }

    /** Exact center of the currently installed custom body. */
    public static Vec3 installedBodyCenter(Entity entity) {
        installedUp(entity);
        return bodyCenter(entity);
    }

    /** Installed geometry authority only; never samples reference or attitude. */
    public static Vec3d installedUp(Entity entity) {
        Vec3d up = GravityEntityAccess.cast(entity).gravityengine$gravityComponent()
                .operationState().installedCollisionUp();
        if (up == null) throw new IllegalStateException("custom body has no installed collision axis");
        return up;
    }

    public static Vec3 eyePosition(Entity entity, Vec3 center, Vec3 down) {
        return eyePosition(
                entity,
                center,
                GravityFrame.downOnly(
                        MinecraftMathAdapter.toVec3d(down)
                )
        );
    }

    public static Vec3 eyePosition(Entity entity, Vec3 center, GravityFrame frame) {
        double offsetFromCenter = entity.getEyeHeight() - entity.getBbHeight() * 0.5D;
        return center.add(
                MinecraftMathAdapter.toMinecraft(frame.up())
                        .scale(offsetFromCenter));
    }

    /**
     * Physical eye position follows the installed collision axis.
     */
    public static Vec3 eyePosition(Entity entity) {
        GravityFrame frame =
                GravityFrameAccess.installedLocomotionFrame(entity);
        return eyePosition(entity, bodyCenter(entity), frame);
    }

    /** Interpolated eye position for a partial-tick presentation query. */
    public static Vec3 eyePosition(Entity entity, float partialTick) {
        InterpolatedBody body = interpolatedBody(
                entity,
                GravityFrameAccess.installedLocomotionFrame(entity),
                partialTick
        );
        return eyePosition(entity, body.center(), body.frame());
    }

    public static Vec3 eyePosition(Vec3 center, double bodyHeight, double eyeHeight, Vec3 down) {
        double offsetFromCenter = eyeHeight - bodyHeight * 0.5D;
        return center.add(down.normalize().reverse().scale(offsetFromCenter));
    }

    /**
     * Produces one interpolated frame and center for camera, model and debug
     * rendering. The caller supplies the frame; no gravity sampling occurs here.
     */
    public static InterpolatedBody interpolatedBody(Entity entity, GravityFrame frame, float partialTick) {
        Vec3 positionAnchor = interpolatedPositionAnchor(entity, partialTick);

        Vec3 center = bodyCenterFromPositionAnchor(positionAnchor, dimensions(entity).height());

        return new InterpolatedBody(positionAnchor, center, frame);
    }

    public static GravityFrame frameAtPositionAnchor(Entity entity, GravityState state, Vec3 positionAnchor) {
        Vec3 center = bodyCenterFromPositionAnchor(positionAnchor, dimensions(entity).height());
        return GravityFrame.fromState(
                state,
                MinecraftMathAdapter.toVec3d(center)
        );
    }

    /** Installed physical representation, independent of collision solver routing.
     * External geometry does not turn a Vanilla box into a custom capsule. */
    public static CollisionBody body(Entity entity) {
        if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(entity)) {
            return cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(
                    MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox()));
        }
        return exactBody(entity);
    }

    /** Sole production constructor from Vanilla P, dimensions and collision up. */
    public static CharacterCapsule candidateBody(EntityDimensions dimensions, Vec3 positionAnchor, Vec3 up) {
        return candidateBody(
                dimensions,
                positionAnchor,
                MinecraftMathAdapter.toVec3d(up)
        );
    }

    public static CharacterCapsule candidateBody(
            EntityDimensions dimensions,
            Vec3 positionAnchor,
            Vec3d up
    ) {
        return characterBodyAtCenter(
                dimensions.width(),
                dimensions.height(),
                bodyCenterFromPositionAnchor(
                        positionAnchor,
                        dimensions.height()
                ),
                up
        );
    }

    public static CharacterCapsule characterBodyAtCenter(double width, double height, Vec3 center, Vec3 up) {
        return characterBodyAtCenter(
                width,
                height,
                center,
                MinecraftMathAdapter.toVec3d(up)
        );
    }

    public static CharacterCapsule characterBodyAtCenter(
            double width,
            double height,
            Vec3 center,
            Vec3d up
    ) {
        CharacterDimensionPolicy.requireCapsule(width, height);

        return CharacterCapsule.fromDimensions(
                MinecraftMathAdapter.toVec3d(center),
                width,
                height,
                up
        );
    }

    /** Install an authorized axis candidate at the supplied native anchor. */
    public static void installFromPositionAnchor(Entity entity, Vec3d up, Vec3 positionAnchor) {
        commitCustomBody(entity, up, positionAnchor,
                candidateBody(dimensions(entity), positionAnchor, up));
    }

    /**
     * Repair the proxy after native translation using the installed axis.
     * Translation cannot choose a new collider orientation or write position again.
     */
    public static void reanchorAfterVanillaTranslation(Entity entity) {
        CollisionBody body = exactBody(entity);
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (var ignored = runtime.openGeometryMutation()) {
            entity.setBoundingBox(MinecraftCollisionGeometryAdapter.toMinecraft(body.enclosingAabb()));
        }
    }

    /** P from an exact body's authoritative local height, never its rotated proxy. */
    public static Vec3 positionAnchorFromBody(CharacterCapsule body) {
        return positionAnchorFromBodyCenter(MinecraftMathAdapter.toMinecraft(body.center()),
                body.totalHeight());
    }

    /**
     * Single commit point for all live custom bodies. The supplied body must
     * already represent {@code positionAnchor + WORLD_UP * height/2}; the public AABB is
     * derived from it and never feeds back into the positionAnchor anchor.
     */
    public static void commitCustomBody(
            Entity entity,
            Vec3d up,
            Vec3 positionAnchor,
            CollisionBody body
    ) {
        if (entity == null || up == null || positionAnchor == null || body == null) {
            throw new NullPointerException("custom body commit arguments");
        }
        requireUnitAxis(up);
        CollisionBody expected = candidateBody(dimensions(entity), positionAnchor, up);
        if (!expected.geometricallyEquals(body)
                || !aabbsMatch(expected.enclosingAabb(), body.enclosingAabb())) {
            throw new IllegalArgumentException("custom body does not match anchor/dimensions/axis: " + body);
        }
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored = runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(MinecraftCollisionGeometryAdapter.toMinecraft(body.enclosingAabb()));
            runtime.setInstalledCollisionAxis(up);
        }
        requireInstalledGeometry(entity, "custom body commit");
    }

    private static void requireUnitAxis(Vec3d up) {
        if (!up.isFinite() || Math.abs(up.lengthSquared() - 1.0D) > 1.0E-10D)
            throw new IllegalArgumentException("collision axis must be finite and unit length");
    }

    /** Central vanilla/proxy commit; Vanilla ownership has no installed custom collision axis. */
    public static void commitVanillaBody(Entity entity, Vec3 positionAnchor, AABB box) {
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored = runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(box);
            runtime.clearInstalledCollisionAxis();
        }
    }

    /** Transaction rollback that restores geometry ownership metadata as well. */
    public static void restoreGeometry(
            Entity entity,
            Vec3 positionAnchor,
            AABB box,
            Vec3d installedUp
    ) {
        if (installedUp != null) requireUnitAxis(installedUp);
        if (cc.sighs.gravityengine.gravity.geometry.BodyRepresentation.ofAxis(installedUp).isExact()) {
            CollisionBody body = candidateBody(
                    dimensions(entity), positionAnchor, installedUp
            );
            if (!aabbsMatch(body.enclosingAabb(), box)) {
                throw new IllegalStateException(
                        "saved custom geometry does not match its collision axis"
                );
            }
            commitCustomBody(entity, installedUp, positionAnchor, body);
        } else {
            commitVanillaBody(entity, positionAnchor, box);
            if (installedUp != null) GravityEntityAccess.cast(entity).gravityengine$gravityComponent()
                    .operationState().setInstalledCollisionAxis(installedUp);
        }
    }

    /**
     * Exact rollback restore of a previously committed custom body and its
     * enclosing AABB.
     *
     * <p>Unlike {@link #restoreGeometry} this does not re-derive the AABB
     * from current dimensions/positionAnchor: the captured AABB is the authoritative
     * rollback value and is installed verbatim under the geometry mutation
     * scope.  The saved axis is restored directly; actor attitude and
     * environmental tangent history are not rollback geometry.</p>
     */
    public static void restoreExactGeometry(
            Entity entity,
            Vec3 positionAnchor,
            AABB box,
            Vec3d installedUp
    ) {
        if (entity == null || positionAnchor == null || box == null) {
            throw new NullPointerException(
                    "exact geometry restore arguments"
            );
        }
        if (!Double.isFinite(positionAnchor.x) || !Double.isFinite(positionAnchor.y)
                || !Double.isFinite(positionAnchor.z)
                || !Double.isFinite(box.minX) || !Double.isFinite(box.minY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxX)
                || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
            throw new IllegalArgumentException(
                    "exact geometry restore requires finite values"
            );
        }
        if (installedUp != null) requireUnitAxis(installedUp);
        GravityOperationState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored =
                     runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(box);
            if (installedUp != null) {
                runtime.setInstalledCollisionAxis(installedUp);
            } else {
                runtime.clearInstalledCollisionAxis();
            }
        }
    }

    public static boolean geometryMatchesAxis(Entity entity, Vec3d up) {
        if (entity == null || up == null) return false;
        CollisionBody exact = candidateBody(dimensions(entity), entity.position(), up);
        return centersMatch(MinecraftMathAdapter.toMinecraft(exact.center()), entity.getBoundingBox().getCenter())
                && aabbsMatch(exact.enclosingAabb(), entity.getBoundingBox());
    }

    /** Checks the installed body against its proxy without substituting a caller's frame. */
    public static void requireInstalledGeometry(Entity entity, String context) {
        Vec3d up = installedUp(entity);
        if (geometryMatchesAxis(entity, up)) return;
        throw new IllegalStateException(context + ": installed body/proxy mismatch: anchor=" + entity.position()
                + ", axis=" + up + ", boundingBox=" + entity.getBoundingBox());
    }

    static void requireMatchingCenters(Vec3 proxyCenter, Vec3 exactCenter) {
        if (!centersMatch(proxyCenter, exactCenter)) {
            Vec3 drift = proxyCenter.subtract(exactCenter);
            throw new IllegalStateException(
                    "pre-move custom geometry/frame mismatch: proxyCenter="
                            + proxyCenter + ", exactCenter=" + exactCenter
                            + ", drift=" + drift
                            + ", driftMagnitude=" + drift.length()
            );
        }
    }

    private static boolean centersMatch(Vec3 first, Vec3 second) {
        return first.subtract(second).lengthSqr()
                <= GEOMETRY_FRAME_EPSILON_SQUARED;
    }

    private static boolean aabbsMatch(AABB first, AABB second) {
        return approximatelyEqual(first.minX, second.minX)
                && approximatelyEqual(first.minY, second.minY)
                && approximatelyEqual(first.minZ, second.minZ)
                && approximatelyEqual(first.maxX, second.maxX)
                && approximatelyEqual(first.maxY, second.maxY)
                && approximatelyEqual(first.maxZ, second.maxZ);
    }

    private static boolean aabbsMatch(Aabb3d first, AABB second) {
        return aabbsMatch(
                MinecraftCollisionGeometryAdapter.toMinecraft(first), second);
    }

    private static boolean aabbsMatch(Aabb3d first, Aabb3d second) {
        return aabbsMatch(
                MinecraftCollisionGeometryAdapter.toMinecraft(first),
                MinecraftCollisionGeometryAdapter.toMinecraft(second));
    }

    private static boolean approximatelyEqual(double first, double second) {
        return Math.abs(first - second) <= GEOMETRY_FRAME_EPSILON;
    }

    /** Interpolated Vanilla world/network position anchor P used by presentation consumers. */
    public static Vec3 interpolatedPositionAnchor(Entity entity, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ())
        );
    }
}
