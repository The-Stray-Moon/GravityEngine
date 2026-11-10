package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.CollisionScene;
import cc.sighs.gravityengine.gravity.collision.ObbQueryContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensions;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;

import java.util.Objects;

/**
 * One immutable, loader-neutral geometry-transition planning request.
 *
 * <p>Every field is data the planner actually consumes. The target captures
 * these facts from its entity/level boundary once and never hands an
 * {@code Entity}, {@code Level}, {@code EntityDimensions}, Minecraft
 * {@code Vec3}/{@code AABB} or {@code BlockPos} to the planner.</p>
 *
 * @param installedFrame   frame whose collision axis currently owns the body
 * @param installedBody    exact body currently installed at that axis
 * @param installedAnchor  authoritative network position of the installed body
 * @param proposedFrame    frame proposed by this operation's fresh evidence
 * @param dimensions       captured character dimensions
 * @param scene            operation's already-frozen collision scene
 * @param queryContext     operation's shared geometry query context
 * @param positionAuthority who owns the position anchor for this operation
 * @param restingContact   previous resting support snapshot, or {@code null}
 *                         when none is usable at this boundary
 * @param gameTick         authoritative game tick used for snapshot staleness
 */
public record GeometryTransitionRequest(
        GravityFrame installedFrame,
        CollisionBody installedBody,
        Vec3d installedAnchor,
        GravityFrame proposedFrame,
        CharacterDimensions dimensions,
        CollisionScene scene,
        ObbQueryContext queryContext,
        PositionAuthorityPolicy positionAuthority,
        RestingContactSnapshot restingContact,
        long gameTick
) {
    public GeometryTransitionRequest {
        Objects.requireNonNull(installedFrame, "installedFrame");
        Objects.requireNonNull(installedBody, "installedBody");
        Objects.requireNonNull(installedAnchor, "installedAnchor");
        Objects.requireNonNull(proposedFrame, "proposedFrame");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(positionAuthority, "positionAuthority");
        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: " + gameTick
            );
        }
    }
}
