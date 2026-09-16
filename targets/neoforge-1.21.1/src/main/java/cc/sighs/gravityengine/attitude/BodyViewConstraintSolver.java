package cc.sighs.gravityengine.attitude;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Kinematic local head-joint overflow. Never changes controller, momentum or character geometry. */
public final class BodyViewConstraintSolver {
    private BodyViewConstraintSolver() {}
    public record Result(BodyAttitudeStepResult step, BodyRelativeViewState view) {}
    public record Follow(Quaterniond body, Quaterniond joint) {
        public Follow { body = SemanticView.normalized(body); joint = SemanticView.normalized(joint); }
        @Override public Quaterniond body() { return new Quaterniond(body); }
        @Override public Quaterniond joint() { return new Quaterniond(joint); }
    }

    /** Joint convention Rz(roll) Ry(yaw) Rx(pitch), in body-local coordinates only.
     * Each small controller interval starts with a bounded joint, so decomposition stays
     * away from the local yaw pole. World pitch/roll may pass through any number of turns.
     * The previous controller is continuity evidence, never an absolute Euler accumulator.
     */
    public static Follow follow(Quaterniond oldBody, Quaterniond previousController,
            Quaterniond controller, double limit) {
        if (!Double.isFinite(limit) || limit <= 0 || limit >= Math.PI / 2)
            throw new IllegalArgumentException("joint limit must be between zero and pi/2");
        Quaterniond start = SemanticView.normalized(previousController);
        Quaterniond end = SemanticView.normalized(controller);
        Quaterniond body = SemanticView.normalized(oldBody);
        // Lifecycle/remote baselines can begin outside the joint; initialize their local bound once.
        body = constrain(body, start, limit);
        double angle = 2 * Math.acos(Math.min(1, Math.abs(start.dot(end))));
        int steps = Math.max(1, (int)Math.ceil(angle / Math.toRadians(2)));
        for (int i = 1; i <= steps; i++) {
            Quaterniond q = i == steps ? end : new Quaterniond(start).slerp(end, (double)i / steps);
            body = constrain(body, q, limit);
        }
        return new Follow(body, new Quaterniond(body).conjugate().mul(end));
    }

    private static Quaterniond constrain(Quaterniond body, Quaterniond controller, double limit) {
        Quaterniond relative = new Quaterniond(body).conjugate().mul(controller).normalize();
        Vector3d joint = localJointAngles(relative);
        double x = clamp(joint.x, limit), y = clamp(joint.y, limit), z = clamp(joint.z, limit);
        if (Math.abs(x-joint.x) < 1e-12 && Math.abs(y-joint.y) < 1e-12 && Math.abs(z-joint.z) < 1e-12) return body;
        Quaterniond bounded = new Quaterniond().rotationZYX(z, y, x);
        return new Quaterniond(controller).mul(bounded.conjugate()).normalize();
    }
    /** ZYX extraction of a normalized local joint. JOML 1.10.5 has a sign bug in this method. */
    public static Vector3d localJointAngles(Quaterniond q) {
        return new Vector3d(Math.atan2(q.y*q.z + q.w*q.x, .5-q.x*q.x-q.y*q.y),
                Math.asin(Math.clamp(2*(q.w*q.y-q.x*q.z), -1, 1)),
                Math.atan2(q.x*q.y + q.w*q.z, .5-q.y*q.y-q.z*q.z));
    }
    private static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }

    public static Result solve(BodyAttitudeStepResult motion, BodyLookControlSample look,
            double maxHeadRadians, boolean follow) {
        BodyAttitudeState state = motion.nextState();
        SemanticView controller = look.nextViewState().semantic(state.currentWorldFromBody());
        if (!follow) return new Result(motion, BodyRelativeViewState.fromSemantic(controller,
                state.currentWorldFromBody(), look.requestedLocalLook()));
        var solved = follow(state.currentWorldFromBody(), look.previousController().worldFromController(),
                controller.worldFromController(), maxHeadRadians);
        var next = new BodyAttitudeState(state.previousWorldFromBody(), solved.body(),
                state.angularVelocityWorld(), state.tick(), state.revision(), true);
        var result = new BodyAttitudeStepResult(next, motion.appliedAngularAccelerationWorld(),
                motion.rotationErrorRadians(), motion.substepCount(), motion.timeClamped(),
                motion.trajectory().appendKinematic(solved.body(), 1e-10));
        return new Result(result, BodyRelativeViewState.fromSemantic(controller, solved.body(), look.requestedLocalLook()));
    }
}
