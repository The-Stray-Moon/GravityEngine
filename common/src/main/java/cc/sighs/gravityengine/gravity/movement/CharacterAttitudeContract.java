package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.attitude.BodyAttitudeControlAuthority;

/**
 * The step's actor orientation owner. No contract owns character collision,
 * position or translation.
 *
 * <p><b>Ordinary upright invariant.</b> Ordinary standing, walking, sprinting,
 * crouching, jumping, falling and weak/zero-gravity drift are always
 * {@link #REFERENCE_ALIGNED}. While the ordinary locomotion owner is selected,
 * the body up axis follows the effective accepted gravity-reference up, while
 * heading around that up axis and ordinary head/look control stay free. The
 * whole body quaternion is never replaced by the reference quaternion, and
 * ordinary airborne motion never gains independent body roll. A near-zero or
 * cancelling resultant keeps the existing valid reference; it is not a reason
 * to fall back to world up or to release the upright constraint.</p>
 *
 * <p>Low gravity, absent or unknown terminal support, gameplay
 * {@code onGround}, contact count, motion direction or a threshold crossing
 * alone can never select free attitude. {@link #REFERENCE_ALIGNED} is also not
 * an angular spring, a collision acceptance test or a recovery transaction: it
 * never requests independent {@code BodyAttitude} ownership, and no
 * {@code SUPPORT_ALIGNING} / {@code SETTLING} contract exists.</p>
 *
 * <p><b>Explicit swim exemption.</b> Only an explicitly selected and currently
 * eligible low-gravity swim request selects {@link #FREE_ATTITUDE}. Its gravity
 * threshold and terminal-support condition qualify entry and retention of that
 * explicit mode only. The swim implementation stays authoritative over the
 * swimmer's pose, the angular-dynamics stage applies no ordinary gravity
 * righting for it, and no alignment target is accumulated for the moment
 * swimming ends.</p>
 *
 * <p><b>Elytra.</b> {@link #ELYTRA_ALIGNED} is a distinct contract with its own
 * gravity restoring, flight alignment, player-roll and angular-drag sources and
 * its own persistent angular velocity. Those gains and that dynamics are never
 * reused for ordinary upright alignment or for low-gravity swim.</p>
 *
 * <p><b>Geometry independence.</b> None of these contracts implies a different
 * ordinary character collider. Attitude updates never call {@code setPos},
 * modify {@code deltaMovement}, install a Q-oriented collider, change support
 * facts or become packet position authority.</p>
 */
public enum CharacterAttitudeContract {
    REFERENCE_ALIGNED,
    FREE_ATTITUDE,
    ELYTRA_ALIGNED;

    public boolean viewBodyJointFollow() { return this == FREE_ATTITUDE; }
    public boolean elytraAlignment() { return this == ELYTRA_ALIGNED; }
    public BodyAttitudeControlAuthority inputAuthority() {
        return switch (this) {
            case REFERENCE_ALIGNED -> BodyAttitudeControlAuthority.none();
            case FREE_ATTITUDE -> BodyAttitudeControlAuthority.full();
            case ELYTRA_ALIGNED -> new BodyAttitudeControlAuthority(0, 0, 1);
        };
    }
}
