package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.Obb3d;
import cc.sighs.gravityengine.math.geometry.ObbScratch;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable, operation-owned storage for a batch of OBB queries.
 *
 * <p>One movement or collision-query operation owns one instance and passes it
 * through every candidate query. Instances are neither thread-safe nor
 * suitable for global sharing.  All conversions into the pure
 * {@link Obb3d}/{@link Aabb3d} domain are performed here or in
 * the target adapter; the kernel never converts Minecraft
 * geometry itself.</p>
 */
public final class ObbQueryContext {
    private SupportFaceIdentity preferredSupportFace;

    /** Frozen prior face address, revalidated by every feet query; never a cached scene. */
    public SupportFaceIdentity preferredSupportFace() { return preferredSupportFace; }
    public void setPreferredSupportFace(SupportFaceIdentity face) { preferredSupportFace = face; }

    private static final int MAX_TRACE_SWEEPS = 16;
    private static final int MAX_TRACE_CONTACTS = 8;

    /** Immutable observation copied from contacts already produced by the
     * narrow phase. It is never consulted by collision response. */
    public record ContactObservation(
            String disposition,
            String obstacle,
            Vec3d normal,
            Vec3d point,
            double penetration,
            double timeOfImpact
    ) {
        public ContactObservation {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(obstacle, "obstacle");
        }
    }

    /** One real CCD query, including baseline-overlap disposition. */
    public record SweepObservation(
            int ordinal,
            String phase,
            Vec3d bodyCenter,
            Vec3d movement,
            double windowStartTicks,
            double windowDurationTicks,
            int obstacleCount,
            double earliestTimeOfImpact,
            boolean overlapping,
            boolean indeterminate,
            MovementIndeterminateReason indeterminateReason,
            List<ContactObservation> contacts
    ) {
        public SweepObservation {
            Objects.requireNonNull(phase, "phase");

            Objects.requireNonNull(indeterminateReason, "indeterminateReason");
            contacts = List.copyOf(contacts);
        }
    }

    /** Bounded operation-local collision evidence. A negative baseline count
     * means tracing was enabled but baseline capture did not complete. */
    public record CollisionTrace(
            int baselineObstacleCount,
            List<ContactObservation> baselineContacts,
            List<SweepObservation> sweeps,
            boolean truncated
    ) {
        public CollisionTrace {
            baselineContacts = List.copyOf(baselineContacts);
            sweeps = List.copyOf(sweeps);
        }

        public boolean hasContactEvidence() {
            if (!baselineContacts.isEmpty()) return true;
            for (SweepObservation sweep : sweeps) {
                if (!sweep.contacts().isEmpty() || sweep.overlapping()
                        || sweep.indeterminate()) return true;
            }
            return false;
        }
    }

    private static final class MutableCollisionTrace {
        int baselineObstacleCount = -1;
        final ArrayList<ContactObservation> baselineContacts = new ArrayList<>();
        final ArrayList<SweepObservation> sweeps = new ArrayList<>();
        final ArrayList<String> footFaces = new ArrayList<>();
        boolean footFacesTruncated;
        boolean truncated;
        String phase = "UNLABELED";
    }

    private MutableCollisionTrace collisionTrace;

    /** Enables and resets diagnostic observation for the next solve. */
    public void beginCollisionTrace() {
        this.collisionTrace = new MutableCollisionTrace();
    }

    public CollisionTrace collisionTrace() {
        MutableCollisionTrace trace = this.collisionTrace;
        return trace == null ? null : new CollisionTrace(
                trace.baselineObstacleCount,
                trace.baselineContacts,
                trace.sweeps,
                trace.truncated
        );
    }

    /** Bounded observations from the real foot query; never recompute geometry for logging. */
    void recordFootFace(CollisionBody body, CollisionObstacle obstacle, int face,
            Vec3d normal, double upDot, boolean eligible, boolean exposed, double time, double distance) {
        var trace = this.collisionTrace;
        if (trace == null) return;
        if (trace.footFaces.size() >= 24) { trace.footFacesTruncated = true; return; }
        trace.footFaces.add("phase=" + trace.phase + ",center=" + body.center()
                + ",time=" + time + ",searchDistance=" + distance + ",obstacle=" + (obstacle instanceof BlockObstacle block ? block.blockPos() + ":" + block.bounds()
                : obstacle instanceof EntityObstacle entity
                        ? entity.providerNamespace()
                        + ":"
                        + entity.sourceId()
                        + ":"
                        + entity.primitiveId()
                        : obstacle.getClass().getSimpleName())
                + ",face=" + face
                + ",normal=(" + normal.x() + "," + normal.y() + "," + normal.z() + "),normalup=" + upDot
                + ",footEligible=" + eligible + ",exposed=" + exposed);
    }

    public List<String> footFaceObservations() {
        return collisionTrace == null ? List.of() : List.copyOf(collisionTrace.footFaces);
    }

    public boolean footFaceObservationsTruncated() {
        return collisionTrace != null && collisionTrace.footFacesTruncated;
    }

    void collisionTracePhase(String phase) {
        if (this.collisionTrace != null) {
            this.collisionTrace.phase = Objects.requireNonNull(phase, "phase");
        }
    }

    void recordCollisionBaseline(
            int obstacleCount,
            List<CollisionContact> contacts
    ) {
        MutableCollisionTrace trace = this.collisionTrace;
        if (trace == null) return;
        trace.baselineObstacleCount = obstacleCount;
        for (CollisionContact contact : contacts) {
            addContact(trace.baselineContacts, trace, "MEANINGFUL_BASELINE", contact);
        }
    }

