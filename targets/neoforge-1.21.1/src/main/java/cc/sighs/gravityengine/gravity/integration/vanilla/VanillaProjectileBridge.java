package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Projectile origin, launch and ballistic-aim operand bridge.
 *
 * <p>Translates Vanilla's world-axis spawn/aim arithmetic into the shooter's
 * captured reference frame. Propulsion, placement and actor capture remain
 * separate owners.</p>
 */
public final class VanillaProjectileBridge {
    private VanillaProjectileBridge() {}

    private static final double PARALLEL_EPSILON_SQUARED = 1.0E-7D;

    /**
     * Exact 21.1.249 Crossbow target-branch rise constant.
     *
     * <p>Vanilla {@code CrossbowItem.shootProjectile} bytecode widens the
     * {@code 0.2F} literal after the tangent-distance multiplication, so the
     * exact double is {@code 0.20000000298023224D}. Reproducing the
     * translated branch with an independent {@code 0.2D} would change the
     * numeric topology; callers must pass this named, version-matched
     * constant.  The Vanilla arc policy (0.2 of tangent distance) is
     * unchanged.</p>
     */
    public static final double CROSSBOW_TARGET_RISE =
            (double) 0.2F;

    /** Spawn origin at {@code offset} below the physical eye along body down. */
    public static Vec3 spawnOriginBelowEye(
            VanillaActorSnapshot actor,
            double offset
    ) {
        Objects.requireNonNull(actor, "actor");
        if (!Double.isFinite(offset) || offset < 0.0D) {
            throw new IllegalArgumentException(
                    "eye offset must be finite and non-negative");
        }
        return actor.eye().add(actor.attachmentDown().scale(offset));
    }

    /**
     * Removes the component of {@code inherited} parallel to the actor's
     * reference up when Vanilla removes grounded inherited vertical motion.
     */
    public static Vec3 groundedInheritedMotion(
            VanillaActorSnapshot actor,
            Vec3 inherited,
            boolean grounded
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(inherited, "inherited");
        if (!grounded) {
            return inherited;
        }
        Vec3 up = actor.referenceUp();
        return inherited.subtract(up.scale(inherited.dot(up)));
    }

    /** Semantic launch direction with Vanilla pitch-offset convention. */
    public static Vec3 launchDirection(
            VanillaActorSnapshot actor,
            float pitchOffsetDegrees
    ) {
        Objects.requireNonNull(actor, "actor");
        return MinecraftMathAdapter.toMinecraft(
                actor.look().launchDirection(pitchOffsetDegrees));
    }

    /**
     * Vanilla casts with a pitch-limited base vector and three component multipliers.
     * Semantic aim owns the direction; the actor's attachment reference owns those
     * component operands under free attitude. Derive temporary
     * formula angles from that aim, never from Vanilla-facing output scalars, then lift
     * the fully perturbed vector once. RNG order, clamp and float topology stay Vanilla's.
     * Inactive attitude retains the existing Vanilla/reference-local formula unchanged.
     */
    public static Vec3 fishingLaunch(VanillaActorSnapshot actor, double randomX, double randomY, double randomZ) {
        var look = actor.look();
        if (look.source() != SemanticLookSnapshot.Source.BODY_ATTITUDE) {
            var angles = look.requestedLocalLook();
            return MinecraftMathAdapter.toMinecraft(
                    look.localDirectionToWorld(
                            MinecraftMathAdapter.toVec3d(
                                    fishingLaunchLocal(
                                            angles.yawDegrees(),
                                            angles.pitchDegrees(),
                                            randomX,
                                            randomY,
                                            randomZ
                                    )
                            )
                    ));
        }
        var carrier = actor.attachmentFrame();
        Vec3 forward = MinecraftMathAdapter.toMinecraft(look.forward());
        Vec3d local = carrier.worldToLocal(
                MinecraftMathAdapter.toVec3d(forward));
        float yaw = (float) Math.toDegrees(
                Math.atan2(-local.x(), local.z()));
        float pitch = (float) Math.toDegrees(
                Math.atan2(-local.y(), Math.hypot(local.x(), local.z())));
        Vec3 velocity = fishingLaunchLocal(yaw, pitch, randomX, randomY, randomZ);
        Vec3d world = carrier.localToWorld(
                MinecraftMathAdapter.toVec3d(velocity));
        return MinecraftMathAdapter.toMinecraft(world);
    }

