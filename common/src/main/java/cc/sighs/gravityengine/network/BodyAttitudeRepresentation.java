package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.math.Quatd;

/** Numeric representation required by BodyAttitudeState, independent of designer control limits. */
final class BodyAttitudeRepresentation {
    private BodyAttitudeRepresentation() {}

    static Quatd normalizedCopyOrUnusable(Quatd q) {
        double scale = Math.max(
                Math.max(Math.abs(q.x()), Math.abs(q.y())),
                Math.max(Math.abs(q.z()), Math.abs(q.w()))
        );
        if (!q.isFinite() || scale == 0) return q;
        // Scaling first avoids overflow/underflow for otherwise normalizable finite encodings.
        return new Quatd(
                q.x() / scale,
                q.y() / scale,
                q.z() / scale,
                q.w() / scale
        ).normalized();
    }

    static boolean usable(Quatd body, Quatd controller) {
        return usable(body) && usable(controller);
    }
    private static boolean usable(Quatd q) {
        return q.isFinite() && Double.isFinite(q.lengthSquared()) && q.lengthSquared() > 1e-24;
    }
}