    void recordCollisionSweep(
            CollisionBody body,
            Vec3d movement,
            SweepTimeWindow window,
            KinematicSweepKernel.EarliestContactBatch result,
            List<CollisionContact> toleratedBaselineOverlaps,
            List<CollisionContact> newInitialOverlaps
    ) {
        MutableCollisionTrace trace = this.collisionTrace;
        if (trace == null) return;
        if (trace.sweeps.size() >= MAX_TRACE_SWEEPS) {
            trace.truncated = true;
            return;
        }
        ArrayList<ContactObservation> contacts = new ArrayList<>();
        for (CollisionContact contact : toleratedBaselineOverlaps) {
            addContact(contacts, trace, "TOLERATED_BASELINE_OVERLAP", contact);
        }
        for (CollisionContact contact : newInitialOverlaps) {
            addContact(contacts, trace, "REJECTED_NEW_INITIAL_OVERLAP", contact);
        }
        for (CollisionContact contact : result.contacts()) {
            addContact(contacts, trace, "EARLIEST_CONTACT", contact);
        }
        trace.sweeps.add(new SweepObservation(
                trace.sweeps.size(),
                trace.phase,
                body.center(),
                movement,
                window.startTicks(),
                window.durationTicks(),
                result.obstacles().size(),
                result.timeOfImpact(),
                result.overlapping(),
                result.indeterminate(),
                result.indeterminateReason(),
                contacts
        ));
    }

    private static void addContact(
            List<ContactObservation> target,
            MutableCollisionTrace trace,
            String disposition,
            CollisionContact contact
    ) {
        if (target.size() >= MAX_TRACE_CONTACTS) {
            trace.truncated = true;
            return;
        }
        target.add(new ContactObservation(
                disposition,
                BodyCollisionDelta.label(contact.obstacle()),
                contact.normal(),
                contact.point(),
                contact.penetration(),
                contact.timeOfImpact()
        ));
    }

    /** Observation of the existing step-selection branch; never read by physics. */
    public record StepDecision(boolean attempted, boolean accepted, String reason,
                               double baselineProgress, double candidateProgress) {}
    private boolean stepObserved, stepAttempted, stepAccepted;
    private String stepReason;
    private double stepBaselineProgress, stepCandidateProgress;
    /** Materialize only for a diagnostic consumer; production retains primitive evidence. */
    public StepDecision stepDecision() {
        return stepObserved ? new StepDecision(stepAttempted, stepAccepted, stepReason,
                stepBaselineProgress, stepCandidateProgress) : null;
    }
    public void setStepDecision(StepDecision value) {
        stepObserved = value != null;
        if (value != null) recordStepDecision(value.attempted(), value.accepted(), value.reason(),
                value.baselineProgress(), value.candidateProgress());
    }
    public void recordStepDecision(boolean attempted, boolean accepted, String reason,
            double baselineProgress, double candidateProgress) {
        stepObserved = true;
        stepAttempted = attempted;
        stepAccepted = accepted;
        stepReason = reason;
        stepBaselineProgress = baselineProgress;
        stepCandidateProgress = candidateProgress;
    }
    private final ObbScratch scratch = new ObbScratch();
    private final Vec3d resultNormal = Vec3d.ZERO;
    private OrientedBox primaryBodySource;
    private Obb3d primaryBody;
    private OrientedBox secondaryBodySource;
    private Obb3d secondaryBody;
    private CollisionWorkTracker workTracker = new CollisionWorkTracker(
            CollisionWorkBudget.defaults()
    );
    /*
     * Support classification is secondary derived evidence. It is bounded, but
     * exhausting this tracker must not poison hard movement legality.
     */
    private CollisionWorkTracker supportWorkTracker =
            new CollisionWorkTracker(
                    CollisionWorkBudget.defaults()
            );

    public CollisionWorkTracker supportWorkTracker() {
        return this.supportWorkTracker;
    }

    /** Test/diagnostic seam for intentionally small support-query budgets. */
    public void setSupportWorkTracker(
            CollisionWorkTracker tracker
    ) {
        this.supportWorkTracker =
                Objects.requireNonNull(
                        tracker,
                        "tracker"
                );
    }

    /**
     * Operation-local accounting shared by every query phase of one movement
     * operation. One instance per operation, never static/global.
     */
    public CollisionWorkTracker workTracker() {
        return this.workTracker;
    }

    /** Narrows the shared tracker for a deliberately small-budget test/diagnostic path. */
    public void setWorkTracker(CollisionWorkTracker tracker) {
        this.workTracker = Objects.requireNonNull(tracker, "tracker");
    }

    ObbScratch scratch() {
        return this.scratch;
    }

    Obb3d body(OrientedBox body) {
        Objects.requireNonNull(body, "body");
        if (body == this.primaryBodySource) return this.primaryBody;
        if (body == this.secondaryBodySource) return this.secondaryBody;
        Obb3d converted = body.toObb3d();
        this.secondaryBodySource = this.primaryBodySource;
        this.secondaryBody = this.primaryBody;
        this.primaryBodySource = body;
        this.primaryBody = converted;
        return converted;
    }

    Obb3d axisAlignedObstacle(Aabb3d box, Vec3d translation) {
        Vec3d center = box.center();
        Vec3d half = box.halfExtents();
        if (translation != null) {
            center = center.add(translation);
        }
        return new Obb3d(center, half, OrthonormalFrame3d.IDENTITY);
    }

    Obb3d orientedObstacle(OrientedBox box, Vec3d translation) {
        Objects.requireNonNull(box, "box");
        if (translation == null) {
            return box.toObb3d();
        }
        return new Obb3d(
                new Vec3d(box.center()).add(translation),
                box.halfExtents(),
                box.orientation());
    }

    Vec3d relativeDisplacement(Vec3d displacement) {
        return displacement;
    }

    Vec3d resultNormal() {
        return this.resultNormal;
    }
}
