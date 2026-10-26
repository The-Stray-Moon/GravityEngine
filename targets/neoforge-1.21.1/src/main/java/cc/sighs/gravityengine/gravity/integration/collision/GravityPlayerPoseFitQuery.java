package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * Installed-pose retention and strict gravity-relative pose-change query used by
 * {@code Player.canPlayerFitWithinBlocksAndEntitiesWhen}.
 *
 * <p>Vanilla pose changes may not push the player into blocks, world border or
 * <em>ordinary</em> entities: {@code CollisionGetter.noCollision} treats any
 * entity collision as blocking.  GravityEngine movement deliberately keeps
 * ordinary entities vanilla-soft; this separate query restores the strict
 * pose-change policy against the exact gravity-oriented candidate body,
 * mirroring Vanilla's {@code deflate(1e-7)} "touching is legal, penetration
 * is illegal" spirit.</p>
 */
public final class GravityPlayerPoseFitQuery {
    /**
     * Pose-fit's own tolerance, independent from locomotion CCD constants.
     * It mirrors Vanilla's {@code makeBoundingBox(position()).deflate(1e-7)}
     * "touching is legal, shallow sub-epsilon overlap is legal" semantics.
     */
    public static final double POSE_FIT_EPSILON = 1.0E-7D;

    private GravityPlayerPoseFitQuery() {}

    public static boolean canFit(Player player, Pose pose) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(pose, "pose");
        EntityDimensions requested = player.getDimensions(pose);
        EntityDimensions installed = GravityEntityGeometry.dimensions(player);
        // Retention changes no geometry. Movement owns existing overlap, even
        // immediately after an authoritative body install at the local anchor.
        // Do not turn that overlap into Vanilla's forced crouch/swim fallback.
        if (pose == cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(player).gravityengine$installedPose()
                && requested.width() == installed.width()
                && requested.height() == installed.height()) return true;
        return canFit(
                player,
                requested,
                player.position()
        );
    }

    public static boolean canFit(
            Entity entity,
            EntityDimensions dimensions,
            Vec3 positionAnchor
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(positionAnchor, "positionAnchor");

        if (CharacterDimensionPolicy.decide(
                dimensions.width(), dimensions.height()) != CharacterDimensionPolicy.Decision.CAPSULE)
            return false;
        try {
            // The same pose-fit margin erodes the capsule radius and preserves
            // its spine; this temporary query never changes installed dimensions.
            CollisionBody body = locallyDeflated(
                    GravityEntityGeometry.candidateBody(dimensions, positionAnchor, GravityEntityGeometry.installedUp(entity)),
                    POSE_FIT_EPSILON
            );
            return canFitInScene(entity, body);
        } catch (CollisionComplexityLimitException
                 | CollisionSceneCoverageException limit) {
            // A pose-expansion query must fail closed on complexity or scene
            // coverage limits: never allow an unverified penetration, never
            // crash the pose state machine, never re-capture the world.
            return false;
        }
    }

    /**
     * Pose legality consumes exactly one immutable {@link CollisionScene}.
     * The scene is resolved by the physics boundary
     * ({@link GravityCollisionEngine#poseFitScene}): inside an outer gravity
     * operation the operation's frozen scene is borrowed; outside an
     * operation this is the explicit pose-change capture boundary. Either way
     * no later query reads the live world.
     */
    private static boolean canFitInScene(
            Entity entity,
            CollisionBody body
    ) {
        CollisionScene scene =
                GravityCollisionEngine.poseFitScene(entity, body);
        List<CollisionObstacle> obstacles = scene.queryPoseFit(body);
        // Touching contacts are legal; meaningful penetration (> 1e-7)
        // blocks the pose change.
        return !CurrentContactQuery.requiresPenetrationRecovery(
                body, obstacles
        );
    }

    /** Query-only Minkowski erosion; installed dimensions and spine stay owned by geometry. */
    static CollisionBody locallyDeflated(CollisionBody body, double epsilon) {
        return Objects.requireNonNull(body, "body").deflated(epsilon);
    }
}
