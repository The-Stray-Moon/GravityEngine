package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;

import java.util.Objects;

/** Immutable continuous-SAT result independent of any character controller. */
public final class ObbSweepResult {
    public enum InitialState {
        SEPARATED,
        TOUCHING,
        OVERLAPPING
    }

    public enum Status {
        NO_HIT,
        HIT,
        INITIAL_OVERLAP
    }

    /** Canonical allocation-free result for the overwhelmingly common free-flight case. */
    public static final ObbSweepResult NO_HIT_SEPARATED = new ObbSweepResult(
            InitialState.SEPARATED, Status.NO_HIT, 1.0D, 0.0D, ActiveAxes.empty()
    );

    /** Canonical legal-touch result for tangent or outward motion. */
    public static final ObbSweepResult NO_HIT_TOUCHING = new ObbSweepResult(
            InitialState.TOUCHING, Status.NO_HIT, 1.0D, 0.0D, ActiveAxes.empty()
    );

    private final InitialState initialState;
    private final Status status;
    private final double timeOfImpact;
    private final double penetration;
    private final ActiveAxes activeAxes;

    private ObbSweepResult(
            InitialState initialState,
            Status status,
            double timeOfImpact,
            double penetration,
            ActiveAxes activeAxes
    ) {
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        this.status = Objects.requireNonNull(status, "status");
        this.timeOfImpact = timeOfImpact;
        this.penetration = penetration;
        this.activeAxes = Objects.requireNonNull(activeAxes, "activeAxes");
    }

    static ObbSweepResult noHit(InitialState initialState) {
        return switch (initialState) {
            case SEPARATED -> NO_HIT_SEPARATED;
            case TOUCHING -> NO_HIT_TOUCHING;
            case OVERLAPPING -> throw new IllegalArgumentException(
                    "initial overlap must not be represented as NO_HIT"
            );
        };
    }

    static ObbSweepResult hit(InitialState initialState, double time, ActiveAxes activeAxes) {
        return new ObbSweepResult(initialState, Status.HIT, time, 0.0D, activeAxes);
    }

    static ObbSweepResult initialOverlap(double penetration, ActiveAxes activeAxes) {
        return new ObbSweepResult(
                InitialState.OVERLAPPING,
                Status.INITIAL_OVERLAP,
                0.0D,
                penetration,
                activeAxes
        );
    }

    public InitialState initialState() { return this.initialState; }
    public Status status() { return this.status; }
    public double timeOfImpact() { return this.timeOfImpact; }
    public double penetration() { return this.penetration; }
    public ActiveAxes activeAxes() { return this.activeAxes; }

    public boolean hasBindingNormal() { return !this.activeAxes.isEmpty(); }

    public Vector3d bindingNormal(Vector3d dest) {
        if (!hasBindingNormal()) throw new IllegalStateException("result has no binding normal");
        return this.activeAxes.normal(0, dest);
    }
}
