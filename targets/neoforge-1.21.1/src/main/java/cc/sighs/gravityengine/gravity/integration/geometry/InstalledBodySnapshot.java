package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;

import java.util.Objects;

/**
 * The physical/collision representation that is actually installed on a body.
 *
 * <p>This is the only state dimension that may select a representation
 * transition. It is deliberately independent of application semantics and of
 * movement/collision routing.</p>
 *
 * <p>The owner-local {@code bodyShapeRevision} is valid only for before/after
 * comparisons of one entity. Cross-owner reconciliation uses transferable
 * representation facts: representation kind, pose, local extents and installed
 * collision axis.</p>
 */
public record InstalledBodySnapshot(
        BodyRepresentation representation,
        long bodyShapeRevision,
        Pose pose,
        double width,
        double height,
        Vec3d installedUp
) {
    /** One micrometre, matching the installed-body agreement tolerance. */
    private static final double EXTENT_EPSILON = 1.0E-6D;
    /** Squared distance below which two installed axes are the same axis. */
    private static final double AXIS_EPSILON_SQUARED = 1.0E-20D;

    public InstalledBodySnapshot {
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(pose, "pose");
        requireValidExtents(width, height);
        if (installedUp != null && !installedUp.isFinite()) {
            throw new IllegalArgumentException("non-finite installed axis");
        }
    }

    public static InstalledBodySnapshot capture(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        var runtime = component.operationState();
        EntityDimensions dimensions = GravityEntityGeometry.dimensions(entity);
        return new InstalledBodySnapshot(
                BodyRepresentation.ofAxis(runtime.installedCollisionUp()),
                runtime.bodyShapeRevision(),
                GravityEntityAccess.cast(entity).gravityengine$installedPose(),
                dimensions.width(),
                dimensions.height(),
                runtime.installedCollisionUp()
        );
    }

    /** Same-owner geometry identity, including the owner-local shape generation. */
    public boolean differsFrom(InstalledBodySnapshot other) {
        Objects.requireNonNull(other, "other");
        return bodyShapeRevision != other.bodyShapeRevision
                || representationFactsDifferFrom(
                        other.representation,
                        other.pose,
                        other.width,
                        other.height,
                        other.installedUp
                );
    }

    /**
     * Cross-owner comparison using only facts that can be transferred on the
     * wire. The shape revision is intentionally excluded because it is
     * owner-local.
     */
    public boolean representationFactsDifferFrom(
            BodyRepresentation otherRepresentation,
            Pose otherPose,
            double otherWidth,
            double otherHeight,
            Vec3d otherInstalledUp
    ) {
        Objects.requireNonNull(otherRepresentation, "otherRepresentation");
        Objects.requireNonNull(otherPose, "otherPose");
        requireValidExtents(otherWidth, otherHeight);
        return representation != otherRepresentation
                || pose != otherPose
                || Math.abs(width - otherWidth) > EXTENT_EPSILON
                || Math.abs(height - otherHeight) > EXTENT_EPSILON
                || axisDiffers(installedUp, otherInstalledUp);
    }

    private static void requireValidExtents(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height)
                || !(width > 0.0D) || !(height > 0.0D)) {
            throw new IllegalArgumentException(
                    "installed body extents must be finite and positive: "
                            + width + "x" + height
            );
        }
    }

    private static boolean axisDiffers(Vec3d first, Vec3d second) {
        if (first == null || second == null) return first != second;
        return first.distanceSquared(second) > AXIS_EPSILON_SQUARED;
    }
}
