package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import java.util.Objects;

/**
 * Engine-owned semantic authority of one entity's gravity, independent of the
 * physical acceleration currently produced by that authority.
 *
 * <p>Authority answers "who owns gravity?" - a published FIELD snapshot, an
 * explicit DIRECT assignment, and at which revision that ownership was
 * established. It deliberately carries no acceleration, no
 * {@link cc.sighs.gravityengine.gravity.GravityFrame} and no sample point: the
 * physical result of an authority is produced once per operation by
 * {@link GravityEvaluationService} and published as a
 * {@link GravityEvaluationSnapshot}.</p>
 *
 * <p>This separation exists because a field evaluator may legitimately depend
 * on time, velocity and interval length. Assignment state must therefore never
 * double as a cache of physical gravity.</p>
 *
 * <p>Loader-neutral: no Minecraft, loader, Mixin or optional-mod type may
 * appear here.</p>
 */
public record GravityAuthorityState(
        GravityAuthorityMode mode,
        boolean explicitAssignment,
        boolean fieldPresent,
        long revision
) {
    public GravityAuthorityState {
        Objects.requireNonNull(mode, "mode");
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "authority revision must be non-negative: " + revision
            );
        }
        if (mode == GravityAuthorityMode.DIRECT && !explicitAssignment) {
            throw new IllegalArgumentException(
                    "DIRECT authority always owns an explicit assignment"
            );
        }
        if (mode == GravityAuthorityMode.FIELD && explicitAssignment) {
            throw new IllegalArgumentException(
                    "FIELD authority never owns an explicit assignment"
            );
        }
    }

    /**
     * A published field snapshot. {@code fieldPresent} means the published
     * field set contained at least one contribution at publication time; it is
     * contribution identity, never resultant magnitude.
     */
    public static GravityAuthorityState field(
            boolean fieldPresent,
            long revision
    ) {
        return new GravityAuthorityState(
                GravityAuthorityMode.FIELD,
                false,
                fieldPresent,
                revision
        );
    }

    /** An explicit API/script DIRECT override. */
    public static GravityAuthorityState direct(long revision) {
        return new GravityAuthorityState(
                GravityAuthorityMode.DIRECT,
                true,
                false,
                revision
        );
    }

    public boolean ownsFieldAuthority() {
        return mode == GravityAuthorityMode.FIELD;
    }

    public boolean ownsDirectAuthority() {
        return mode == GravityAuthorityMode.DIRECT;
    }

    /**
     * Authority binding used by the canonical operation evaluation. Two
     * authority states are interchangeable for reuse when they describe the
     * same owner and the same revision.
     */
    public boolean sameBinding(GravityAuthorityState other) {
        return other != null
                && mode == other.mode
                && explicitAssignment == other.explicitAssignment
                && fieldPresent == other.fieldPresent
                && revision == other.revision;
    }
}