    /**
     * 21.1.249 FishingHook component construction.  The local base uses
     * Vanilla's yaw/pitch formula and each component is scaled by
     * {@code 0.6 / length + triangle} with the three exact RNG draws.  This
     * remains in local canonical coordinates; the caller transforms the
     * complete vector once through its semantic frame.
     *
     * <p>The formula reproduces the exact 21.1.249 float topology of
     * {@code FishingHook(Player, Level, int, int)}: {@code Mth.sin/cos} are
     * the versioned float table lookups over {@code Mth.DEG_TO_RAD} scaled
     * angles, the pitch ratio is divided and negated in float, clamped by
     * the float {@code Mth.clamp(-5.0F, 5.0F)} overload, and only then
     * widened for the {@code Vec3}.  No epsilon pole workaround exists in
     * Vanilla and none is added here.</p>
     */
    private static Vec3 fishingLaunchLocal(
            float yawDegrees,
            float pitchDegrees,
            double randomX,
            double randomY,
            double randomZ
    ) {
        if (!Float.isFinite(yawDegrees) || !Float.isFinite(pitchDegrees)
                || !Double.isFinite(randomX)
                || !Double.isFinite(randomY)
                || !Double.isFinite(randomZ)) {
            throw new IllegalArgumentException(
                    "fishing launch inputs must be finite");
        }

        // Vanilla: f2 = Mth.cos(-f1 * DEG_TO_RAD - PI);
        //          f3 = Mth.sin(-f1 * DEG_TO_RAD - PI);
        //          f4 = -Mth.cos(-f * DEG_TO_RAD);
        //          f5 = Mth.sin(-f * DEG_TO_RAD);
        float cosYaw =
                Mth.cos(
                        -yawDegrees * Mth.DEG_TO_RAD
                                - (float) Math.PI
                );
        float sinYaw =
                Mth.sin(
                        -yawDegrees * Mth.DEG_TO_RAD
                                - (float) Math.PI
                );
        float cosPitch =
                -Mth.cos(
                        -pitchDegrees * Mth.DEG_TO_RAD
                );
        float sinPitch =
                Mth.sin(
                        -pitchDegrees * Mth.DEG_TO_RAD
                );

        // Vanilla: new Vec3((double)(-f3),
        //                    (double)Mth.clamp(-(f5 / f4), -5.0F, 5.0F),
        //                    (double)(-f2))
        Vec3 base = new Vec3(
                (double) (-sinYaw),
                (double) Mth.clamp(
                        -(sinPitch / cosPitch),
                        -5.0F,
                        5.0F
                ),
                (double) (-cosYaw)
        );
        double length = base.length();
        // Vanilla: vec3.multiply(
        //     0.6 / length + triangle, ... x3)
        return base.multiply(
                0.6D / length + randomX,
                0.6D / length + randomY,
                0.6D / length + randomZ
        );
    }

    /** Direct crossbow shot: rotate semantic view around semantic view up. */
    public static Vec3 crossbowDirectDirection(
            VanillaActorSnapshot actor,
            float angleDegrees
    ) {
        Objects.requireNonNull(actor, "actor");
        return rotateAround(
                actor.viewForward(),
                actor.viewUp(),
                Math.toRadians(angleDegrees)
        );
    }

