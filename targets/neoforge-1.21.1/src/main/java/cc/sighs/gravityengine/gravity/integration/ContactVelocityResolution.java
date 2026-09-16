package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.ContactConstraintProjector;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * Explicit outcome of one contact-velocity resolution.
 *
 * <p>A feasible resolution is the exact Euclidean projection of the desired
 * velocity onto every simultaneous unilateral constraint
 * {@code n_i dot v >= b_i}. An infeasible moving-contact set (contradictory
 * affine bounds) is a normal physics outcome at the contact layer, never a
 * programmer invariant failure and never a reason to reject translation. For
 * that case this value carries a deterministic non-penetrating fallback
 * velocity: the projection onto {@code n_i dot v >= 0}, which preserves the
 * unconstrained tangent component, removes only inward contact velocity, and
 * never adds momentum.</p>
 *
 * <p>Translation authority belongs to the collision solver alone. This value
 * is a velocity-response result and has no position or acceptance semantics.</p>
 */
public final class ContactVelocityResolution {
    private final boolean feasible;
    private final boolean fallback;
    private final Vec3 velocity;
    private final Vec3 desiredVelocity;
    private final List<ContactConstraintProjector.Constraint> constraints;

    private ContactVelocityResolution(
            boolean feasible,
            boolean fallback,
            Vec3 velocity,
            Vec3 desiredVelocity,
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        this.feasible = feasible;
        this.fallback = fallback;
        this.velocity = velocity == null
                ? null
                : new Vec3(velocity.x, velocity.y, velocity.z);
        this.desiredVelocity = new Vec3(
                Objects.requireNonNull(
                        desiredVelocity,
                        "desiredVelocity"
                ).x,
                desiredVelocity.y,
                desiredVelocity.z
        );
        this.constraints = List.copyOf(
                Objects.requireNonNull(
                        constraints,
                        "constraints"
                )
        );
        if (feasible && this.velocity == null) {
            throw new IllegalArgumentException(
                    "feasible resolution requires a velocity"
            );
        }
    }

    /**
     * The contact set admitted a legal velocity. {@code constraints} is the
     * exact snapshot that was solved, retained for diagnostics only.
     */
    public static ContactVelocityResolution feasible(
            Vec3 velocity,
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(constraints, "constraints");
        return new ContactVelocityResolution(
                true,
                false,
                velocity,
                velocity,
                constraints
        );
    }

    /** Finite geometry activation exhausted its operation budget. No plane
     * set is claimed; a conservative stop affects velocity only. */
    public static ContactVelocityResolution unresolved(Vec3 desiredVelocity) {
        return new ContactVelocityResolution(false, true, Vec3.ZERO, desiredVelocity, List.of());
    }

    /**
     * No vector satisfies every affine constraint; the resolution carries the
     * deterministic non-penetrating fallback velocity instead.
     */
    public static ContactVelocityResolution infeasible(
            Vec3 desiredVelocity,
            Vec3 fallbackVelocity,
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        Objects.requireNonNull(desiredVelocity, "desiredVelocity");
        Objects.requireNonNull(fallbackVelocity, "fallbackVelocity");
        Objects.requireNonNull(constraints, "constraints");
        return new ContactVelocityResolution(
                false,
                true,
                fallbackVelocity,
                desiredVelocity,
                constraints
        );
    }

    public boolean feasible() {
        return feasible;
    }

    public boolean infeasible() {
        return !feasible;
    }

    /** Whether the affine set had no exact solution and the fallback was used. */
    public boolean fallbackApplied() {
        return fallback;
    }

    /**
     * The velocity this response commits. For a feasible set this is the exact
     * projection; for an infeasible set it is the deterministic non-penetrating
     * fallback. It never authorizes a translation change.
     */
    public Vec3 velocity() {
        if (velocity == null) {
            throw new IllegalStateException(
                    "contact resolution has no committed velocity"
            );
        }
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    /** Defensive copy of the velocity the projection was asked to realize. */
    public Vec3 desiredVelocity() {
        return new Vec3(
                desiredVelocity.x,
                desiredVelocity.y,
                desiredVelocity.z
        );
    }

    /** Immutable snapshot of the exact constraints that were solved. */
    public List<ContactConstraintProjector.Constraint> constraints() {
        return constraints;
    }
}
