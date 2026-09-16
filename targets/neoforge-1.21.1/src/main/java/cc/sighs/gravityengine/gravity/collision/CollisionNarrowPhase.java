package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbCollision;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbContact;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbSweepHit;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereSweepResult;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Vector3d;

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
        return switch (body) {
            case CharacterCapsule capsule -> staticCapsuleContactAt(
                    capsule, obstacle, obstacleTime
            );
            case OrientedBox box -> staticContactAt(box, obstacle, obstacleTime, queryContext);
        };
    }

    public static Optional<CollisionContact> sweptContact(
            CollisionBody body,
            Vector3d movement,
            CollisionObstacle obstacle
    ) {
        return firstContactOrFailClosed(
                sweptContactResult(body, movement, obstacle)
        );
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vector3d movement,
            CollisionObstacle obstacle
    ) {
        // One-shot convenience API; production candidate loops pass an operation context.
        return sweptContactResult(body, movement, obstacle, 0.0D, 1.0D, new ObbQueryContext());
    }

    public static SweepContactResult sweptContactResult(
            CollisionBody body,
            Vector3d movement,
            CollisionObstacle obstacle,
            ObbQueryContext queryContext
    ) {
        return sweptContactResult(body, movement, obstacle, 0.0D, 1.0D, queryContext);
    }

    public static Optional<CollisionContact> sweptContact(
            CollisionBody body,
            Vector3d movement,
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
            Vector3d movement,
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
            Vector3d movement,
            CollisionObstacle obstacle,
            double obstacleStartTime,
            double intervalDuration,
            ObbQueryContext queryContext
    ) {
        return switch (body) {
            case CharacterCapsule capsule -> sweptCapsuleContactResult(
                    capsule, movement, obstacle, obstacleStartTime, intervalDuration
            );
            case OrientedBox box -> sweptContactResult(
                    box, movement, obstacle, obstacleStartTime, intervalDuration, queryContext
            );
        };
    }

    public static Optional<CollisionContact> currentContactProbe(
            CollisionBody body,
            Vector3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContact(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    public static SweepContactResult currentContactProbeResult(
            CollisionBody body,
            Vector3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContactResult(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    public static SweepContactResult currentContactProbeResult(
            CollisionBody body,
            Vector3d probeMovement,
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
            return staticObbContact(body, pureBody, entity.bodyAt(obstacleTime), new Vector3d(), entity, queryContext)
                    .map(contact -> timedContact(contact, entity, body, obstacleTime));
        }
        Vector3d offset = new Vector3d();
        return switch (obstacle) {
            case BlockObstacle bo -> staticAabbContact(
                    body, pureBody, bo.bounds(), offset, bo, queryContext
            );
            case EntityObstacle ignored -> throw new IllegalStateException("rigid dispatch already handled");
            case WorldBorderObstacle wo -> staticAabbContact(
                    body, pureBody, wo.bounds(), offset, wo, queryContext
            );
            case SphereObstacle so -> staticSphereContact(
                    body, pureBody, so, offset);
        };
    }

    private static Optional<CollisionContact> staticAabbContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            cc.sighs.gravityengine.math.geometry.Aabb3d obstacleBounds,
            Vector3d offset,
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
        Vector3d normal = overlap.normal(queryContext.resultNormal());
        cc.sighs.gravityengine.math.geometry.Aabb3d movedBounds = obstacleBounds.move(offset);
        Vector3d contactPoint = closestPointOnAABB(body.center(), movedBounds);

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
            Vector3d movement,
            CollisionObstacle obstacle
    ) {
        return firstContactOrFailClosed(sweptContactResult(body, movement, obstacle));
    }

    /** Explicit sweep result retaining t=0 state and every active contact plane. */
    public static SweepContactResult sweptContactResult(
            OrientedBox body,
            Vector3d movement,
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
            Vector3d movement,
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
            Vector3d movement,
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
            Vector3d movement,
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
            Vector3d delta = e.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime+intervalDuration));
            var reverse = capsuleObbSweepResult(capsule, new Vector3d(delta).sub(movement), body, e, movement);
            return new SweepContactResult(reverse.initialState(), reverse.contacts().stream().map(c -> {
                double t = obstacleStartTime+intervalDuration*c.timeOfImpact();
                Vector3d point = e.exactBodyAt(t).closestPointTo(c.point());
                return new CollisionContact(e, point, c.normal().negate(), c.penetration(), c.timeOfImpact(), e.velocityAt(point,t),t);
            }).toList(), reverse.indeterminate());
        }
        if (obstacle instanceof EntityObstacle entity) {
            entity.motion().poseAt(obstacleStartTime);
            entity.motion().poseAt(Math.min(1, obstacleStartTime + intervalDuration));
            if (entity.motion().rotating() && intervalDuration > 0)
                return RigidObstacleSweep.sweep(body, movement, entity, obstacleStartTime, intervalDuration, queryContext);
            OrientedBox initial = entity.bodyAt(obstacleStartTime);
            Vector3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime + intervalDuration));
            var result = sweptObbContact(body, queryContext.body(body), movement,
                    new Vector3d(movement).sub(delta), queryContext.orientedObstacle(initial, new Vector3d()),
                    initial.center(), entity, delta, queryContext);
            return new SweepContactResult(result.initialState(), result.contacts().stream().map(contact ->
                    timedContact(contact, entity, body.move(new Vector3d(movement).mul(contact.timeOfImpact())),
                            Math.min(1, obstacleStartTime + intervalDuration * contact.timeOfImpact()))).toList(),
                    result.indeterminate());
        }
        Vector3d startOffset = new Vector3d();
        Vector3d obstacleDisplacement =
                new Vector3d();
        Vector3d relativeMovement =
                new Vector3d(movement).sub(obstacleDisplacement);
        if (!isFinite(startOffset)
                || !isFinite(obstacleDisplacement)
                || !isFinite(relativeMovement)) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }
        cc.sighs.gravityengine.math.geometry.Obb3d pureBody = queryContext.body(body);
        return switch (obstacle) {
            case BlockObstacle bo -> sweptObbContact(
                    body, pureBody, movement, relativeMovement,
                    queryContext.axisAlignedObstacle(bo.bounds(), startOffset),
                    bo.bounds().center().add(startOffset), bo, obstacleDisplacement, queryContext
            );
            case EntityObstacle ignored -> throw new IllegalStateException("rigid dispatch already handled");
            case WorldBorderObstacle wo -> sweptObbContact(
                    body, pureBody, movement, relativeMovement,
                    queryContext.axisAlignedObstacle(wo.bounds(), startOffset),
                    wo.bounds().center().add(startOffset), wo,
                    obstacleDisplacement, queryContext
            );
            case SphereObstacle so -> sweptSphereContact(
                    body, pureBody, movement, relativeMovement, so,
                    startOffset, obstacleDisplacement
            );
        };
    }

    /**
     * Probes contact at one obstacle time without advancing a moving obstacle.
     * Use this for current support/manifold refreshes; it cannot report a
     * surface that will only enter the region later in the tick.
     */
    public static Optional<CollisionContact> currentContactProbe(
            OrientedBox body,
            Vector3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContact(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    /** Explicit current-time contact probe for callers that must inspect overlap. */
    public static SweepContactResult currentContactProbeResult(
            OrientedBox body,
            Vector3d probeMovement,
            CollisionObstacle obstacle,
            double obstacleTime
    ) {
        return sweptContactResult(body, probeMovement, obstacle, obstacleTime, 0.0D);
    }

    private static SweepContactResult sweptObbContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            Vector3d worldMovement,
            Vector3d relativeMovement,
            cc.sighs.gravityengine.math.geometry.Obb3d pureObstacle,
            Vector3d obstacleStartCenter,
            CollisionObstacle obstacle,
            Vector3d obstacleDisplacement,
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
        Vector3d bodyAtToi = body.center().add(
                new Vector3d(worldMovement).mul(toi));
        Vector3d obstacleAtToi = new Vector3d(obstacleStartCenter).add(
                new Vector3d(obstacleDisplacement).mul(toi));
        Vector3d contactPoint = finiteContactPoint(bodyAtToi, obstacleAtToi);
        cc.sighs.gravityengine.math.geometry.ActiveAxes activeAxes = sweep.activeAxes();
        List<CollisionContact> contacts = new ArrayList<>(activeAxes.size());
        Vector3d normal = queryContext.resultNormal();
        for (int i = 0; i < activeAxes.size(); i++) {
            Vector3d contactNormal = activeAxes.normal(i, normal);
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
            Vector3d offset,
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
        Vector3d normal = overlap.normal(queryContext.resultNormal());
        Vector3d contactPoint = new Vector3d(body.center())
                .add(obstacleBody.center()).add(offset).mul(0.5D);
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
        return staticSphereContact(body, obstacle, new Vector3d());
    }

    /** One-shot static contact with an explicit obstacle-time offset. */
    public static Optional<CollisionContact> staticSphereContact(
            OrientedBox body,
            SphereObstacle obstacle,
            Vector3d offset
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
            Vector3d offset
    ) {
        cc.sighs.gravityengine.math.geometry.Sphere3d sphere = obstacle.sphere().moved(offset);
        SphereObbContact sc = SphereObbCollision.contact(sphere, pureBody);
        if (sc.penetration() <= CollisionTolerances.PENETRATION_EPSILON) return Optional.empty();

        Vector3d point = sc.pointOnBox();
        Vector3d normal = sc.normalFromSphereToBox();
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
            Vector3d movement,
            SphereObstacle obstacle
    ) {
        return firstContactOrFailClosed(sweptSphereContact(
                body,
                body.toObb3d(),
                movement,
                movement,
                obstacle,
                new Vector3d(),
                new Vector3d()
        ));
    }

    private static SweepContactResult sweptSphereContact(
            OrientedBox body,
            cc.sighs.gravityengine.math.geometry.Obb3d pureBody,
            Vector3d worldMovement,
            Vector3d relativeMovement,
            SphereObstacle obstacle,
            Vector3d startOffset,
            Vector3d obstacleDisplacement
    ) {
        cc.sighs.gravityengine.math.geometry.Sphere3d sphere = obstacle.sphere().moved(startOffset);
        double initialDistance =
                SphereObbCollision.signedDistance(sphere, pureBody);
        SphereObbContact initialContact =
                SphereObbCollision.contact(sphere, pureBody);
        if (initialDistance < -CollisionTolerances.PENETRATION_EPSILON) {
            Vector3d point = initialContact.pointOnBox();
            Vector3d normal = initialContact.normalFromSphereToBox();
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
            Vector3d normal = initialContact.normalFromSphereToBox();
            if (new Vector3d(
                    relativeMovement.x, relativeMovement.y, relativeMovement.z)
                    .dot(normal)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepContactResult(SweepInitialState.TOUCHING, List.of());
            }
            Vector3d point = initialContact.pointOnBox();
            Vector3d normalVec = new Vector3d(normal);
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
                new Vector3d(
                        relativeMovement.x,
                        relativeMovement.y,
                        relativeMovement.z));
        if (sweep.status() == SphereSweepResult.Status.INDETERMINATE) {
            return new SweepContactResult(initialState, List.of(), true);
        }
        Optional<SphereObbSweepHit> hit = sweep.hit();
        if (hit.isEmpty()) return new SweepContactResult(initialState, List.of());

        SphereObbSweepHit sh = hit.get();
        // The normal from SphereObbSweepHit points from sphere to body
        Vector3d contactPoint = sh.contactPoint();
        Vector3d normal = sh.normalFromSphereToBox();
        return new SweepContactResult(initialState, List.of(new CollisionContact(
                obstacle,
                new Vector3d(contactPoint).add(
                        new Vector3d(obstacleDisplacement).mul(sh.timeOfImpact())),
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
        Vector3d offset = new Vector3d();
        return switch (obstacle) {
            case BlockObstacle block -> staticCapsuleAabbContact(
                    capsule, block.bounds().move(offset), obstacle
            );
            case WorldBorderObstacle border -> staticCapsuleAabbContact(
                    capsule, border.bounds().move(offset), obstacle
            );
            case EntityObstacle ignored -> throw new IllegalStateException("rigid dispatch already handled");
            case SphereObstacle sphere -> staticCapsuleSphereContact(
                    capsule,
                    sphere.center().add(offset),
                    sphere.sphere().radius(),
                    obstacle
            );
        };
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

        Vector3d point =
                obstacleBody.localPointToWorld(
                        local.pointOnObstacle(), new Vector3d()
                );

        Vector3d normal =
                obstacleBody.localVectorToWorld(
                        local.normal(), new Vector3d()
                ).normalize();

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
            Vector3d sphereCenter,
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
            Vector3d movement,
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
            Vector3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1,obstacleStartTime+intervalDuration));
            var result = CapsuleCapsuleCollision.sweep(capsule,movement,other,delta);
            if (result.contact() == null) return new SweepContactResult(result.initialState(),List.of(),result.indeterminate());
            var g = result.contact();
            double t = Math.min(1,obstacleStartTime+intervalDuration*result.timeOfImpact());
            return new SweepContactResult(result.initialState(),List.of(new CollisionContact(entity,g.witnessB(),g.normal(),
                    result.initialState() == SweepInitialState.OVERLAPPING ? g.penetration() : 0,
                    result.timeOfImpact(),entity.velocityAt(g.witnessB(),t),t)),result.indeterminate());
        }
        if (obstacle instanceof EntityObstacle entity) {
            if (entity.motion().rotating() && intervalDuration > 0)
                return CapsuleRigidObstacleSweep.sweep(capsule, movement, entity, obstacleStartTime, intervalDuration);
            OrientedBox pose = entity.bodyAt(obstacleStartTime);
            Vector3d delta = entity.motion().centerDisplacement(obstacleStartTime, Math.min(1, obstacleStartTime + intervalDuration));
            var result = capsuleObbSweepResult(capsule, new Vector3d(movement).sub(delta), pose, entity, delta);
            return new SweepContactResult(result.initialState(), result.contacts().stream().map(c -> {
                double time = Math.min(1, obstacleStartTime + intervalDuration * c.timeOfImpact());
                return new CollisionContact(entity, c.point(), c.normal(), c.penetration(), c.timeOfImpact(),
                        entity.velocityAt(c.point(), time), time);
            }).toList(), result.indeterminate());
        }
        Vector3d startOffset = new Vector3d();
        Vector3d obstacleDisplacement =
                new Vector3d();
        Vector3d relativeMovement =
                new Vector3d(movement).sub(obstacleDisplacement);
        if (!isFinite(startOffset)
                || !isFinite(obstacleDisplacement)
                || !isFinite(relativeMovement)) {
            return new SweepContactResult(
                    SweepInitialState.SEPARATED, List.of(), true
            );
        }

        return switch (obstacle) {
            case BlockObstacle block -> capsuleAabbSweepResult(
                    capsule, relativeMovement,
                    block.bounds().move(startOffset), obstacle,
                    obstacleDisplacement
            );
            case WorldBorderObstacle border -> capsuleAabbSweepResult(
                    capsule, relativeMovement,
                    border.bounds().move(startOffset), obstacle,
                    obstacleDisplacement
            );
            case EntityObstacle ignored -> throw new IllegalStateException("rigid dispatch already handled");
            case SphereObstacle sphere -> capsuleSphereSweepResult(
                    capsule, relativeMovement,
                    sphere.center().add(startOffset),
                    sphere.sphere().radius(), obstacle,
                    obstacleDisplacement
            );
        };
    }

    private static SweepContactResult capsuleAabbSweepResult(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            cc.sighs.gravityengine.math.geometry.Aabb3d box,
            CollisionObstacle obstacle,
            Vector3d obstacleDisplacement
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
        Vector3d point = new Vector3d(geometry.pointOnObstacle()).add(
                new Vector3d(obstacleDisplacement).mul(toi)
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
            Vector3d relativeMovement,
            OrientedBox obstacleBody,
            CollisionObstacle obstacle,
            Vector3d obstacleDisplacement
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
        Vector3d localMovement =
                obstacleBody.worldVectorToLocal(
                        relativeMovement, new Vector3d()
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
        Vector3d point =
                new Vector3d(obstacleBody.localPointToWorld(
                        geometry.pointOnObstacle(), new Vector3d()
                )).add(
                        new Vector3d(obstacleDisplacement).mul(toi)
                );

        /*
         * Normals are free vectors and must never contain obstacle translation.
         */
        Vector3d normal =
                obstacleBody.localVectorToWorld(
                        geometry.normal(), new Vector3d()
                ).normalize();

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
            Vector3d relativeMovement,
            Vector3d sphereCenter,
            double sphereRadius,
            CollisionObstacle obstacle,
            Vector3d obstacleDisplacement
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
        Vector3d point = new Vector3d(geometry.pointOnSphere()).add(
                new Vector3d(obstacleDisplacement).mul(toi));
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
                        capsule.center(), new Vector3d()
                ),

                /*
                 * Capsule axis is a direction/free vector.
                 * Translation is forbidden.
                 */
                obstacleBody.worldVectorToLocal(
                        capsule.axis(), new Vector3d()
                ),

                capsule.radius(),
                capsule.halfSegmentLength()
        );
    }

    static cc.sighs.gravityengine.math.geometry.Aabb3d localBounds(OrientedBox box) {
        Vector3d extents = box.halfExtents();
        return new cc.sighs.gravityengine.math.geometry.Aabb3d(
                -extents.x, -extents.y, -extents.z,
                extents.x, extents.y, extents.z
        );
    }

    private static CollisionContact capsuleContact(
            CollisionObstacle obstacle,
            Vector3d point,
            Vector3d normal,
            double penetration,
            double timeOfImpact
    ) {
        Vector3d normalized = normal.normalize();
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
            Vector3d relativeMovement,
            CollisionObstacle obstacle,
            CapsuleAabbCollision.SweepGeometry sweep
    ) {
        GravityDebugLog.log(
                "capsule-ccd-indeterminate",
                "primitive=%s body=%s obstacle=%s relativeMovement=%s initialState=%s "
                        + "time=%.9f iterations=%d diagnostic=%s caIterations=%d "
                        + "usedFallback=%s fallbackMinSamples=%d fallbackRootSamples=%d",
                primitive,
                capsule,
                obstacle,
                relativeMovement,
                sweep.initialState(),
                sweep.timeOfImpact(),
                sweep.iterations(),
                sweep.diagnostic(),
                sweep.ccdDiagnostics().conservativeAdvanceIterations(),
                sweep.ccdDiagnostics().usedFallback(),
                sweep.ccdDiagnostics().fallbackMinimumSamples(),
                sweep.ccdDiagnostics().fallbackRootSamples()
        );
    }

    private static void logCapsuleCcdIndeterminate(
            String primitive,
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            CollisionObstacle obstacle,
            CapsuleSphereCollision.SweepGeometry sweep
    ) {
        GravityDebugLog.log(
                "capsule-ccd-indeterminate",
                "primitive=%s body=%s obstacle=%s relativeMovement=%s initialState=%s "
                        + "time=%.9f iterations=%d diagnostic=%s caIterations=%d "
                        + "usedFallback=%s fallbackMinSamples=%d fallbackRootSamples=%d",
                primitive,
                capsule,
                obstacle,
                relativeMovement,
                sweep.initialState(),
                sweep.timeOfImpact(),
                sweep.iterations(),
                sweep.diagnostic(),
                sweep.ccdDiagnostics().conservativeAdvanceIterations(),
                sweep.ccdDiagnostics().usedFallback(),
                sweep.ccdDiagnostics().fallbackMinimumSamples(),
                sweep.ccdDiagnostics().fallbackRootSamples()
        );
    }

    // ---------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------

    /** Closest point on an Aabb3d to a given world point. */
    public static Vector3d closestPointOnAABB(Vector3d point, cc.sighs.gravityengine.math.geometry.Aabb3d box) {
        return new Vector3d(
                clamp(point.x, box.minX(), box.maxX()),
                clamp(point.y, box.minY(), box.maxY()),
                clamp(point.z, box.minZ(), box.maxZ())
        );
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static Vector3d finiteContactPoint(Vector3d bodyCenter, Vector3d obstacleCenter) {
        Vector3d point = new Vector3d(bodyCenter).add(obstacleCenter).mul(0.5D);
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
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
        Vector3d point = obstacle.motion().rotating()
                ? obstacle.bodyAt(time).closestPointTo(actor.closestPointTo(contact.point())) : contact.point();
        return new CollisionContact(obstacle, point, contact.normal(), contact.penetration(),
                contact.timeOfImpact(), obstacle.velocityAt(point, time), time);
    }

    private static boolean isFinite(Vector3d vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

}