    /**
     * Crossbow target shot with explicit asymmetric source topology.
     *
     * <p>Vanilla 21.1.249 {@code CrossbowItem.shootProjectile} measures the
     * horizontal range from the {@code shooter} feet position and the
     * vertical source from the {@code projectile} position:
     *
     * <pre>
     * d0 = target.x - shooter.x;  d1 = target.z - shooter.z;
     * d2 = hypot(d0, d1);                                    // feet tangent range
     * d3 = target.getY(1/3) - projectile.getY()
     *      + d2 * (double) 0.2F;                             // projectile vertical
     * aim = (d0, d3, d1)
     * </pre>
     *
     * Under custom reference semantics the feet-tangent range is measured in
     * the shooter reference tangent plane, and the projectile-vertical term
     * keeps the projectile position (which follows the shooter body/eye
     * anatomy) rather than being re-derived from the shooter feet.  Rise
     * constants and spread/inaccuracy stay Vanilla-owned; {@code
     * riseConstant} must be {@link #CROSSBOW_TARGET_RISE}, the exact
     * widened {@code (double) 0.2F} from the versioned bytecode.  The
     * caller passes one coherent shooter snapshot plus the already-resolved
     * projectile position and target body point so the two source-topology
     * facts remain explicit and can never be collapsed into one "origin".
     */
    public static Vec3 crossbowTargetDirection(
            VanillaActorSnapshot shooter,
            Vec3 projectilePosition,
            Vec3 targetPoint,
            double riseConstant,
            float angleDegrees
    ) {
        Objects.requireNonNull(shooter, "shooter");
        Objects.requireNonNull(projectilePosition, "projectilePosition");
        Objects.requireNonNull(targetPoint, "targetPoint");

        // Shooter-feet horizontal source (Vanilla X/Z aim source).
        Vec3 feetToTarget = targetPoint.subtract(shooter.feet());
        double tangentDistance =
                referenceTangentDistance(shooter, feetToTarget);

        // Projectile-vertical source (Vanilla targetY(1/3) - projectileY).
        Vec3 up = shooter.referenceUp();
        double projectileVertical =
                projectilePosition.dot(up);
        double targetVertical =
                targetPoint.dot(up);

        // Local reference-space aim: tangent footprint from the shooter feet
        // (Vanilla X/Z), vertical source from the projectile position plus
        // Vanilla's tangent-distance rise along reference up.
        Vec3 aimed = referenceTangentComponent(
                feetToTarget,
                up
        ).add(
                up.scale(
                        targetVertical
                                - projectileVertical
                                + tangentDistance * riseConstant
                )
        );
        return targetSpreadDirection(shooter, aimed, angleDegrees);
    }

    /**
     * Crossbow spread around a target-aim vector, mirroring Vanilla's
     * perpendicular-axis helper with the reference axis in place of world up.
     */
    private static Vec3 targetSpreadDirection(
            VanillaActorSnapshot shooter,
            Vec3 aimed,
            float angleDegrees
    ) {
        Objects.requireNonNull(shooter, "shooter");
        Objects.requireNonNull(aimed, "aimed");
        Vec3 normalized = normalized(aimed);
        Vec3 axis = normalized.cross(shooter.referenceUp());
        if (axis.lengthSqr() <= PARALLEL_EPSILON_SQUARED) {
            axis = normalized.cross(shooter.viewUp());
        }
        if (axis.lengthSqr() <= PARALLEL_EPSILON_SQUARED) {
            Vec3d frameForward = shooter.referenceFrame()
                    .orientation().axisZ();
            axis = normalized.cross(
                    MinecraftMathAdapter.toMinecraft(frameForward));
        }
        Vec3 perpendicular = rotateAround(
                normalized, axis, Math.PI * 0.5D);
        return rotateAround(
                normalized, perpendicular, Math.toRadians(angleDegrees));
    }

    /**
     * Crossbow-internal reference-tangent distance.
     *
     * <p>This is not a generic ranged-attack formula API; only the verified
     * Crossbow target branch shares this exact operand.</p>
     */
    private static double referenceTangentDistance(
            VanillaActorSnapshot shooter,
            Vec3 displacement
    ) {
        Objects.requireNonNull(shooter, "shooter");
        Objects.requireNonNull(displacement, "displacement");

        Vec3 up =
                shooter.referenceUp();

        double vertical =
                displacement.dot(up);

        double tangentSquared =
                displacement.lengthSqr()
                        - vertical * vertical;

        return tangentSquared > 0.0D
                ? Math.sqrt(tangentSquared)
                : 0.0D;
    }

