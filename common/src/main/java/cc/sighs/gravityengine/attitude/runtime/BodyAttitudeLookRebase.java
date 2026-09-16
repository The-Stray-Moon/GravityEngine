package cc.sighs.gravityengine.attitude.runtime;

/**
 * One-time Vanilla scalar projection committed with ownership release or hard
 * suspension. The coordinator writes {@code yRot}/{@code xRot} in the same
 * actor state installation; no duplicate scalar state is retained in the
 * semantic view. The source-named pair is the receiving Vanilla representation.
 */
public record BodyAttitudeLookRebase(
        float sourceYaw,
        float sourcePitch
) {
    public BodyAttitudeLookRebase {
        if (!Float.isFinite(sourceYaw) || !Float.isFinite(sourcePitch)) {
            throw new IllegalArgumentException("look rebase must be finite");
        }
    }
}
