package cc.sighs.gravityengine.gravity.integration.vanilla;

import net.minecraft.world.phys.Vec3;

/**
 * Operand translation for Vanilla's ordinary-movement position commit.
 *
 * <p>1.21.1 / NeoForge 21.1.249 {@code Entity.move} writes the resolved
 * translation through {@code setPos} only when
 *
 * <pre>    resolved.lengthSqr() &gt; 1.0E-7D</pre>
 *
 * <p>That optimization is valid for Vanilla's own solver, which never reports a
 * usable displacement below the threshold. It is not valid for GravityEngine's
 * authoritative custom collision routes: the custom solver owns the exact
 * resolved displacement and may legitimately return a very small non-zero
 * settling displacement. Dropping it while still consuming the collision and
 * contact result makes the committed position disagree with the committed
 * support state.
 *
 * <p>Only the predicate operand is translated. The resolved {@link Vec3} itself
 * is never enlarged or otherwise modified, so position transport, collision
 * flags, fall response, walk distance, contact velocity response and geometry
 * re-anchoring keep consuming the exact custom displacement.
 */
public final class VanillaMovementCommit {
    /** Exact threshold of the 21.1.249 {@code Entity.move} write predicate. */
    private static final double TINY_WRITE_THRESHOLD = 1.0E-7D;

    private VanillaMovementCommit() {}

    /**
     * Value the native commit predicate must observe for one resolved
     * displacement.
     *
     * <ul>
     *   <li>A Vanilla-owned collision route keeps the native operand
     *       untouched.</li>
     *   <li>An exact zero custom displacement stays a genuine no-op.</li>
     *   <li>Any other custom displacement is lifted just above the native
     *       threshold when its squared length falls to or below it.</li>
     * </ul>
     *
     * <p>Components are tested individually instead of {@code lengthSqr != 0}
     * so an extremely small but representable vector cannot disappear merely
     * because its squared length underflowed to zero.
     */
    public static double commitPredicateOperand(
            Vec3 resolved,
            double nativeLengthSqr,
            boolean customCollisionRoute
    ) {
        if (!customCollisionRoute) {
            return nativeLengthSqr;
        }
        if (resolved.x == 0.0D && resolved.y == 0.0D && resolved.z == 0.0D) {
            return nativeLengthSqr;
        }
        if (Double.isFinite(nativeLengthSqr)
                && nativeLengthSqr <= TINY_WRITE_THRESHOLD) {
            return Math.nextUp(TINY_WRITE_THRESHOLD);
        }
        return nativeLengthSqr;
    }
}