    /** Component of {@code value} orthogonal to {@code planeNormal}. */
    private static Vec3 referenceTangentComponent(
            Vec3 value,
            Vec3 planeNormal
    ) {
        return value.subtract(
                planeNormal.scale(value.dot(planeNormal))
        );
    }

    private static Vec3 rotateAround(
            Vec3 vector,
            Vec3 axis,
            double radians
    ) {
        Vec3d out = Quatd.fromAxisAngle(
                MinecraftMathAdapter.toVec3d(axis),
                radians
        ).transform(
                MinecraftMathAdapter.toVec3d(vector)
        );
        return MinecraftMathAdapter.toMinecraft(out);
    }

    private static Vec3 normalized(Vec3 value) {
        double length = Math.sqrt(value.lengthSqr());
        if (!(length > 1.0E-12D)) {
            throw new IllegalArgumentException(
                    "direction must be non-degenerate");
        }
        return value.scale(1.0D / length);
    }

    /**
     * Vanilla yaw carrier interpreted around the supplied environmental
     * reference up. This is not physical body-forward and does not read Qbody.
     */
    public static Vec3 referenceYawForward(
            GravityFrame frame,
            float yaw
    ) {
        Objects.requireNonNull(frame, "frame");

        float radians =
                yaw * ((float) Math.PI / 180.0F);

        return MinecraftMathAdapter.toMinecraft(
                frame.localToWorld(
                        MinecraftMathAdapter.toVec3d(
                                new Vec3(
                                        -Mth.sin(radians),
                                        0.0D,
                                        Mth.cos(radians)
                                )
                        )
                )
        );
    }

    public static double referenceVerticalComponent(
            GravityFrame frame,
            Vec3 delta
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(delta, "delta");
        return delta.dot(
                MinecraftMathAdapter.toMinecraft(frame.up()));
    }

    /**
     * Body-height point for a target entity without reading semantic look.
     */
    public static Vec3 bodyHeightPoint(
            Entity target,
            double fraction
    ) {
        Objects.requireNonNull(target, "target");
        requireFinite(fraction, "fraction");

        CharacterCapsule exact =
                VanillaActorBridge
                        .exactBodyForQuery(target);

        if (exact == null) {
            return target.position().add(
                    0.0D,
                    fraction * target.getBbHeight(),
                    0.0D
            );
        }

        Vec3d center =
                exact.center();

        Vec3d up =
                exact.axis();

        double localOffset =
                (fraction - 0.5D)
                        * target.getBbHeight();

        return new Vec3(
                center.x(),
                center.y(),
                center.z()
        ).add(
                new Vec3(
                        up.x(),
                        up.y(),
                        up.z()
                ).scale(localOffset)
        );
    }

    /**
     * Body-height point when the source actor has already been coherently
     * captured for the current operation.
     */
    public static Vec3 bodyHeightPoint(
            VanillaActorSnapshot actor,
            double fraction
    ) {
        Objects.requireNonNull(actor, "actor");
        requireFinite(fraction, "fraction");

        if (!actor.customBody()) {
            return actor.positionAnchor().add(
                    0.0D,
                    fraction * actor.height(),
                    0.0D
            );
        }

        return actor.center().add(
                actor.attachmentUp().scale(
                        (fraction - 0.5D)
                                * actor.height()
                )
        );
    }

    /**
     * Anatomical point a fixed amount below an entity eye without reading
     * target semantic look.
     */
    public static Vec3 eyeBelow(
            Entity target,
            double amount
    ) {
        Objects.requireNonNull(target, "target");

        if (!Double.isFinite(amount)
                || amount < 0.0D) {
            throw new IllegalArgumentException(
                    "eye offset must be finite and non-negative"
            );
        }

        CharacterCapsule exact =
                VanillaActorBridge
                        .exactBodyForQuery(target);

        if (exact == null) {
            return target.getEyePosition()
                    .add(0.0D, -amount, 0.0D);
        }

        Vec3d center =
                exact.center();

        Vec3d axis =
                exact.axis();

        double offsetFromCenter =
                target.getEyeHeight()
                        - target.getBbHeight() * 0.5D
                        - amount;

        return new Vec3(
                center.x(),
                center.y(),
                center.z()
        ).add(
                new Vec3(
                        axis.x(),
                        axis.y(),
                        axis.z()
        ).scale(offsetFromCenter)
        );
    }

