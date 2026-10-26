package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbCollision;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbContact;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbSweepHit;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereSweepResult;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow-phase collision detection.
 *
 * <p>Contains no Minecraft entity mutation. All methods are stateless
 * production functions operating on geometric primitives.</p>
 */
public final class CollisionNarrowPhase {
    private CollisionNarrowPhase() {}

    /** Exact strict occupancy, also used by entity sensors. */
    public static boolean intersects(CollisionBody a, CollisionBody b) {
        if (a instanceof CharacterCapsule capsule) {
            if (b instanceof CharacterCapsule other)
                return CapsuleCapsuleCollision.contactGeometry(capsule,other).signedGap() < 0;
            OrientedBox box = (OrientedBox)b;
            return CapsuleAabbCollision.intersects(capsuleInObbLocalSpace(capsule,box),localBounds(box));
        }
        if (b instanceof CharacterCapsule) return intersects(b,a);
        return cc.sighs.gravityengine.math.geometry.ObbSat.overlapDetached(((OrientedBox)a).toObb3d(),((OrientedBox)b).toObb3d()).hasMtv();
    }

    // ---------------------------------------------------------------
    // Shape-neutral production dispatch
    // ---------------------------------------------------------------

    public static Optional<CollisionContact> staticContact(
            CollisionBody body,
            CollisionObstacle obstacle
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return staticContactAt(body, obstacle, 0.0D, new ObbQueryContext());
    }

    public static Optional<CollisionContact> staticContact(
            CollisionBody body,
            CollisionObstacle obstacle,
            ObbQueryContext queryContext
    ) {
        return staticContactAt(body, obstacle, 0.0D, queryContext);
    }

    public static Optional<CollisionContact> staticContactAt(
            CollisionBody body,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return staticContactAt(body, obstacle, obstacleTime, new ObbQueryContext());
    }

    public static Optional<CollisionContact> staticContactAt(
            CollisionBody body,
            CollisionObstacle obstacle,
            double obstacleTime,
            ObbQueryContext queryContext
    ) {
        if (body instanceof CharacterCapsule capsule) {
            return staticCapsuleContactAt(
                    capsule, obstacle, obstacleTime
            );
        }
        if (body instanceof OrientedBox box) {
            return staticContactAt(box, obstacle, obstacleTime, queryContext);
        }
        throw new IllegalStateException(
                "Unhandled collision body type: " + body.getClass().getName()
        );
    }

    public static Optional<CollisionContact> sweptContact(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle
    ) {
        return firstContactOrFailClosed(
                sweptContactResult(body, movement, obstacle)
        );
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return sweptContactResult(body, movement, obstacle, 0.0D, 1.0D, new ObbQueryContext());
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle,
            ObbQueryContext queryContext
    ) {
        return sweptContactResult(body, movement, obstacle, 0.0D, 1.0D, queryContext);
    }

    public static Optional<CollisionContact> sweptContact(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration
    ) {
        return firstContactOrFailClosed(sweptContactResult(
                body, movement, obstacle, obstacleStartTime, intervalDuration
        ));
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return sweptContactResult(
                body, movement, obstacle, obstacleStartTime, intervalDuration,
                new ObbQueryContext()
        );
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration,
            ObbQueryContext queryContext
    ) {
        if (body instanceof CharacterCapsule capsule) {
            return sweptCapsuleContactResult(
                    capsule, movement, obstacle, obstacleStartTime, intervalDuration
            );
        }
        if (body instanceof OrientedBox box) {
            return sweptContactResult(
                    box, movement, obstacle, obstacleStartTime, intervalDuration, queryContext
            );
        }
        throw new IllegalStateException(
                "Unhandled collision body type: " + body.getClass().getName()
        );
    }

