package cc.sighs.gravityengine.gravity.presentation;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.ScalarMath;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;

/** Client-owned visual orientation timeline. No physical state or position history.
 * Packets request convergence; completed client ticks retarget the timeline.
 * Render samples never advance it. */
public final class ClientGravityPresentationState {
    private GravityFrame previous;
    private GravityFrame current;
    private double startTime;
    private double duration = 1;
    private boolean convergenceRequested;

    /** A physical commit is already installed. Only its visual transition waits
     * for the next completed tick, where the integer and fractional clocks agree.
     * Multiple commits before that boundary coalesce onto its latest target. */
    public void requestConvergence() {
        convergenceRequested = true;
    }

    public void tick(GravityFrame target, long tick) {
        retarget(target, tick, convergenceRequested ? 3 : 1);
        convergenceRequested = false;
    }

    private void retarget(GravityFrame target, double time, double interval) {
        java.util.Objects.requireNonNull(target, "target");
        if (!Double.isFinite(time) || !Double.isFinite(interval) || interval <= 0) {
            throw new IllegalArgumentException("invalid presentation interval");
        }
        if (current == null) {
            previous = current = target;
            startTime = time;
            return;
        }
        if (current.orientation().equals(target.orientation()) && current.strength() == target.strength()) return;
        previous = sampleAt(time);
        current = target;
        startTime = time;
        duration = interval;
    }

    public GravityFrame sampleAt(double time) {
        if (current == null) return GravityFrame.DEFAULT;
        double progress = ScalarMath.clamp((time - startTime) / duration, 0, 1);
        if (progress == 0) return previous;
        if (progress == 1 || previous == current) return current;
        var rotation = BodyOrientation3d.quaternion(previous.orientation())
                .slerp(BodyOrientation3d.quaternion(current.orientation()), progress).normalized();
        return new GravityFrame(current.samplePoint(), BodyOrientation3d.frame(rotation),
                previous.strength() + (current.strength() - previous.strength()) * progress);
    }

    /** Pending convergence keeps presentation active across a physical disable
     * until the completed tick can start the transition to the default basis. */
    public boolean transitioning(double time) {
        return current != null && (convergenceRequested
                || time < startTime + duration && previous != current);
    }
    public GravityFrame previous() { return previous; }
    public GravityFrame current() { return current; }
}
