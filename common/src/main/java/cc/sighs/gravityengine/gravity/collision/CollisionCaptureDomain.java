package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.Objects;

/**
 * Immutable conservative capture envelope for one outer collision operation.
 *
 * <p>The domain is a value, never a solver. It is constructed at the
 * integration boundary from the initial body, the operation's requested
 * translation and the hard route bounds of the character solver. The capture
 * adapter materializes every static block primitive, registered obstacle and
 * hard dynamic surface inside this envelope exactly once; later scene queries
 * only filter these captured immutable lists.</p>
 *
 * <p>Static and dynamic envelopes are separate so the fixed 16-block moving
 * surface discovery inflation never forces the same inflation onto block
 * capture.</p>
 */
public record CollisionCaptureDomain(
        Aabb3d staticBounds,
        Aabb3d dynamicEntityBounds
) {
    public CollisionCaptureDomain {
        Objects.requireNonNull(staticBounds, "staticBounds");
        Objects.requireNonNull(dynamicEntityBounds, "dynamicEntityBounds");
    }

    /**
     * Conservative world-axis domain for one translated character operation.
     *
     * <p>The envelope starts from the initial body's swept AABB and inflates
     * by every finite route allowance the current solver can legally use:
     * initial-overlap recovery (bounded by body span + step + 0.5), the
     * bounded rise/settle terrain budget, continuous support-follow rise
     * (proportional to tangent request) and probe/contact tolerances.</p>
     */
    public static CollisionCaptureDomain forTranslation(
            Aabb3d initialBounds,
            Vec3d requestedMovement,
            double maxStepHeight
    ) {
        return forTranslationRange(
                initialBounds,
                requestedMovement,
                requestedMovement,
                maxStepHeight
        );
    }

    /**
     * Conservative world-axis domain for one translated character operation
     * whose actual request may lie anywhere between two componentwise bounds.
     *
     * <p>The envelope starts from the initial body's swept AABB expanded
     * toward <em>both</em> movement bounds and inflates by every finite route
     * allowance the current solver can legally use, exactly like
     * {@link #forTranslation}. Route margins use the largest corner magnitude
     * inside the bound box, so the single envelope conservatively covers any
     * actual displacement inside the box.</p>
     */
    public static CollisionCaptureDomain forTranslationRange(
            Aabb3d initialBounds,
            Vec3d movementLower,
            Vec3d movementUpper,
            double maxStepHeight
    ) {
        return forTranslationRange(
                initialBounds,
                movementLower,
                movementUpper,
                new Aabb3d(
                        0.0D, 0.0D, 0.0D,
                        0.0D, 0.0D, 0.0D
                ),
                maxStepHeight
        );
    }

    /**
     * Conservative capture envelope including an additional relative motion
     * envelope, such as a rotating support material-point trajectory.
     *
     * <p>{@code additionalRelativeBounds} is expressed relative to the actor's
     * operation start and is added componentwise to the movement range before
     * the normal route margins are applied.</p>
     */
    public static CollisionCaptureDomain forTranslationRange(
            Aabb3d initialBounds,
            Vec3d movementLower,
            Vec3d movementUpper,
            Aabb3d additionalRelativeBounds,
            double maxStepHeight
    ) {
        Objects.requireNonNull(initialBounds, "initialBounds");
        Objects.requireNonNull(movementLower, "movementLower");
        Objects.requireNonNull(movementUpper, "movementUpper");
        Objects.requireNonNull(
                additionalRelativeBounds,
                "additionalRelativeBounds"
        );
        if (!Double.isFinite(maxStepHeight) || maxStepHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "maxStepHeight must be finite and non-negative: "
                            + maxStepHeight
            );
        }
        requireFinite(movementLower, "movementLower");
        requireFinite(movementUpper, "movementUpper");

        Vec3d combinedLower = movementLower.add(
                additionalRelativeBounds.minX(),
                additionalRelativeBounds.minY(),
                additionalRelativeBounds.minZ()
        );
        Vec3d combinedUpper = movementUpper.add(
                additionalRelativeBounds.maxX(),
                additionalRelativeBounds.maxY(),
                additionalRelativeBounds.maxZ()
        );

        double largestMagnitudeSquared = Math.max(
                combinedLower.x() * combinedLower.x(),
                combinedUpper.x() * combinedUpper.x()
        ) + Math.max(
                combinedLower.y() * combinedLower.y(),
                combinedUpper.y() * combinedUpper.y()
        ) + Math.max(
                combinedLower.z() * combinedLower.z(),
                combinedUpper.z() * combinedUpper.z()
        );
        double movementLength = Math.sqrt(largestMagnitudeSquared);
        double bodySpan = Math.max(
                initialBounds.maxX() - initialBounds.minX(),
                Math.max(
                        initialBounds.maxY() - initialBounds.minY(),
                        initialBounds.maxZ() - initialBounds.minZ())
        );
        double terrainRise = TerrainTraversalPolicy
                .customGravityTerrainRiseBudget(maxStepHeight);
        double continuousSupportRise =
                movementLength
                        * TerrainTraversalPolicy.MAX_CONTINUOUS_SUPPORT_RISE_RATIO
                        + CollisionTolerances.ZERO_VECTOR_EPSILON;
        double recoveryBound =
                bodySpan + terrainRise + 0.5D;
        double margin =
                recoveryBound
                        + terrainRise
                        + continuousSupportRise
                        + CollisionTolerances.CONTACT_SKIN
                        + CollisionTolerances.CONTACT_SLOP
                        + 1.0E-7D;

        Aabb3d staticBounds = initialBounds
                .expandTowards(combinedLower)
                .expandTowards(combinedUpper)
                .inflate(margin);
        return fromStaticBounds(staticBounds);
    }

    private static void requireFinite(
            Vec3d vector,
            String name
    ) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }

    /**
     * Rigid angular capture envelope for one attitude operation.
     *
     * <p>The angular solver does not query only exact rotated copies of the
     * original OBB. {@link ObbRotationSweep} also queries conservative temporary
     * interval-envelope OBBs whose independent half-axis maxima can extend
     * beyond the original body's circumsphere. Therefore this capture domain
     * must cover every body that the angular solver may legally submit to the
     * collision scene, not merely every exact physical orientation.</p>
     *
     * <p>The domain also covers the final exact-body gravity support probe and
     * the broadphase contact skin/slop applied by
     * {@link CapturedCollisionScene#query}.</p>
     */
    public static CollisionCaptureDomain forAngular(
            OrientedBox startingBody
    ) {
        Objects.requireNonNull(startingBody, "startingBody");

        var center = startingBody.center();
        var half = startingBody.halfExtents();

        double exactCornerRadius = Math.sqrt(
                half.x() * half.x()
                        + half.y() * half.y()
                        + half.z() * half.z()
        );

        /*
         * ObbRotationSweep.advance() may pass an interval-envelope OBB to its
         * collision oracle.  That temporary query body can be larger than the
         * exact rotated OBB, so use the solver's proven envelope bound.
         */
        // Any material support pivot lies within radius r of C0. Every rotated
        // corner is within 2r of that pivot. Independent enclosure coordinates
        // lie within that 2r ball's bounding cube: C0 radius r + sqrt(3)*2r.
        // This covers proof geometry before the frozen scene can select a pivot.
        double angularQueryRadius = exactCornerRadius
                + 2 * ObbRotationSweep.maximumEnvelopeCornerRadius(startingBody);

        /*
         * After angular validation, GravityGroundProbe may sweep the exact body
         * by PROBE_DISTANCE.  This is a separate legal query shape.
         */
        double supportProbeRadius =
                3 * exactCornerRadius
                        + GravityGroundProbe
                        .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE;

        /*
         * CapturedCollisionScene.query() applies CONTACT_SLOP and CONTACT_SKIN
         * after rawSweptAabb(), so include them once around the largest legal
         * angular-operation query.
         *
         * Keep the final 1e-7 numerical guard; do not use it as a substitute for
         * geometric coverage.
         */
        double radius =
                Math.max(
                        angularQueryRadius,
                        supportProbeRadius
                )
                        + CollisionTolerances.CONTACT_SKIN
                        + CollisionTolerances.CONTACT_SLOP
                        + 1.0E-7D;

        Aabb3d staticBounds = new Aabb3d(
                center.x() - radius,
                center.y() - radius,
                center.z() - radius,
                center.x() + radius,
                center.y() + radius,
                center.z() + radius
        );

        return fromStaticBounds(staticBounds);
    }

    /**
     * One-off capture around an exact body at a transition boundary. The
     * envelope includes the recovery bound of the geometry transition.
     */
    public static CollisionCaptureDomain forTransition(
            cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody exactBody,
            double maxStepHeight
    ) {
        Objects.requireNonNull(exactBody, "exactBody");
        return forTranslation(
                exactBody.enclosingAabb(),
                Vec3d.ZERO,
                maxStepHeight
        );
    }

    private static CollisionCaptureDomain fromStaticBounds(
            Aabb3d staticBounds
    ) {
        return new CollisionCaptureDomain(
                staticBounds,
                DynamicEntityBroadphasePolicy
                        .candidateQueryBounds(staticBounds)
        );
    }

    /** Whole-domain capture for fixed-position/vanilla transition scenes. */
    public static CollisionCaptureDomain around(
            Aabb3d bounds,
            double margin
    ) {
        Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(margin) || margin < 0.0D) {
            throw new IllegalArgumentException(
                    "margin must be finite and non-negative: " + margin
            );
        }
        return fromStaticBounds(bounds.inflate(margin));
    }
}
