package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.presentation.CompletedGravityFrame;
import javax.annotation.Nullable;

/** Client entity tick history. Publications are latest-value evidence, never animation steps. */
final class ClientGravityPresentationState {
    private GravityFrame previous;
    private GravityFrame current;
    private long observedRevision;
    private long observedDiscontinuityRevision;
    private long lastEntityTick = Long.MIN_VALUE;

    void tick(long entityTick, long revision, long discontinuityRevision,
            @Nullable CompletedGravityFrame latest) {
        if (revision < observedRevision) return;
        if (latest != null && latest.revision() != revision) {
            throw new IllegalArgumentException("publication/revision mismatch");
        }
        boolean reset = observedRevision < discontinuityRevision || current == null
                || entityTick < lastEntityTick;
        if (!reset && entityTick == lastEntityTick) return;
        GravityFrame next = latest == null ? null : latest.frame();
        previous = reset || next == null ? next : current;
        current = next;
        observedRevision = revision;
        observedDiscontinuityRevision = discontinuityRevision;
        lastEntityTick = entityTick;
    }

    @Nullable GravityFrame sample(long discontinuityRevision,
            @Nullable CompletedGravityFrame latest, float partialTick) {
        if (observedRevision < discontinuityRevision) return latest == null ? null : latest.frame();
        if (current == null) return latest == null ? null : latest.frame();
        if (previous == current) return current;
        return GravityFrame.interpolateForPresentation(previous, current,
                ClientGravityFrameSampler.sanitizePartialTick(partialTick));
    }

    GravityFrame previous() { return previous; }
    GravityFrame current() { return current; }
    long observedRevision() { return observedRevision; }
    long observedDiscontinuityRevision() { return observedDiscontinuityRevision; }
}
