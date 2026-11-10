package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.ScalarMath;

/**
 * Kinematic local head-joint overflow.
 *
 * <p><b>Ownership.</b> This solver is a KINEMATIC body-follow writer. It may
 * rewrite the body attitude only while the actor has no angular dynamics, for
 * example the FREE_ATTITUDE body/view joint-follow path. It must never run
 * after the dynamic angular solver for an Elytra-shaped actor: that would be a
 * second writer of physical {@code q} and would silently invalidate the
 * momentum state it left behind. The dynamic path therefore returns the
 * motion result untouched and only constrains the view/joint state.</p>
 *
 * <p>It never changes the controller, angular momentum or character
 * geometry.</p>
 */
public final class BodyViewConstraintSolver {
    private BodyViewConstraintSolver() {}
    public record Result(BodyAttitudeStepResult step, BodyRelativeViewState view) {}
    public record Follow(Quatd body, Quatd joint) {
        public Follow { body = SemanticView.normalized(body); joint = SemanticView.normalized(joint); }
    }

    /** Joint convention Rz(roll) Ry(yaw) Rx(pitch), in body-local coordinates only.
     * Each small controller interval starts with a bounded joint, so decomposition stays
     * away from the local yaw pole. World pitch/roll may pass through any number of turns.
     * The previous controller is continuity evidence, never an absolute Euler accumulator.
     */
    public static Follow follow(Quatd oldBody, Quatd previousController,
            Quatd controller, double limit) {
        if (!Double.isFinite(limit) || limit <= 0 || limit >= Math.PI / 2)
            throw new IllegalArgumentException("joint limit must be between zero and pi/2");
        Quatd start = SemanticView.normalized(previousController);
        Quatd end = SemanticView.normalized(controller);
        Quatd body = SemanticView.normalized(oldBody);
        // Lifecycle/remote baselines can begin outside the joint; initialize their local bound once.
        body = constrain(body, start, limit);
        double angle = 2 * Math.acos(Math.min(1, Math.abs(start.dot(end))));
        int steps = Math.max(1, (int)Math.ceil(angle / Math.toRadians(2)));
        for (int i = 1; i <= steps; i++) {
            Quatd q = i == steps ? end : start.slerp(end, (double)i / steps);
            body = constrain(body, q, limit);
        }
        return new Follow(body, body.conjugate().multiply(end));
    }

    private static Quatd constrain(Quatd body, Quatd controller, double limit) {
        Quatd relative = body.conjugate().multiply(controller).normalized();
        Vec3d joint = localJointAngles(relative);
        double x = clamp(joint.x(), limit), y = clamp(joint.y(), limit), z = clamp(joint.z(), limit);
        if (Math.abs(x-joint.x()) < 1e-12 && Math.abs(y-joint.y()) < 1e-12 && Math.abs(z-joint.z()) < 1e-12) return body;
        Quatd bounded = Quatd.rotationZYX(z, y, x);
        return controller.multiply(bounded.conjugate()).normalized();
    }
    /** ZYX extraction of a normalized local joint. JOML 1.10.5 has a sign bug in this method. */
    public static Vec3d localJointAngles(Quatd q) {
        return new Vec3d(Math.atan2(q.y()*q.z() + q.w()*q.x(), .5-q.x()*q.x()-q.y()*q.y()),
                Math.asin(ScalarMath.clamp(2*(q.w()*q.y()-q.x()*q.z()), -1, 1)),
                Math.atan2(q.x()*q.y() + q.w()*q.z(), .5-q.y()*q.y()-q.z()*q.z()));
    }
    private static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }

    public static Result solve(BodyAttitudeStepResult motion, BodyLookControlSample look,
            double maxHeadRadians, boolean follow) {
        BodyAttitudeState state = motion.nextState();
        SemanticView controller = look.nextViewState().semantic(state.currentWorldFromBody());
        if (!follow) {
            /*
             * Dynamic (or otherwise non-joint-following) path: the physical
             * body attitude produced by the angular solver stands. The view
             * constraint only publishes view/joint state.
             */
            return new Result(motion, BodyRelativeViewState.fromSemantic(controller,
                    state.currentWorldFromBody(), look.requestedLocalLook()));
        }
        if (state.hasAngularDynamics()) {
            throw new IllegalStateException(
                    "BodyViewConstraintSolver must not rewrite the physical "
                            + "attitude of a dynamic body; an explicit "
                            + "ownership handoff is required first");
        }
        var solved = follow(state.currentWorldFromBody(), look.previousController().worldFromController(),
                controller.worldFromController(), maxHeadRadians);
        var next = state.withKinematicOrientation(solved.body());
        var result = new BodyAttitudeStepResult(next, motion.appliedTorqueWorld(),
                motion.rotationErrorRadians(), motion.substepCount(), motion.timeClamped(),
                motion.trajectory().appendKinematic(solved.body(), 1e-10));
        return new Result(result, BodyRelativeViewState.fromSemantic(controller, solved.body(), look.requestedLocalLook()));
    }
}
