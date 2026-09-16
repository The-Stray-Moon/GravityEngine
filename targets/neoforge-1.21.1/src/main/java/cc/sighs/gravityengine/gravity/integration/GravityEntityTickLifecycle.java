package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.assignment.GravityAssignmentService;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.network.GravitySyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Owns entity base-tick application bootstrap, reconciliation and periodic assignment refresh. */
public final class GravityEntityTickLifecycle {
    private GravityEntityTickLifecycle() {}

    public static void onBaseTick(Entity e) {
        boolean groundAir = e instanceof LivingEntity living
                && cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.supportsGroundAir(living);
        if (e.level().isClientSide()) {
            if (groundAir) {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(e);
            }
            return;
        }
        var gravityComponent =
                GravityEntityAccess.cast(e)
                        .gravityengine$gravityComponent();

        if (gravityComponent.applicationBootstrapPending()) {
            /*
             * ServerPlayer load restoration has one high-level owner. A failed login
             * or replacement bootstrap retries through exactly that same owner; the
             * generic entity fallback must never reconstruct only the gravity half and
             * then publish/simulate a partially restored player.
             */
            if (e instanceof ServerPlayer player) {
                PlayerPhysicalLoadBootstrap.bootstrapLoadedPlayer(player);
                return;
            }

            /*
             * Non-player load fallback: refresh canonical load inputs without an
             * intermediate application commit, then perform exactly one derived
             * application reconstruction.
             */
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.refreshLoadedInputs(e);

            var bootstrap =
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                            .bootstrapLoadedApplication(e);

            if (bootstrap.status()
                    == cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus.COMMITTED
                    || bootstrap.status()
                    == cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus.UNCHANGED) {
                gravityComponent.clearApplicationBootstrapPending();
            } else {
                // Do not let ordinary per-tick assignment/update logic bypass the
                // incomplete load bootstrap.
                return;
            }
            // This bootstrap already refreshed FIELD inputs; do not sample again
            // if the first loaded tick also falls on the periodic refresh cadence.
            return;
        }
        if (e instanceof ServerPlayer sp) {
            GravityApplicationCoordinator.reconcileServerInfluence(sp);
        }
        if (!groundAir) return;
        int iv = e instanceof Player ? 4 : 20;
        if (e.tickCount % iv != 0) return;
        if (e instanceof LivingEntity) updateResolvedGravity(e);
    }

    private static void updateResolvedGravity(Entity e) {
        var component = GravityEntityAccess.cast(e)
                .gravityengine$gravityComponent();

        /*
         * DIRECT bypasses field composition until explicitly released.
         * Do not even perform the periodic field sample while DIRECT owns the
         * assignment.
         */
        if (component.assignedAuthority() == GravityAuthorityMode.DIRECT) {
            return;
        }

        var resolved = GravityAssignmentService.evaluate(e);
        if (!resolved.changed()) {
            return;
        }

        var applied = cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyFieldAssignment(
                e,
                resolved.resolved(),
                resolved.fieldPresent()
        );

        if (applied.assignmentAccepted()) {
            GravitySyncService.syncTracking(e);
        }
    }
}