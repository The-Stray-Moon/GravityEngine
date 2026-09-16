package cc.sighs.gravityengine.gravity.movement;

/** Player-owned pending request and resolved policy for one actor step. Lifecycle resets clear both. */
public final class CharacterControlStep {
    private long logicalStep = Long.MIN_VALUE;
    private CharacterControlPlan plan;
    private long inputStep = Long.MIN_VALUE;
    private boolean toggleRequested;
    /** Input capture has no environmental evidence and cannot resolve policy. */
    public void captureSprintIntent(long actorStep, boolean pressed) {
        invalidatePlan();
        inputStep = actorStep;
        toggleRequested = pressed;
    }
    public boolean consumeSprintIntent(long actorStep) {
        boolean requested = inputStep == actorStep && toggleRequested;
        inputStep = Long.MIN_VALUE;
        toggleRequested = false;
        return requested;
    }
    public CharacterControlPlan at(long actorStep) { return logicalStep == actorStep ? plan : null; }
    public void install(long actorStep, CharacterControlPlan value) { logicalStep = actorStep; plan = value; }
    /** A native jump invalidates pre-jump policy, but does not discard this step's input. */
    public void invalidatePlan() { logicalStep = Long.MIN_VALUE; plan = null; }
    public void clear() {
        invalidatePlan();
        inputStep = Long.MIN_VALUE;
        toggleRequested = false;
    }
}
