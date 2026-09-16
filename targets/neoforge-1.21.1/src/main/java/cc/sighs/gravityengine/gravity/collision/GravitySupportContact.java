package cc.sighs.gravityengine.gravity.collision;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3d;


import java.util.Objects;

/**
 * Immutable snapshot of a physical support contact. Walkability is a separate
 * continuous-traversal classification, not a requirement for load bearing.
 *
 * <p>The normal points out of the support toward the character. Surface
 * velocity is sampled by the same collision operation. {@code contactPoint}
 * is always a finite world-space point, but only
 * {@link SupportGeometryKind#TRUSTED_CURRENT_BLOCK_FACE} guarantees that the
 * normal/witness pair defines a plane that may be reused across ticks.</p>
 *
 * <p>This distinction is intentional: swept SAT contacts may carry only a
 * representative point, which is sufficient for same-operation collision
 * response but is not necessarily a geometric support-plane witness.
 * Likewise a manifold normal paired with one member-face witness does not
 * define one real plane.</p>
 */
public record GravitySupportContact(
        Vector3d normal,
        Vector3d surfaceVelocity,
        Vector3d contactPoint,
        SupportGeometryKind geometryKind,
        SupportFaceIdentity faceIdentity
) {
    public GravitySupportContact(Vector3d normal, Vector3d surfaceVelocity, Vector3d contactPoint,
                                 SupportGeometryKind geometryKind) {
        this(normal, surfaceVelocity, contactPoint, geometryKind, null);
    }

    private static final double NORMAL_EPSILON = 1.0E-6D;

    public enum SupportGeometryKind {
        /**
         * Final-position GroundProbe support on one static world-axis
         * BlockObstacle face.
         *
         * <p>The normal/witness pair is proven against the selected finite
         * obstacle Aabb3d face, including rounded-capsule contacts. This is the only support kind
         * allowed to authorize cross-tick PRESERVE_SUPPORT.</p>
         */
        TRUSTED_CURRENT_BLOCK_FACE,

        /** A selected finite face of a moving exact obstacle. */
        REAL_OBSTACLE_FACE,

        /**
         * Ordinary single collision/sweep contact.
         *
         * <p>This includes rounded block edge/corner support. The point is valid same-operation collision data, but it is not
         * guaranteed to lie on the obstacle support plane and therefore must
         * never authorize cross-tick re-anchoring.</p>
         */
        SWEEP_CONTACT,

        /**
         * Composite voxel support.
         *
         * <p>The normal is synthetic and the point belongs only to one
         * representative member contact, so no single support plane exists.</p>
         */
        MANIFOLD
    }

    public GravitySupportContact {
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(surfaceVelocity, "surfaceVelocity");
        Objects.requireNonNull(contactPoint, "contactPoint");
        Objects.requireNonNull(geometryKind, "geometryKind");

        requireFinite(normal, "normal");
        requireFinite(surfaceVelocity, "surfaceVelocity");
        requireFinite(contactPoint, "contactPoint");

        if (Math.abs(normal.lengthSquared() - 1.0D) > NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "support normal must be normalized: " + normal
            );
        }

        /*
         * Vector3d is mutable. Snapshot ownership therefore requires both
         * defensive construction and defensive accessors.
         */
        normal = new Vector3d(normal);
        surfaceVelocity = new Vector3d(surfaceVelocity);
        contactPoint = new Vector3d(contactPoint);
    }

    @Override
    public Vector3d normal() {
        return new Vector3d(normal);
    }

    @Override
    public Vector3d surfaceVelocity() {
        return new Vector3d(surfaceVelocity);
    }

    @Override
    public Vector3d contactPoint() {
        return new Vector3d(contactPoint);
    }

    /**
     * Converts an ordinary collision contact into a same-operation support
     * snapshot.
     *
     * <p>Its point deliberately remains untrusted for cross-tick plane
     * preservation.</p>
     */
    public static GravitySupportContact fromSweepContact(
            CollisionContact contact
    ) {
        Objects.requireNonNull(contact, "contact");

        return fromSweepContact(
                contact.normal(),
                contact
        );
    }

    /**
     * Same as {@link #fromSweepContact(CollisionContact)}, with a caller-
     * selected support normal.
     */
    public static GravitySupportContact fromSweepContact(
            Vector3d supportNormal,
            CollisionContact contact
    ) {
        Objects.requireNonNull(supportNormal, "supportNormal");
        Objects.requireNonNull(contact, "contact");

        return new GravitySupportContact(
                supportNormal,
                contact.surfaceVelocity(),
                contact.point(),
                SupportGeometryKind.SWEEP_CONTACT
        );
    }

    /**
     * Whether this normal/witness pair is allowed to survive the current solve
     * as cross-tick resting-plane authority.
     */
    public boolean planePreservationEligible() {
        return geometryKind
                == SupportGeometryKind.TRUSTED_CURRENT_BLOCK_FACE
                && blockFaceIndex(normal) >= 0
                && (faceIdentity == null || faceIdentity.provesBlockFace(normal, contactPoint));
    }

    /** An actual world-axis face, never the dominant component of a rounded normal. */
    public static int blockFaceIndex(Vector3d normal) {
        int axis = TerrainTraversalPolicy.worldAxisDimension(normal);
        return axis < 0 ? -1 : axis * 2 + (normal.get(axis) > 0 ? 1 : 0);
    }

    /** Finite piece evidence required before a contact can own a reusable plane. */
    public static boolean matchesBlockFace(Aabb3d bounds, Vector3d normal, Vector3d witness) {
        if (blockFaceIndex(normal) < 0) return false;
        Vector3d onFace = blockFaceWitness(bounds, normal, witness);
        int axis = blockFaceIndex(normal) / 2;
        double tangentMargin = CollisionTolerances.CONTACT_SKIN + CollisionTolerances.CONTACT_SLOP;
        for (int component = 0; component < 3; component++) {
            double tolerance = component == axis ? CollisionTolerances.CONTACT_SLOP : tangentMargin;
            if (Math.abs(onFace.get(component) - witness.get(component)) > tolerance) return false;
        }
        return true;
    }

    /**
     * Exact witness on one axis-aligned BlockObstacle face.
     *
     * <p>Package-private intentionally so focused collision tests can validate
     * the plane construction.</p>
     */
    static Vector3d blockFaceWitness(
            Aabb3d bounds,
            Vector3d normal,
            Vector3d seed
    ) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(seed, "seed");

        int axis =
                TerrainTraversalPolicy.worldAxisDimension(
                        normal
                );

        if (axis < 0) {
            throw new IllegalArgumentException(
                    "block-face witness requires world-axis normal: "
                            + normal
            );
        }

        /*
         * The tangent coordinates do not affect the infinite plane equation,
         * but keeping them inside the obstacle bounds produces a meaningful
         * geometric witness for diagnostics and future finite-face tests.
         */
        double x = clamp(
                seed.x,
                bounds.minX(),
                bounds.maxX()
        );
        double y = clamp(
                seed.y,
                bounds.minY(),
                bounds.maxY()
        );
        double z = clamp(
                seed.z,
                bounds.minZ(),
                bounds.maxZ()
        );

        return switch (axis) {
            case 0 -> new Vector3d(
                    normal.x > 0.0D
                            ? bounds.maxX()
                            : bounds.minX(),
                    y,
                    z
            );

            case 1 -> new Vector3d(
                    x,
                    normal.y > 0.0D
                            ? bounds.maxY()
                            : bounds.minY(),
                    z
            );

            case 2 -> new Vector3d(
                    x,
                    y,
                    normal.z > 0.0D
                            ? bounds.maxZ()
                            : bounds.minZ()
            );

            default -> throw new IllegalStateException(
                    "unexpected block-face axis: " + axis
            );
        };
    }

    private static double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(
                min,
                Math.min(max, value)
        );
    }

    private static void requireFinite(
            Vector3d value,
            String name
    ) {
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