    public static Optional<CollisionContact> currentContactProbe(
            CollisionBody body,
            Vec3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContact(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    public static SweepContactResult currentContactProbeResult(
            CollisionBody body,
            Vec3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContactResult(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    public static SweepContactResult currentContactProbeResult(
            CollisionBody body,
            Vec3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime,
            ObbQueryContext queryContext
    ) {
        return sweptContactResult(
                body, probeMovement, obstacle, obstacleTime, 0.0D, queryContext
        );
    }

    // ---------------------------------------------------------------
    // Static OBB vs Aabb3d contact
    // ---------------------------------------------------------------

    /**
     * Computes a static contact between an OBB and an Aabb3d-based obstacle
     * through the authoritative pure SAT kernel.
     */
    public static Optional<CollisionContact> staticContact(
            OrientedBox body,
            CollisionObstacle obstacle
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return staticContactAt(body, obstacle, 0.0D, new ObbQueryContext());
    }

    /** Static contact against the obstacle translated to a normalized tick time. */
    public static Optional<CollisionContact> staticContactAt(
            OrientedBox body,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return staticContactAt(body, obstacle, obstacleTime, new ObbQueryContext());
    }

    static Optional<CollisionContact> staticContactAt(
            OrientedBox body,
            CollisionObstacle obstacle,
            double obstacleTime,
            ObbQueryContext queryContext
    ) {
        if (obstacle instanceof EntityObstacle e && e.exactBodyAt(obstacleTime) instanceof CharacterCapsule capsule)
            return staticCapsuleObbContact(capsule, body, e).map(c -> new CollisionContact(
                    e, capsule.closestPointTo(c.point()), c.normal().negate(), c.penetration(), 0,
                    e.velocityAt(c.point(), obstacleTime), obstacleTime));
        cc.sighs.gravityengine.math.geometry.Obb3d pureBody = queryContext.body(body);
        if (obstacle instanceof EntityObstacle entity) {
            return staticObbContact(body, pureBody, entity.bodyAt(obstacleTime), Vec3d.ZERO, entity, queryContext)
                    .map(contact -> timedContact(contact, entity, body, obstacleTime));
        }
        Vec3d offset = Vec3d.ZERO;
        if (obstacle instanceof BlockObstacle bo) {
            return staticAabbContact(
                    body, pureBody, bo.bounds(), offset, bo, queryContext
            );
        }
        if (obstacle instanceof EntityObstacle) {
            throw new IllegalStateException("rigid dispatch already handled");
        }
        if (obstacle instanceof WorldBorderObstacle wo) {
            return staticAabbContact(
                    body, pureBody, wo.bounds(), offset, wo, queryContext
            );
        }
        if (obstacle instanceof SphereObstacle so) {
            return staticSphereContact(
                    body, pureBody, so, offset);
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    private static Optional<CollisionContact> staticAabbContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            cc.sighs.gravityengine.math.geometry.Aabb3d obstacleBounds,
            Vec3d offset,
            CollisionObstacle obstacle,
            ObbQueryContext queryContext
    ) {
        cc.sighs.gravityengine.math.geometry.ObbOverlapResult overlap = cc.sighs.gravityengine.math.geometry.ObbSat.overlap(
                pureBody,
                queryContext.axisAlignedObstacle(obstacleBounds, offset),
                queryContext.scratch()
        );
        if (overlap.status() != cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.OVERLAPPING) return Optional.empty();
        double pen = overlap.penetration();
        Vec3d normal = overlap.normal();
        cc.sighs.gravityengine.math.geometry.Aabb3d movedBounds = obstacleBounds.move(offset);
        Vec3d contactPoint = closestPointOnAABB(body.center(), movedBounds);

        return Optional.of(new CollisionContact(
                obstacle, contactPoint, normal, pen, 0.0D, obstacle.velocityAt(contactPoint, 0)
        ));
    }

    // ---------------------------------------------------------------
    // Continuous OBB vs Aabb3d swept contact
    // ---------------------------------------------------------------

    /**
     * Continuous swept SAT for a moving OBB against a static Aabb3d obstacle.
     * No displacement substeps or binary search are used.
     */
    public static Optional<CollisionContact> sweptContact(
            OrientedBox body,
            Vec3d movement,
            CollisionObstacle obstacle
    ) {
        return firstContactOrFailClosed(sweptContactResult(body, movement, obstacle));
    }

    /** Explicit sweep result retaining t=0 state and every active contact plane. */
    public static SweepContactResult sweptContactResult(
            OrientedBox body,
            Vec3d movement,
            CollisionObstacle obstacle
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return sweptContactResult(body, movement, obstacle, 0.0D, 1.0D, new ObbQueryContext());
    }

    /**
     * Sweeps over a normalized tick interval. Rigid obstacle motion uses the same operation time domain.
     */
    public static Optional<CollisionContact> sweptContact(
            OrientedBox body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration
    ) {
        return firstContactOrFailClosed(sweptContactResult(
                body, movement, obstacle, obstacleStartTime, intervalDuration
        ));
    }

    /**
     * Sweeps over a normalized tick interval without collapsing meaningful
     * penetration or cotemporal SAT constraints into an optional singleton.
     */
    public static SweepContactResult sweptContactResult(
            OrientedBox body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return sweptContactResult(
                body, movement, obstacle, obstacleStartTime, intervalDuration,
                new ObbQueryContext()
        );
    }

    static SweepContactResult sweptContactResult(
            OrientedBox body,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration,
            ObbQueryContext queryContext
    ) {
        if (!isFinite(movement)
                || !Double.isFinite(obstacleStartTime)
                || !Double.isFinite(intervalDuration)
                || obstacleStartTime < 0.0D
                || intervalDuration < 0.0D
                || obstacleStartTime + intervalDuration
                > 1.0D + CollisionTolerances.TOI_EPSILON) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }
        if (obstacle instanceof EntityObstacle e && e.exactBodyAt(obstacleStartTime) instanceof CharacterCapsule capsule) {
            Vec3d delta = e.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime+intervalDuration));
            var reverse = capsuleObbSweepResult(capsule, delta.subtract(movement), body, e, movement);
            return new SweepContactResult(reverse.initialState(), reverse.contacts().stream().map(c -> {
                double t = obstacleStartTime+intervalDuration*c.timeOfImpact();
                Vec3d point = e.exactBodyAt(t).closestPointTo(c.point());
                return new CollisionContact(e, point, c.normal().negate(), c.penetration(), c.timeOfImpact(), e.velocityAt(point,t),t);
            }).toList(), reverse.indeterminate());
        }
        if (obstacle instanceof EntityObstacle entity) {
            entity.motion().poseAt(obstacleStartTime);
            entity.motion().poseAt(Math.min(1, obstacleStartTime + intervalDuration));
            if (entity.motion().requiresGeneralSweep() && intervalDuration > 0)
                return RigidObstacleSweep.sweep(body, movement, entity, obstacleStartTime, intervalDuration, queryContext);
            OrientedBox initial = entity.bodyAt(obstacleStartTime);
            Vec3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime + intervalDuration));
            var result = sweptObbContact(body, queryContext.body(body), movement,
                    movement.subtract(delta), queryContext.orientedObstacle(initial, Vec3d.ZERO),
                    initial.center(), entity, delta, queryContext);
            return new SweepContactResult(result.initialState(), result.contacts().stream().map(contact ->
                    timedContact(contact, entity, body.move(movement.multiply(contact.timeOfImpact())),
                            Math.min(1, obstacleStartTime + intervalDuration * contact.timeOfImpact()))).toList(),
                    result.indeterminate());
        }
        Vec3d startOffset = Vec3d.ZERO;
        Vec3d obstacleDisplacement =
                Vec3d.ZERO;
        Vec3d relativeMovement =
                movement.subtract(obstacleDisplacement);
        if (!isFinite(startOffset)
                || !isFinite(obstacleDisplacement)
                || !isFinite(relativeMovement)) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }
        cc.sighs.gravityengine.math.geometry.Obb3d pureBody = queryContext.body(body);
        if (obstacle instanceof BlockObstacle bo) {
            return sweptObbContact(
                    body, pureBody, movement, relativeMovement,
                    queryContext.axisAlignedObstacle(bo.bounds(), startOffset),
                    bo.bounds().center().add(startOffset), bo, obstacleDisplacement, queryContext
            );
        }
        if (obstacle instanceof EntityObstacle) {
            throw new IllegalStateException("rigid dispatch already handled");
        }
        if (obstacle instanceof WorldBorderObstacle wo) {
            return sweptObbContact(
                    body, pureBody, movement, relativeMovement,
                    queryContext.axisAlignedObstacle(wo.bounds(), startOffset),
                    wo.bounds().center().add(startOffset), wo,
                    obstacleDisplacement, queryContext
            );
        }
        if (obstacle instanceof SphereObstacle so) {
            return sweptSphereContact(
                    body, pureBody, movement, relativeMovement, so,
                    startOffset, obstacleDisplacement
            );
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    /**
     * Probes contact at one obstacle time without advancing a moving obstacle.
     * Use this for current support/manifold refreshes; it cannot report a
     * surface that will only enter the region later in the tick.
     */
    public static Optional<CollisionContact> currentContactProbe(
            OrientedBox body,
            Vec3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContact(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    /** Explicit current-time contact probe for callers that must inspect overlap. */
    public static SweepContactResult currentContactProbeResult(
            OrientedBox body,
            Vec3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContactResult(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    private static SweepContactResult sweptObbContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            Vec3d worldMovement,
            Vec3d relativeMovement,
            cc.sighs.gravityengine.math.geometry.Obb3d pureObstacle,
            Vec3d obstacleStartCenter,
            CollisionObstacle obstacle,
            Vec3d obstacleDisplacement,
            ObbQueryContext queryContext
    ) {
        cc.sighs.gravityengine.math.geometry.ObbSweepResult sweep;
        try {
            sweep = cc.sighs.gravityengine.math.geometry.ObbSweep.sweep(
                    pureBody,
                    queryContext.relativeDisplacement(relativeMovement),
                    pureObstacle,
                    queryContext.scratch()
            );
        } catch (IllegalArgumentException exception) {
            return new SweepContactResult(SweepInitialState.SEPARATED, List.of(), true);
        }
        SweepInitialState initialState = switch (sweep.initialState()) {
            case SEPARATED -> SweepInitialState.SEPARATED;
            case TOUCHING -> SweepInitialState.TOUCHING;
            case OVERLAPPING -> SweepInitialState.OVERLAPPING;
        };
        if (sweep.status() == cc.sighs.gravityengine.math.geometry.ObbSweepResult.Status.NO_HIT) {
            return new SweepContactResult(initialState, List.of());
        }

        double toi = sweep.timeOfImpact();
        Vec3d bodyAtToi = body.center().add(
                worldMovement.multiply(toi));
        Vec3d obstacleAtToi = obstacleStartCenter.add(
                obstacleDisplacement.multiply(toi));
        Vec3d contactPoint = finiteContactPoint(bodyAtToi, obstacleAtToi);
        cc.sighs.gravityengine.math.geometry.ActiveAxes activeAxes = sweep.activeAxes();
        List<CollisionContact> contacts = new ArrayList<>(activeAxes.size());
        Vec3d normal = queryContext.resultNormal();
        for (int i = 0; i < activeAxes.size(); i++) {
            Vec3d contactNormal = activeAxes.normal(i);
            contacts.add(new CollisionContact(
                    obstacle,
                    contactPoint,
                    contactNormal,
                    sweep.status() == cc.sighs.gravityengine.math.geometry.ObbSweepResult.Status.INITIAL_OVERLAP
                            ? sweep.penetration()
                            : 0.0D,
                    toi,
                    obstacle.velocityAt(contactPoint, 0)
            ));
        }
        return new SweepContactResult(initialState, contacts);
    }

    private static Optional<CollisionContact> staticObbContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            OrientedBox obstacleBody,
            Vec3d offset,
            CollisionObstacle obstacle,
            ObbQueryContext queryContext
    ) {
        cc.sighs.gravityengine.math.geometry.ObbOverlapResult overlap = cc.sighs.gravityengine.math.geometry.ObbSat.overlap(
                pureBody,
                queryContext.orientedObstacle(obstacleBody, offset),
                queryContext.scratch()
        );
        if (overlap.status() != cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.OVERLAPPING) return Optional.empty();
        double penetration = overlap.penetration();
        Vec3d normal = overlap.normal();
        Vec3d contactPoint = new Vec3d(body.center())
                .add(obstacleBody.center()).add(offset).multiply(0.5D);
        return Optional.of(new CollisionContact(
                obstacle, contactPoint, normal, penetration, 0.0D, obstacle.velocityAt(contactPoint, 0)
        ));
    }

    // ---------------------------------------------------------------
    // Static OBB vs generic sphere contact
    // ---------------------------------------------------------------

    /** Static contact between an OBB and a generic sphere obstacle. */
    public static Optional<CollisionContact> staticSphereContact(
            OrientedBox body,
            SphereObstacle obstacle
    ) {
        return staticSphereContact(body, obstacle, Vec3d.ZERO);
    }

    /** One-shot static contact with an explicit obstacle-time offset. */
    public static Optional<CollisionContact> staticSphereContact(
            OrientedBox body,
            SphereObstacle obstacle,
            Vec3d offset
    ) {
        return staticSphereContact(
                body,
                body.toObb3d(),
                obstacle,
                offset);
    }

    private static Optional<CollisionContact> staticSphereContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            SphereObstacle obstacle,
            Vec3d offset
    ) {
        cc.sighs.gravityengine.math.geometry.Sphere3d sphere = obstacle.sphere().moved(offset);
        SphereObbContact sc = SphereObbCollision.contact(sphere, pureBody);
        if (sc.penetration() <= CollisionTolerances.PENETRATION_EPSILON) return Optional.empty();

        Vec3d point = sc.pointOnBox();
        Vec3d normal = sc.normalFromSphereToBox();
        return Optional.of(new CollisionContact(
                obstacle,
                point,
                normal,
                sc.penetration(),
                0.0D,
                obstacle.velocityAt(point, 0)
        ));
    }

    /**
     * Swept contact between a moving OBB and a generic sphere obstacle using
     * the pure {@link SphereObbCollision} kernel.
     */
    public static Optional<CollisionContact> sweptSphereContact(
            OrientedBox body,
            Vec3d movement,
            SphereObstacle obstacle
    ) {
        return firstContactOrFailClosed(sweptSphereContact(
                body,
                body.toObb3d(),
                movement,
                movement,
                obstacle,
                Vec3d.ZERO,
                Vec3d.ZERO
        ));
    }

    private static SweepContactResult sweptSphereContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            Vec3d worldMovement,
            Vec3d relativeMovement,
            SphereObstacle obstacle,
            Vec3d startOffset,
            Vec3d obstacleDisplacement
    ) {
        cc.sighs.gravityengine.math.geometry.Sphere3d sphere = obstacle.sphere().moved(startOffset);
        double initialDistance =
                SphereObbCollision.signedDistance(sphere, pureBody);
        SphereObbContact initialContact =
                SphereObbCollision.contact(sphere, pureBody);
        if (initialDistance < -CollisionTolerances.PENETRATION_EPSILON) {
            Vec3d point = initialContact.pointOnBox();
            Vec3d normal = initialContact.normalFromSphereToBox();
            CollisionContact contact = new CollisionContact(
                    obstacle,
                    point,
                    normal,
                    initialContact.penetration(),
                    0.0D,
                    obstacle.velocityAt(point, 0)
            );
            return new SweepContactResult(SweepInitialState.OVERLAPPING, List.of(contact));
        }
        SweepInitialState initialState = initialDistance <= CollisionTolerances.CONTACT_SLOP
                ? SweepInitialState.TOUCHING
                : SweepInitialState.SEPARATED;
        if (initialState == SweepInitialState.TOUCHING) {
            Vec3d normal = initialContact.normalFromSphereToBox();
            if (new Vec3d(
                    relativeMovement.x(), relativeMovement.y(), relativeMovement.z())
                    .dot(normal)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepContactResult(SweepInitialState.TOUCHING, List.of());
            }
            Vec3d point = initialContact.pointOnBox();
            Vec3d normalVec = normal;
            return new SweepContactResult(
                    SweepInitialState.TOUCHING,
                    List.of(new CollisionContact(
                            obstacle,
                            point,
                            normalVec,
                            0.0D,
                            0.0D,
                            obstacle.velocityAt(point, 0)
                    ))
            );
        }
        SphereSweepResult sweep = SphereObbCollision.sweepDetailed(
                sphere,
                pureBody,
                new Vec3d(
                        relativeMovement.x(),
                        relativeMovement.y(),
                        relativeMovement.z()));
        if (sweep.status() == SphereSweepResult.Status.INDETERMINATE) {
            return new SweepContactResult(initialState, List.of(), true);
        }
        Optional<SphereObbSweepHit> hit = sweep.hit();
        if (hit.isEmpty()) return new SweepContactResult(initialState, List.of());

        SphereObbSweepHit sh = hit.get();
        // The normal from SphereObbSweepHit points from sphere to body
        Vec3d contactPoint = sh.contactPoint();
        Vec3d normal = sh.normalFromSphereToBox();
        return new SweepContactResult(initialState, List.of(new CollisionContact(
                obstacle,
                contactPoint.add(
                        obstacleDisplacement.multiply(sh.timeOfImpact())),
                normal,
                0.0D,
                sh.timeOfImpact(),
                obstacle.velocityAt(contactPoint, 0)
        )));
    }

    // ---------------------------------------------------------------
    // Capsule dispatch
    // ---------------------------------------------------------------

    private static Optional<CollisionContact> staticCapsuleContactAt(
            CharacterCapsule capsule,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        if (obstacle instanceof EntityObstacle entity && entity.exactBodyAt(obstacleTime) instanceof CharacterCapsule other) {
            var g = CapsuleCapsuleCollision.contactGeometry(capsule, other);
            if (g.penetration() <= CollisionTolerances.PENETRATION_EPSILON) return Optional.empty();
            return Optional.of(new CollisionContact(entity,g.witnessB(),g.normal(),g.penetration(),0,
                    entity.velocityAt(g.witnessB(),obstacleTime),obstacleTime));
        }
        if (obstacle instanceof EntityObstacle entity) {
            OrientedBox pose = entity.bodyAt(obstacleTime);
            return staticCapsuleObbContact(capsule, pose, entity).map(c -> new CollisionContact(entity,
                    c.point(), c.normal(), c.penetration(), c.timeOfImpact(), entity.velocityAt(c.point(), obstacleTime), obstacleTime));
        }
        Vec3d offset = Vec3d.ZERO;
        if (obstacle instanceof BlockObstacle block) {
            return staticCapsuleAabbContact(
                    capsule, block.bounds().move(offset), obstacle
            );
        }
        if (obstacle instanceof WorldBorderObstacle border) {
            return staticCapsuleAabbContact(
                    capsule, border.bounds().move(offset), obstacle
            );
        }
        if (obstacle instanceof EntityObstacle) {
            throw new IllegalStateException("rigid dispatch already handled");
        }
        if (obstacle instanceof SphereObstacle sphere) {
            return staticCapsuleSphereContact(
                    capsule,
                    sphere.center().add(offset),
                    sphere.sphere().radius(),
                    obstacle
            );
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    private static Optional<CollisionContact> staticCapsuleAabbContact(
            CharacterCapsule capsule,
            cc.sighs.gravityengine.math.geometry.Aabb3d box,
            CollisionObstacle obstacle
    ) {
        CapsuleAabbCollision.ContactGeometry geometry =
                CapsuleAabbCollision.contactGeometry(capsule, box);
        if (geometry.penetration() <= CollisionTolerances.PENETRATION_EPSILON) {
            return Optional.empty();
        }
        return Optional.of(capsuleContact(
                obstacle,
                geometry.pointOnObstacle(),
                geometry.normal(),
                geometry.penetration(),
                0.0D
        ));
    }

    private static Optional<CollisionContact> staticCapsuleObbContact(
            CharacterCapsule capsule,
            OrientedBox obstacleBody,
            CollisionObstacle obstacle
    ) {
        CharacterCapsule localCapsule =
                capsuleInObbLocalSpace(
                        capsule,
                        obstacleBody
                );

        cc.sighs.gravityengine.math.geometry.Aabb3d localBox =
                localBounds(obstacleBody);

        CapsuleAabbCollision.ContactGeometry local =
                CapsuleAabbCollision.contactGeometry(
                        localCapsule,
                        localBox
                );

        if (local.penetration()
                <= CollisionTolerances.PENETRATION_EPSILON) {
            return Optional.empty();
        }

        Vec3d point =
                obstacleBody.localPointToWorld(
                        local.pointOnObstacle()
                );

        Vec3d normal =
                obstacleBody.localVectorToWorld(
                        local.normal()
                ).normalized();

        return Optional.of(
                capsuleContact(
                        obstacle,
                        point,
                        normal,
                        local.penetration(),
                        0.0D
                )
        );
    }

    private static Optional<CollisionContact> staticCapsuleSphereContact(
            CharacterCapsule capsule,
            Vec3d sphereCenter,
            double sphereRadius,
            CollisionObstacle obstacle
    ) {
        CapsuleSphereCollision.ContactGeometry geometry =
                CapsuleSphereCollision.contactGeometry(
                        capsule, sphereCenter, sphereRadius
                );
        if (geometry.penetration() <= CollisionTolerances.PENETRATION_EPSILON) {
            return Optional.empty();
        }
        return Optional.of(capsuleContact(
                obstacle,
                geometry.pointOnSphere(),
                geometry.normal(),
                geometry.penetration(),
                0.0D
        ));
    }

    private static SweepContactResult sweptCapsuleContactResult(
            CharacterCapsule capsule,
            Vec3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration
    ) {
        if (!isFinite(movement)
                || !Double.isFinite(obstacleStartTime)
                || !Double.isFinite(intervalDuration)
                || obstacleStartTime < 0.0D
                || intervalDuration < 0.0D
                || obstacleStartTime + intervalDuration
                > 1.0D + CollisionTolerances.TOI_EPSILON) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }
        if (obstacle instanceof EntityObstacle entity && entity.exactBodyAt(obstacleStartTime) instanceof CharacterCapsule other) {
            Vec3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1,obstacleStartTime+intervalDuration));
            var result = CapsuleCapsuleCollision.sweep(capsule,movement,other,delta);
            if (result.contact() == null) return new SweepContactResult(result.initialState(),List.of(),result.indeterminate());
            var g = result.contact();
            double t = Math.min(1,obstacleStartTime+intervalDuration*result.timeOfImpact());
            return new SweepContactResult(result.initialState(),List.of(new CollisionContact(entity,g.witnessB(),g.normal(),
                    result.initialState() == SweepInitialState.OVERLAPPING ? g.penetration() : 0,
                    result.timeOfImpact(),entity.velocityAt(g.witnessB(),t),t)),result.indeterminate());
        }
        if (obstacle instanceof EntityObstacle entity) {
            if (entity.motion().requiresGeneralSweep() && intervalDuration > 0)
                return CapsuleRigidObstacleSweep.sweep(capsule, movement, entity, obstacleStartTime, intervalDuration);
            OrientedBox pose = entity.bodyAt(obstacleStartTime);
            Vec3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime + intervalDuration));
            if (delta.lengthSquared() > 0 && translationHasSeparatingFace(capsule, movement.subtract(delta), pose)) {
                var initial = CapsuleAabbCollision.contactGeometry(capsuleInObbLocalSpace(capsule, pose), localBounds(pose));
                return new SweepContactResult(CapsuleCapsuleCollision.classify(initial.signedGap()), List.of());
            }
            var result = capsuleObbSweepResult(capsule, movement.subtract(delta), pose, entity, delta);
            return new SweepContactResult(result.initialState(), result.contacts().stream().map(c -> {
                double time = Math.min(1, obstacleStartTime + intervalDuration * c.timeOfImpact());
                return new CollisionContact(entity, c.point(), c.normal(), c.penetration(), c.timeOfImpact(),
                        entity.velocityAt(c.point(), time), time);
            }).toList(), result.indeterminate());
        }
        Vec3d startOffset = Vec3d.ZERO;
        Vec3d obstacleDisplacement =
                Vec3d.ZERO;
        Vec3d relativeMovement =
                movement.subtract(obstacleDisplacement);
        if (!isFinite(startOffset)
                || !isFinite(obstacleDisplacement)
                || !isFinite(relativeMovement)) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }

        if (obstacle instanceof BlockObstacle block) {
            return capsuleAabbSweepResult(
                    capsule, relativeMovement,
                    block.bounds().move(startOffset), obstacle,
                    obstacleDisplacement
            );
        }
        if (obstacle instanceof WorldBorderObstacle border) {
            return capsuleAabbSweepResult(
                    capsule, relativeMovement,
                    border.bounds().move(startOffset), obstacle,
                    obstacleDisplacement
            );
        }
        if (obstacle instanceof EntityObstacle) {
            throw new IllegalStateException("rigid dispatch already handled");
        }
        if (obstacle instanceof SphereObstacle sphere) {
            return capsuleSphereSweepResult(
                    capsule, relativeMovement,
                    sphere.center().add(startOffset),
                    sphere.sphere().radius(), obstacle,
                    obstacleDisplacement
            );
        }
        throw new IllegalStateException(
                "Unhandled obstacle type: " + obstacle.getClass().getName()
        );
    }

    /** A fixed face separates a translating pair throughout the interval when
     * it separates both endpoints. In particular a rising floor reaching the
     * actor only at the endpoint must not create a skin-early zero-TOI loop. */
    private static boolean translationHasSeparatingFace(CharacterCapsule capsule, Vec3d relative, OrientedBox box) {
        var local = capsuleInObbLocalSpace(capsule, box).enclosingAabb();
        var end = local.move(box.worldVectorToLocal(relative));
        var bounds = localBounds(box);
        double epsilon = cc.sighs.gravityengine.math.geometry.GeometryTolerance.TOUCHING;
        return Math.min(local.minX(), end.minX()) >= bounds.maxX() - epsilon
                || Math.max(local.maxX(), end.maxX()) <= bounds.minX() + epsilon
                || Math.min(local.minY(), end.minY()) >= bounds.maxY() - epsilon
                || Math.max(local.maxY(), end.maxY()) <= bounds.minY() + epsilon
                || Math.min(local.minZ(), end.minZ()) >= bounds.maxZ() - epsilon
                || Math.max(local.maxZ(), end.maxZ()) <= bounds.minZ() + epsilon;
    }

    private static SweepContactResult capsuleAabbSweepResult(
            CharacterCapsule capsule,
            Vec3d relativeMovement,
            cc.sighs.gravityengine.math.geometry.Aabb3d box,
            CollisionObstacle obstacle,
            Vec3d obstacleDisplacement
    ) {
        CapsuleAabbCollision.SweepGeometry sweep = CapsuleAabbCollision.sweep(
                capsule, relativeMovement, box
        );
        if (sweep.indeterminate()) {
            logCapsuleCcdIndeterminate(
                    "capsule/Aabb3d", capsule, relativeMovement, obstacle, sweep
            );
            return new SweepContactResult(sweep.initialState(), List.of(), true);
        }
        if (!sweep.hasContact()) {
            return new SweepContactResult(sweep.initialState(), List.of());
        }
        CapsuleAabbCollision.ContactGeometry geometry = sweep.contact();
        double toi = clamp(sweep.timeOfImpact(), 0.0D, 1.0D);
        Vec3d point = new Vec3d(geometry.pointOnObstacle()).add(
                obstacleDisplacement.multiply(toi)
        );
        double penetration = sweep.initialState() == SweepInitialState.OVERLAPPING
                ? geometry.penetration() : 0.0D;
        return new SweepContactResult(
                sweep.initialState(),
                List.of(capsuleContact(
                        obstacle, point, geometry.normal(), penetration, toi
                ))
        );
    }

    private static SweepContactResult capsuleObbSweepResult(
            CharacterCapsule capsule,
            Vec3d relativeMovement,
            OrientedBox obstacleBody,
            CollisionObstacle obstacle,
            Vec3d obstacleDisplacement
    ) {
        CharacterCapsule localCapsule =
                capsuleInObbLocalSpace(
                        capsule,
                        obstacleBody
                );

        /*
         * relativeMovement is a free vector.
         * It must only be rotated into obstacle-local space.
         */
        Vec3d localMovement =
                obstacleBody.worldVectorToLocal(
                        relativeMovement
                );

        CapsuleAabbCollision.SweepGeometry sweep =
                CapsuleAabbCollision.sweep(
                        localCapsule,
                        localMovement,
                        localBounds(obstacleBody)
                );

        if (sweep.indeterminate()) {
            logCapsuleCcdIndeterminate(
                    "capsule/OBB(local capsule/Aabb3d)",
                    capsule,
                    relativeMovement,
                    obstacle,
                    sweep
            );

            return new SweepContactResult(
                    sweep.initialState(),
                    List.of(),
                    true
            );
        }

        if (!sweep.hasContact()) {
            return new SweepContactResult(
                    sweep.initialState(),
                    List.of()
            );
        }

        CapsuleAabbCollision.ContactGeometry geometry =
                sweep.contact();

        double toi = clamp(
                sweep.timeOfImpact(),
                0.0D,
                1.0D
        );

        /*
         * obstacleBody is already positioned at obstacleStartTime.
         * localPointToWorld applies its center exactly once.
         *
         * obstacleDisplacement * toi then advances the obstacle from the
         * interval start to the actual cotemporal impact time.
         */
        Vec3d point =
                new Vec3d(obstacleBody.localPointToWorld(
                        geometry.pointOnObstacle()
                )).add(
                        obstacleDisplacement.multiply(toi)
                );

        /*
         * Normals are free vectors and must never contain obstacle translation.
         */
        Vec3d normal =
                obstacleBody.localVectorToWorld(
                        geometry.normal()
                ).normalized();

        double penetration =
                sweep.initialState()
                        == SweepInitialState.OVERLAPPING
                        ? geometry.penetration()
                        : 0.0D;

        return new SweepContactResult(
                sweep.initialState(),
                List.of(
                        capsuleContact(
                                obstacle,
                                point,
                                normal,
                                penetration,
                                toi
                        )
                )
        );
    }

    private static SweepContactResult capsuleSphereSweepResult(
            CharacterCapsule capsule,
            Vec3d relativeMovement,
            Vec3d sphereCenter,
            double sphereRadius,
            CollisionObstacle obstacle,
            Vec3d obstacleDisplacement
    ) {
        CapsuleSphereCollision.SweepGeometry sweep = CapsuleSphereCollision.sweep(
                capsule, relativeMovement, sphereCenter, sphereRadius
        );
        if (sweep.indeterminate()) {
            logCapsuleCcdIndeterminate(
                    "capsule/sphere", capsule, relativeMovement, obstacle, sweep
            );
            return new SweepContactResult(sweep.initialState(), List.of(), true);
        }
        if (sweep.contact() == null) {
            return new SweepContactResult(sweep.initialState(), List.of());
        }
        CapsuleSphereCollision.ContactGeometry geometry = sweep.contact();
        double toi = clamp(sweep.timeOfImpact(), 0.0D, 1.0D);
        Vec3d point = new Vec3d(geometry.pointOnSphere()).add(
                obstacleDisplacement.multiply(toi));
        double penetration = sweep.initialState() == SweepInitialState.OVERLAPPING
                ? geometry.penetration() : 0.0D;
        return new SweepContactResult(
                sweep.initialState(),
                List.of(capsuleContact(
                        obstacle, point, geometry.normal(), penetration, toi
                ))
        );
    }

    static CharacterCapsule capsuleInObbLocalSpace(
            CharacterCapsule capsule,
            OrientedBox obstacleBody
    ) {
        Objects.requireNonNull(
                capsule,
                "capsule"
        );
        Objects.requireNonNull(
                obstacleBody,
                "obstacleBody"
        );

        return new CharacterCapsule(
                /*
                 * Capsule center is a world-space point.
                 * worldPointToLocal subtracts obstacleBody.center exactly once.
                 */
                obstacleBody.worldPointToLocal(
                        capsule.center()
                ),

                /*
                 * Capsule axis is a direction/free vector.
                 * Translation is forbidden.
                 */
                obstacleBody.worldVectorToLocal(
                        capsule.axis()
                ),

                capsule.radius(),
                capsule.halfSegmentLength()
        );
    }

    static cc.sighs.gravityengine.math.geometry.Aabb3d localBounds(OrientedBox box) {
        Vec3d extents = box.halfExtents();
        return new cc.sighs.gravityengine.math.geometry.Aabb3d(
                -extents.x(), -extents.y(), -extents.z(),
                extents.x(), extents.y(), extents.z()
        );
    }

    private static CollisionContact capsuleContact(
            CollisionObstacle obstacle,
            Vec3d point,
            Vec3d normal,
            double penetration,
            double timeOfImpact
    ) {
        Vec3d normalized = normal.normalized();
        return new CollisionContact(
                obstacle,
                point,
                normalized,
                Math.max(0.0D, penetration),
                timeOfImpact,
                obstacle.velocityAt(point, 0)
        );
    }

    private static void logCapsuleCcdIndeterminate(
            String primitive,
            CharacterCapsule capsule,
            Vec3d relativeMovement,
            CollisionObstacle obstacle,
            CapsuleAabbCollision.SweepGeometry sweep
    ) {
        // Neutral kernel no longer owns the Minecraft-side diagnostic sink.
    }

    private static void logCapsuleCcdIndeterminate(
            String primitive,
            CharacterCapsule capsule,
            Vec3d relativeMovement,
            CollisionObstacle obstacle,
            CapsuleSphereCollision.SweepGeometry sweep
    ) {
        // Neutral kernel no longer owns the Minecraft-side diagnostic sink.
    }

    // ---------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------

    /** Closest point on an Aabb3d to a given world point. */
    public static Vec3d closestPointOnAABB(Vec3d point, cc.sighs.gravityengine.math.geometry.Aabb3d box) {
        return new Vec3d(
                clamp(point.x(), box.minX(), box.maxX()),
                clamp(point.y(), box.minY(), box.maxY()),
                clamp(point.z(), box.minZ(), box.maxZ())
        );
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static Vec3d finiteContactPoint(Vec3d bodyCenter, Vec3d obstacleCenter) {
        Vec3d point = bodyCenter.add(obstacleCenter).multiply(0.5D);
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) {
            throw new IllegalArgumentException("non-finite SAT contact point: body="
                    + bodyCenter + ", obstacle=" + obstacleCenter);
        }
        return point;
    }

    private static Optional<CollisionContact> firstContactOrFailClosed(
            SweepContactResult result
    ) {
        if (result.indeterminate()) {
            throw new IllegalStateException(
                    "indeterminate narrow-phase sweep must fail closed"
            );
        }
        if (result.initialState() == SweepInitialState.OVERLAPPING) {
            throw new IllegalStateException(
                    "meaningful sweep overlap requires explicit SweepContactResult handling: "
                            + result.contacts()
            );
        }
        return result.contacts().stream().findFirst();
    }

    private static CollisionContact timedContact(CollisionContact contact, EntityObstacle obstacle,
            OrientedBox actor, double time) {
        Vec3d point = obstacle.motion().requiresGeneralSweep()
                ? obstacle.bodyAt(time).closestPointTo(actor.closestPointTo(contact.point())) : contact.point();
        return new CollisionContact(obstacle, point, contact.normal(), contact.penetration(),
                contact.timeOfImpact(), obstacle.velocityAt(point, time), time);
    }

    private static boolean isFinite(Vec3d vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

}
