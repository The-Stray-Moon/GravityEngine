package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.movement.ClosestSpaceEscapePolicy;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;

import java.util.ArrayList;
import java.util.List;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends net.minecraft.client.player.AbstractClientPlayer
        implements cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Input,
        cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess.LocalInput {
    @Unique private final cc.sighs.gravityengine.client.CharacterSneakInput gravityengine$sneakInput =
            new cc.sighs.gravityengine.client.CharacterSneakInput();
    @org.spongepowered.asm.mixin.Shadow private boolean crouching;
    protected LocalPlayerMixin(net.minecraft.client.multiplayer.ClientLevel level,
            com.mojang.authlib.GameProfile profile) { super(level, profile); }

    /** Native keyboard polling precedes item-use damping and travel. Retain
     * the raw key and reversible slowdown; the travel frame still owns policy. */
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V"), require = 1)
    private void gravityengine$captureSneakKey(net.minecraft.client.player.Input input,
            boolean slow, float speed, Operation<Void> original) {
        var player = (LocalPlayer) (Object) this;
        if (!GravityInfluencePolicy.usesCustomLocomotion(player)) {
            gravityengine$sneakInput.clear();
            original.call(input, slow, speed);
            return;
        }
        original.call(input, false, speed);
        gravityengine$sneakInput.capture(input, slow, speed,
                ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player)
                        .gravityengine$characterMode().swimActive());
    }

    @Override public boolean gravityengine$descendHeld() {
        return gravityengine$sneakInput.held();
    }

    /** Publish the resolved semantic carrier before movement. All inherited
     * sneak consumers and Vanilla command/interaction packets read this one
     * Input.shiftKeyDown value. Forced crawl/real fluid pose rules stay native. */
    @Override public Vec3 gravityengine$resolveSneakInput(
            cc.sighs.gravityengine.gravity.movement.CharacterControlPlan plan, Vec3 travel) {
        var player = (LocalPlayer) (Object) this;
        boolean sneak = gravityengine$sneakInput.held() && !plan.ownsDescendInput();
        if (gravityengine$sneakInput.changesOwnership(plan)) {
            crouching = !player.getAbilities().flying && !player.isSwimming() && !player.isPassenger()
                && canPlayerFitWithinBlocksAndEntitiesWhen(net.minecraft.world.entity.Pose.CROUCHING)
                && (sneak || !player.isSleeping()
                    && !canPlayerFitWithinBlocksAndEntitiesWhen(net.minecraft.world.entity.Pose.STANDING));
        }
        Vec3 resolved = gravityengine$sneakInput.resolve(player.input, plan,
                player.isMovingSlowly(), travel);
        player.xxa = (float) resolved.x;
        player.zza = (float) resolved.z;
        player.setShiftKeyDown(player.input.shiftKeyDown);
        return resolved;
    }
    /** Native angle/zero-input policy consumes displacement in the same control plane as xxa/zza. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "isHorizontalCollisionMinor")
    private boolean gravityengine$minorContact(Vec3 displacement, Operation<Boolean> original) {
        var player = (LocalPlayer) (Object) this;
        if (!GravityInfluencePolicy.usesCustomCollision(player)) return original.call(displacement);
        var frame = GravityFrameAccess.authoritativeFrame(player);
        var look = cc.sighs.gravityengine.look.PlayerLookIntegration.capture(player, frame);
        var forward = cc.sighs.gravityengine.gravity.movement.GravityPhysics.tangentForward(
                look.forward(), frame, look.zeroPitchForward());
        var left = frame.up().cross(forward).normalize();
        return original.call(new Vec3(displacement.dot(left), displacement.dot(frame.up()), displacement.dot(forward)));
    }

    /** The adapted displacement already uses control axes; no second yaw rotation. */
    @WrapOperation(method = "isHorizontalCollisionMinor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"), require = 1)
    private float gravityengine$minorControlYaw(LocalPlayer player, Operation<Float> original) {
        return GravityInfluencePolicy.usesCustomCollision(player) ? 0.0F : original.call(player);
    }

    @Override
    public BodyAttitudeInput gravityengine$pendingLook() {
        return ClientBodyAttitudeControl.pendingLook(
                (net.minecraft.world.entity.player.Player) (Object) this);
    }

    /** One gravity-aware closest-space evaluation per local-player tick. */
    @Unique
    private long gravityengine$closestSpaceHandledTick = Long.MIN_VALUE;

    /**
     * Tiny semantic-eye probe used only for the client suffocation query.
     * This is intentionally not a character collision body.
     */
    @Unique
    private static final double gravityengine$CLOSEST_SPACE_PROBE_RADIUS =
            1.0E-4D;

    /**
     * World-distance sampling interval for locating the first free point.
     * The final boundary is refined by binary search.
     */
    @Unique
    private static final double gravityengine$CLOSEST_SPACE_SEARCH_STEP =
            0.05D;

    /**
     * A normalized direction always leaves one isolated unit voxel within
     * sqrt(3) blocks. The small margin avoids precision rejection at a corner.
     *
     * Longer continuous solid regions are intentionally not treated as one
     * "closest neighboring space" escape.
     */
    @Unique
    private static final double gravityengine$CLOSEST_SPACE_MAX_DISTANCE =
            1.8D;

    @Unique
    private static final int gravityengine$CLOSEST_SPACE_REFINE_STEPS = 10;

    @Inject(
            method = "moveTowardsClosestSpace",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void gravityengine$gravityClosestSpace(
            double x,
            double z,
            CallbackInfo ci
    ) {
        LocalPlayer player = (LocalPlayer) (Object) this;

        if (!GravityInfluencePolicy.usesCustomCollision(player)) {
            return;
        }

        /*
         * Vanilla's world-X/world-Z implementation is never correct for an
         * arbitrary-gravity exact body.
         */
        ci.cancel();

        /*
         * Vanilla invokes moveTowardsClosestSpace several times from aiStep.
         * The custom path reconstructs all semantic samples in one evaluation,
         * therefore exactly one invocation owns the custom query per tick.
         *
         * The vanilla x/z arguments are deliberately not interpreted as a
         * rotated block grid.
         */
        if (player.tickCount == this.gravityengine$closestSpaceHandledTick) {
            return;
        }
        this.gravityengine$closestSpaceHandledTick = player.tickCount;

        GravityRuntimeState runtime = GravityEntityAccess.cast(player)
                .gravityengine$gravityComponent().runtime();

        if (runtime.isInMove()) {
            /*
             * Never introduce an independent live-world occupancy read into an
             * already captured physical operation.
             */
            return;
        }

        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(player);

        if (frame == null) {
            return;
        }

        Level level = player.level();
        Vec3 semanticEye = GravityEntityGeometry.eyePosition(player);

        /*
         * Four semantic samples equivalent in purpose to Vanilla's four
         * width-offset calls, but constructed in the actual gravity tangent
         * plane rather than by pretending that the tangent basis defines a voxel
         * coordinate grid.
         */
        Vec3 left = frame.left();
        Vec3 forward = frame.forward();
        double widthOffset = player.getBbWidth() * 0.35D;

        Vec3[] samples = {
                semanticEye
                        .add(left.scale(-widthOffset))
                        .add(forward.scale(widthOffset)),

                semanticEye
                        .add(left.scale(-widthOffset))
                        .add(forward.scale(-widthOffset)),

                semanticEye
                        .add(left.scale(widthOffset))
                        .add(forward.scale(-widthOffset)),

                semanticEye
                        .add(left.scale(widthOffset))
                        .add(forward.scale(widthOffset))
        };

        List<ClosestSpaceEscapePolicy.Escape> candidates =
                new ArrayList<>(16);

        /*
         * Stable candidate order:
         *
         *   -local X, +local X, -local Z, +local Z
         *
         * for each semantic sample. Equal-distance ties therefore remain
         * deterministic.
         */
        for (Vec3 sample : samples) {
            if (!gravityengine$suffocatesAtPoint(level, player, sample)) {
                continue;
            }

            gravityengine$addEscapeCandidate(
                    candidates,
                    level,
                    player,
                    sample,
                    ClosestSpaceEscapePolicy.LocalAxis.X,
                    -1.0D,
                    left.scale(-1.0D)
            );

            gravityengine$addEscapeCandidate(
                    candidates,
                    level,
                    player,
                    sample,
                    ClosestSpaceEscapePolicy.LocalAxis.X,
                    1.0D,
                    left
            );

            gravityengine$addEscapeCandidate(
                    candidates,
                    level,
                    player,
                    sample,
                    ClosestSpaceEscapePolicy.LocalAxis.Z,
                    -1.0D,
                    forward.scale(-1.0D)
            );

            gravityengine$addEscapeCandidate(
                    candidates,
                    level,
                    player,
                    sample,
                    ClosestSpaceEscapePolicy.LocalAxis.Z,
                    1.0D,
                    forward
            );
        }

        var selected =
                ClosestSpaceEscapePolicy.resolveClosestEscape(candidates);

        if (selected.isEmpty()) {
            return;
        }

        ClosestSpaceEscapePolicy.Escape escape =
                selected.orElseThrow();

        Vec3 localVelocity =
                frame.worldToLocal(player.getDeltaMovement());

        double nudge =
                escape.sign()
                        * ClosestSpaceEscapePolicy.ESCAPE_VELOCITY;

        /*
         * Match Vanilla's semantic effect: replace the selected tangent velocity
         * component with the small escape nudge instead of accumulating a force.
         * The normal component and the orthogonal tangent component are retained.
         */
        Vec3 newLocalVelocity =
                escape.axis()
                        == ClosestSpaceEscapePolicy.LocalAxis.X
                        ? new Vec3(
                        nudge,
                        localVelocity.y,
                        localVelocity.z
                )
                        : new Vec3(
                        localVelocity.x,
                        localVelocity.y,
                        nudge
                );

        player.setDeltaMovement(
                frame.localToWorld(newLocalVelocity)
        );
    }

    @Unique
    private static void gravityengine$addEscapeCandidate(
            List<ClosestSpaceEscapePolicy.Escape> candidates,
            Level level,
            LocalPlayer player,
            Vec3 sample,
            ClosestSpaceEscapePolicy.LocalAxis axis,
            double sign,
            Vec3 worldDirection
    ) {
        double distance = gravityengine$firstFreeDistance(
                level,
                player,
                sample,
                worldDirection
        );

        if (!Double.isFinite(distance)) {
            return;
        }

        candidates.add(
                new ClosestSpaceEscapePolicy.Escape(
                        axis,
                        sign,
                        distance
                )
        );
    }

    /**
     * Finds the first non-suffocating point along an actual world-space tangent
     * direction.
     *
     * <p>This operates on Minecraft's real suffocating collision geometry. It
     * never converts the world into a rotated pseudo-voxel grid.</p>
     */
    @Unique
    private static double gravityengine$firstFreeDistance(
            Level level,
            LocalPlayer player,
            Vec3 origin,
            Vec3 direction
    ) {
        double directionLengthSquared = direction.lengthSqr();

        if (directionLengthSquared <= 1.0E-12D) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3 unitDirection =
                Math.abs(directionLengthSquared - 1.0D) <= 1.0E-9D
                        ? direction
                        : direction.normalize();

        if (!gravityengine$suffocatesAtPoint(
                level,
                player,
                origin
        )) {
            return 0.0D;
        }

        double blockedDistance = 0.0D;

        for (double distance =
             gravityengine$CLOSEST_SPACE_SEARCH_STEP;
             distance
                     <= gravityengine$CLOSEST_SPACE_MAX_DISTANCE
                     + 1.0E-9D;
             distance += gravityengine$CLOSEST_SPACE_SEARCH_STEP) {

            double candidateDistance = Math.min(
                    distance,
                    gravityengine$CLOSEST_SPACE_MAX_DISTANCE
            );

            Vec3 candidatePoint = origin.add(
                    unitDirection.scale(candidateDistance)
            );

            if (!gravityengine$suffocatesAtPoint(
                    level,
                    player,
                    candidatePoint
            )) {
                /*
                 * We have a blocked/free bracket. Refine the actual geometry
                 * boundary so candidate ordering is based on physical distance,
                 * not on the coarse sampling interval.
                 */
                double low = blockedDistance;
                double high = candidateDistance;

                for (int i = 0;
                     i < gravityengine$CLOSEST_SPACE_REFINE_STEPS;
                     i++) {
                    double mid = (low + high) * 0.5D;

                    Vec3 midPoint = origin.add(
                            unitDirection.scale(mid)
                    );

                    if (gravityengine$suffocatesAtPoint(
                            level,
                            player,
                            midPoint
                    )) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }

                return high;
            }

            blockedDistance = candidateDistance;

            if (candidateDistance
                    >= gravityengine$CLOSEST_SPACE_MAX_DISTANCE) {
                break;
            }
        }

        return Double.POSITIVE_INFINITY;
    }

    /**
     * Semantic point suffocation query against actual world collision geometry.
     *
     * <p>The query is intentionally tiny: closest-space is deciding which way
     * the semantic eye/body sample should be nudged, not performing another
     * character collision solve.</p>
     */
    @Unique
    private static boolean gravityengine$suffocatesAtPoint(
            Level level,
            LocalPlayer player,
            Vec3 point
    ) {
        double radius = gravityengine$CLOSEST_SPACE_PROBE_RADIUS;

        AABB probe = new AABB(
                point.x - radius,
                point.y - radius,
                point.z - radius,
                point.x + radius,
                point.y + radius,
                point.z + radius
        );

        return level.collidesWithSuffocatingBlock(
                player,
                probe
        );
    }

    @Unique
    private static boolean columnSuffocates(
            Level level,
            net.minecraft.client.player.LocalPlayer player,
            Vec3 samplePoint
    ) {
        BlockPos cell = BlockPos.containing(samplePoint);
        AABB boundingBox = player.getBoundingBox();
        AABB column = new AABB(
                cell.getX(),
                boundingBox.minY,
                cell.getZ(),
                cell.getX() + 1.0D,
                boundingBox.maxY,
                cell.getZ() + 1.0D
        ).deflate(1.0E-7D);
        return level.collidesWithSuffocatingBlock(player, column);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void gravityengine$defensiveReconcile(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateLocalAbilitySuppression(player);
    }

    /**
     * Semantic input capture boundary.
     *
     * <p>Vanilla {@code LocalPlayer.aiStep} calls {@code input.tick} and
     * NeoForge's {@code onMovementInputUpdate} first, then performs
     * gameplay conditioning (auto-jump, sprint resolution, item-use impulse
     * damping) and finally delegates to {@code super.aiStep()}, where the
     * actual movement/jump consumption happens.  Injecting immediately
     * before that {@code super} call captures the processed {@code Input}
     * values at the earliest point at which sprint is resolved and no
     * gameplay movement/jump has been consumed yet.  Body look and held roll are
     * captured for the current normal tick; Vanilla consumes movement input.</p>
     */
    @Inject(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/"
                            + "AbstractClientPlayer;aiStep()V",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void gravityengine$captureSemanticInput(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ClientBodyAttitudeControl.beginTick(player);
    }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void gravityengine$logOutgoingMove(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!GravityDebugLog.ENABLED) return;
        GravityDebugLog.log(
                player,
                "client-move-out",
                "position=%s %s yaw=%.3f pitch=%.3f onGround=%s",
                GravityDebugLog.vec(player.position()),
                GravityDebugLog.formatEntityVelocity(player),
                player.getYRot(),
                player.getXRot(),
                player.onGround()
        );
    }

    @WrapOperation(
            method = {"sendPosition", "tick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void gravityengine$traceActualMovePacket(
            ClientPacketListener connection,
            Packet<?> packet,
            Operation<Void> original
    ) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 1.21.1 sendPosition chooses Pos/PosRot/Rot/StatusOnly; tick also
        // sends passenger rotation. Observe immediately before the real send.
        if (GravityDebugLog.ENABLED
                && packet instanceof ServerboundMovePlayerPacket move
                && GravityInfluencePolicy.usesCustomCollision(player)) {
            var packetPosition = new net.minecraft.world.phys.Vec3(
                    move.getX(player.getX()),
                    move.getY(player.getY()),
                    move.getZ(player.getZ())
            );
            GravityDebugLog.log(
                    player,
                    "client-packet-send",
                    "packetType=%s carriesPosition=%s carriesRotation=%s packetPosition=%s "
                            + "currentPosition=%s packetYaw=%.3f packetPitch=%.3f "
                            + "currentLocalYaw=%.3f currentLocalPitch=%.3f "
                            + "packetOnGround=%s currentOnGround=%s %s",
                    packet.getClass().getSimpleName(),
                    move.hasPosition(),
                    move.hasRotation(),
                    GravityDebugLog.vec(packetPosition),
                    GravityDebugLog.vec(player.position()),
                    move.getYRot(player.getYRot()),
                    move.getXRot(player.getXRot()),
                    player.getYRot(),
                    player.getXRot(),
                    move.isOnGround(),
                    player.onGround(),
                    GravityDebugLog.formatEntityVelocity(player)
            );
        }
        original.call(connection, packet);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gravityengine$sendBodyState(CallbackInfo ci) {
        cc.sighs.gravityengine.client.ClientBodyAttitudeControl.sendCurrentState(
                (LocalPlayer) (Object) this);
    }

}