    /**
     * Anatomical point a fixed amount below an already-captured actor eye.
     * The captured body orientation owns the "below" direction so this never
     * re-reads the live target or mixes world-Y geometry into a custom body.
     */
    public static Vec3 actorEyeBelow(
            VanillaActorSnapshot actor,
            double amount
    ) {
        Objects.requireNonNull(actor, "actor");
        requireFinite(amount, "amount");
        if (amount < 0.0D) {
            throw new IllegalArgumentException(
                    "eye offset must be non-negative");
        }
        return actor.eye().add(
                actor.attachmentDown().scale(amount)
        );
    }

    /**
     * Converts the target operand of Vanilla {@code Mob.lookAt} while keeping
     * its scalar yaw/pitch update algorithm unchanged.
     *
     * <p>The supplied {@code frame} is invocation-owned and is never
     * re-resolved here. The physical eye is used for custom-body EYES anchors;
     * the returned carrier makes Vanilla's world-axis subtraction encode the
     * desired reference-local displacement.</p>
     */
    public static Vec3 mobLookAtTargetCarrier(
            Mob mob,
            GravityFrame frame,
            EntityAnchorArgument.Anchor anchor,
            Vec3 target
    ) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(target, "target");

        boolean customBody =
                GravityInfluencePolicy
                        .usesCustomBody(mob);

        if (frame.isDefault()
                && !customBody) {
            return target;
        }

        Vec3 physicalAnchor;

        if (anchor
                == EntityAnchorArgument.Anchor.EYES) {
            if (customBody) {
                Vec3 center =
                        GravityEntityGeometry.bodyCenter(mob);
                physicalAnchor =
                        GravityEntityGeometry.eyePosition(
                                mob,
                                center,
                                frame
                        );
            } else {
                physicalAnchor =
                        mob.getEyePosition();
            }
        } else {
            physicalAnchor =
                    mob.position();
        }

        Vec3 vanillaAnchor =
                anchor.apply(mob);

        return vanillaAnchor.add(
                MinecraftMathAdapter.toMinecraft(frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(
                                target.subtract(physicalAnchor))))
        );
    }

    /**
     * Vanilla-style strict facing threshold against an already-frozen
     * semantic view.
     */
    public static boolean facesTarget(
            VanillaActorSnapshot actor,
            Vec3 targetPositionAnchor,
            double threshold
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetPositionAnchor, "targetPositionAnchor");

        Vec3 delta =
                targetPositionAnchor.subtract(actor.positionAnchor());

        if (delta.lengthSqr() <= 1.0E-20D) {
            return false;
        }

        return actor.viewForward()
                .dot(delta.normalize())
                > threshold;
    }

    /**
     * Tangent displacement encoded into Vanilla's reference-local X/Z carrier.
     * Callers retain their own source-point topology.
     */
    public static Vec3 referenceTangentCarrier(
            GravityFrame frame,
            Vec3 displacement
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(displacement, "displacement");

        Vec3d local =
                frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(displacement));

        return new Vec3(
                local.x(),
                0.0D,
                local.z()
        );
    }

    /**
     * Converts a completed Vanilla reference-coordinate shot vector back to
     * world space. Rise constants and formula ordering remain caller-owned.
     */
    public static Vec3 worldShot(
            GravityFrame frame,
            double x,
            double y,
            double z
    ) {
        Objects.requireNonNull(frame, "frame");

        return MinecraftMathAdapter.toMinecraft(
                frame.localToWorld(new Vec3d(x, y, z)));
    }

    /**
     * Invocation-local translated operands. This is a carrier only; it does
     * not define a universal ranged formula.
     */
    public record AimOperands(
            GravityFrame frame,
            Vec3 tangentCarrier,
            double vertical,
            boolean translated
    ) {
        public AimOperands {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(
                    tangentCarrier,
                    "tangentCarrier"
            );
            requireFinite(vertical, "vertical");
        }
    }

    private static void requireFinite(
            double value,
            String name
    ) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite"
            );
        }
    }
}
