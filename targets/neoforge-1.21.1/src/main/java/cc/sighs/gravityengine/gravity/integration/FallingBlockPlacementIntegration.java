package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;

/**
 * Converts completed movement contact facts into FallingBlock lifecycle
 * operands. It never evaluates gravity and never installs a block.
 */
public final class FallingBlockPlacementIntegration {
    private static final double INSTALLATION_NORMAL_EPSILON =
            1.0E-6D;

    private FallingBlockPlacementIntegration() {}

    public static FallingBlockLandingContext fromMove(
            FallingBlockEntity entity
    ) {
        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent()
                        .operationState();

        if (!runtime.isInMove()
                || runtime.discontinuityDestination() != null) {
            return null;
        }

        var passive =
                runtime.currentPassiveMoveResult();

        var exact =
                runtime.currentMoveResult();

        if (passive != null
                && !passive.indeterminate()
                && passive.blockedDown()) {
            return passive.impacts()
                    .stream()
                    .filter(contact ->
                            passive.requestedMovement()
                                    .dot(contact.normal())
                                    < -CollisionTolerances
                                    .ENTERING_PLANE_EPSILON
                    )
                    .map(
                            CombinedVectorRoute
                                    ::materialContact
                    )
                    .map(contact ->
                            fromContact(
                                    contact,
                                    passive.frame()
                            )
                    )
                    .filter(
                            java.util.Objects
                                    ::nonNull
                    )
                    .filter(context ->
                            safePosition(
                                    entity,
                                    context
                            )
                    )
                    .findFirst()
                    .orElse(null);
        }

        if (exact != null
                && exact.isAuthoritative()
                && exact.blockedDown()) {
            return exact
                    .movementSupportContact()
                    .map(contact ->
                            fromContact(
                                    contact,
                                    exact.frame()
                            )
                    )
                    .filter(context ->
                            safePosition(
                                    entity,
                                    context
                            )
                    )
                    .orElse(null);
        }

        return null;
    }

    static FallingBlockLandingContext fromContact(
            GravitySupportContact contact,
            GravityFrame frame
    ) {
        if (frame.strength() == 0.0D) {
            return null;
        }

        /*
         * PHYSICAL LANDING:
         *
         * Use the same support classification as the collision solver.
         * A rotated rigid surface does not have to be within 1e-6 of a
         * Minecraft world axis merely to count as support.
         */
        if (!CombinedVectorRoute.isSupportNormal(
                contact.normal(),
                frame
        )) {
            return null;
        }

        Direction down =
                FallingBlockStartIntegration
                        .dominantDown(
                                frame.down()
                        );

        if (down == null) {
            return null;
        }

        var identity =
                contact.faceIdentity();

        /*
         * Dynamic/rigid support can terminate the ballistic flight, but it
         * cannot become a parent-world BlockPos installation face.
         */
        if (identity == null
                || !identity.staticBlockSupport()
                || identity.dynamicSupport()) {
            return FallingBlockLandingContext
                    .nonInstallableLanding(down);
        }

        /*
         * BLOCK INSTALLATION:
         *
         * This is intentionally stricter than physical support. Minecraft
         * FallingBlock installation owns a discrete grid face.
         */
        var axis =
                down.getNormal();

        Vec3d installationNormal =
                new Vec3d(
                        -axis.getX(),
                        -axis.getY(),
                        -axis.getZ()
                );

        if (contact.normal()
                .dot(installationNormal)
                < 1.0D
                - INSTALLATION_NORMAL_EPSILON) {
            /*
             * It was a legitimate physical landing, just not one that can be
             * represented as a Minecraft grid installation.
             */
            return FallingBlockLandingContext
                    .nonInstallableLanding(down);
        }

        BlockPos support =
                MinecraftMathAdapter.toBlockPos(
                        identity.block()
                );

        return FallingBlockLandingContext
                .staticLanding(
                        down,
                        support
                );
    }

    private static boolean safePosition(
            FallingBlockEntity entity,
            FallingBlockLandingContext context
    ) {
        if (!context.installable()) {
            return true;
        }

        var level =
                entity.level();

        BlockPos placement =
                context.placementPos();

        return !level.isOutsideBuildHeight(
                placement
        )
                && level.getWorldBorder()
                .isWithinBounds(placement)
                && level.hasChunkAt(placement);
    }
}