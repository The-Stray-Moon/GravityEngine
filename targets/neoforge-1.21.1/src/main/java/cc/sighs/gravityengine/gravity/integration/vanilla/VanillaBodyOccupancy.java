package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.collision.CollisionNarrowPhase;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Static physical-occupancy operands for Vanilla's position-packet policy.
 *
 * <p>No move, scene/field sampling, contact publication or position write
 * occurs here. The caller retains Vanilla's thresholds, Boolean expression,
 * accept/reject decision and correction flow.</p>
 *
 * <p>Block candidates preserve their complete Vanilla VoxelShape identity.
 * Entity candidates preserve entity identity so an installed exact body can
 * remain narrow-phase authority instead of being converted back into the
 * entity's enclosing Vanilla broad-phase AABB.</p>
 */
public final class VanillaBodyOccupancy {
    // 1.21.1 isPlayerCollidingWithAnythingNew uses this float,
    // promoted to double.
    private static final double PACKET_DEFLATION = 1.0E-5F;

    // 1.21.1 EntityGetter#getEntityCollisions constants.
    private static final double ENTITY_COLLISION_MIN_SIZE = 1.0E-7D;
    private static final double ENTITY_QUERY_INFLATION = 1.0E-7D;

    private VanillaBodyOccupancy() {}

    /**
     * Invocation-local immutable old body.
     *
     * <p>null means that this packet invocation must retain the native
     * Vanilla/noPhysics path.</p>
     */
    public static CollisionBody capture(Entity entity) {
        if (entity.noPhysics
                || !GravityInfluencePolicy.usesExactBodyCollision(entity)) {
            return null;
        }
        return capturePhysicalBody(entity);
    }

    /**
     * Read geometry, not the decision whether Vanilla should perform a check.
     * A custom-to-Vanilla handoff is a representation change, not missing data.
     * noPhysics remains the surrounding native policy's responsibility.
     */
    public static CollisionBody capturePhysicalBody(Entity entity) {
        return GravityEntityGeometry.body(entity);
    }

    /** Use only for bounds captured while Vanilla owned the physical body. */
    public static CollisionBody fromVanillaBounds(AABB bounds) {
        return OrientedBox.axisAligned(
                MinecraftCollisionGeometryAdapter.toAabb3d(bounds)
        );
    }

    /**
     * Translate the resolved body by the requested change in Vanilla
     * position anchor P.
     */
    public static CollisionBody atRequestedPosition(
            CollisionBody resolved,
            Vec3 resolvedP,
            Vec3 requestedP
    ) {
        return resolved.move(
                MinecraftMathAdapter.toVec3d(
                        requestedP.subtract(resolvedP)
                )
        );
    }

    /**
     * New-occupancy predicate against one validation-owned rigid snapshot.
     */
    public static boolean hasNewCollision(
            CollisionGetter level,
            ServerPlayer entity,
            CollisionBody oldBody,
            CollisionBody requested,
            RigidOccupancySnapshot snapshot
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(oldBody, "oldBody");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(snapshot, "snapshot");

        AABB requestedEnvelope = MinecraftCollisionGeometryAdapter
                .toMinecraft(requested.enclosingAabb())
                .deflate(PACKET_DEFLATION);

        /*
         * CollisionGetter#getCollisions materializes the entity collision
         * list first, then exposes block collisions after it. Preserve that
         * semantic order while keeping entity identity.
         */
        if (introducesEntityCollision(
                oldBody,
                requested,
                entityCandidates(
                        level,
                        entity,
                        requestedEnvelope
                )
        )) {
            return true;
        }

        if (introducesDynamicRigidCollision(
                oldBody,
                requested,
                snapshot,
                true
        )) {
            return true;
        }

        return introducesCollision(
                oldBody,
                requested,
                level.getBlockCollisions(
                        entity,
                        requestedEnvelope
                )
        );
    }

    /**
     * Whole-VoxelShape old-occupancy exemption.
     *
     * <p>This deliberately preserves the shape as one Vanilla collision
     * identity. Individual pieces are only the exact block primitives used
     * inside that identity's occupancy test.</p>
     */
    public static boolean introducesCollision(
            CollisionBody oldBody,
            CollisionBody requestedBody,
            Iterable<VoxelShape> candidates
    ) {
        CollisionBody oldQuery = packetInterior(oldBody);
        CollisionBody requestedQuery =
                packetInterior(requestedBody);

        for (VoxelShape shape : candidates) {
            if (occupies(requestedQuery, shape)
                    && !occupies(oldQuery, shape)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Entity equivalent of Vanilla's whole-shape exemption.
     *
     * <p>One entity remains one collision identity. Its physical
     * representation is its installed exact body when present, otherwise
     * the ordinary Vanilla AABB.</p>
     */
    static boolean introducesEntityCollision(
            CollisionBody oldBody,
            CollisionBody requestedBody,
            Iterable<? extends Entity> candidates
    ) {
        CollisionBody oldQuery = packetInterior(oldBody);
        CollisionBody requestedQuery =
                packetInterior(requestedBody);

        for (Entity candidate : candidates) {
            if (occupies(requestedQuery, candidate)
                    && !occupies(oldQuery, candidate)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Old-occupancy predicate against one validation-owned rigid snapshot.
     */
    public static boolean oldBodyClear(
            CollisionGetter level,
            ServerPlayer entity,
            AABB oldEnvelope,
            CollisionBody oldBody,
            RigidOccupancySnapshot snapshot
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(oldEnvelope, "oldEnvelope");
        Objects.requireNonNull(oldBody, "oldBody");
        Objects.requireNonNull(snapshot, "snapshot");

        /*
         * Vanilla noCollision checks blocks first.
         */
        for (VoxelShape shape
                : level.getBlockCollisions(
                entity,
                oldEnvelope
        )) {
            if (occupies(oldBody, shape)) {
                return false;
            }
        }

        /*
         * Vanilla then asks getEntityCollisions. Reproduce its candidate
         * query while retaining entity identity for exact narrow phase.
         */
        for (Entity candidate
                : entityCandidates(
                level,
                entity,
                oldEnvelope
        )) {
            if (occupies(oldBody, candidate)) {
                return false;
            }
        }

        if (!dynamicRigidClear(oldBody, snapshot, true)) {
            return false;
        }

        /*
         * Keep the existing Vanilla world-border branch unchanged.
         * Border discovery remains conservative; exact occupancy owns the result.
         */
        var border = level.getWorldBorder();

        return !border.isInsideCloseToBorder(entity, oldEnvelope)
                || !occupies(oldBody, border.getCollisionShape());
    }

    /**
     * Exact actor body versus one complete Vanilla/block VoxelShape.
     *
     * <p>VoxelShape pieces remain legitimate exact axis-aligned obstacle
     * primitives. The enclosing player AABB is not involved in the final
     * occupancy decision. This raw capsule operand uses strict overlap: for
     * old occupancy that preserves the existing-shape exemption. New packet
     * occupancy calls it only after {@link #packetInterior} erosion, so a
     * sub-penetration-epsilon overlap cannot create a new rejection.</p>
     */
    public static boolean occupies(
            CollisionBody body,
            VoxelShape shape
    ) {
        for (AABB piece : shape.toAabbs()) {
            if (VanillaBodySensors.intersects(
                    body,
                    piece
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Exact actor body versus one entity collision identity.
     *
     * <p>If the candidate has installed custom geometry, its exact body owns
     * occupancy. Otherwise its Vanilla AABB is still its physical collision
     * representation for this policy.</p>
     */
    public static boolean occupies(
            CollisionBody body,
            Entity candidate
    ) {
        if (GravityInfluencePolicy.usesCustomBody(
                candidate
        )) {
            CollisionBody exact =
                    GravityEntityGeometry.exactBody(candidate);

            return cc.sighs.gravityengine.gravity.collision.CollisionNarrowPhase.intersects(body, exact);
        }

        return VanillaBodySensors.intersects(
                body,
                candidate.getBoundingBox()
        );
    }

    static boolean introducesDynamicRigidCollision(
            CollisionBody oldBody,
            CollisionBody requestedBody,
            RigidOccupancySnapshot snapshot
    ) {
        return introducesDynamicRigidCollision(
                oldBody,
                requestedBody,
                snapshot,
                false
        );
    }

    /**
     * Dynamic-rigid endpoint occupancy against one validation-owned snapshot.
     *
     * <p>Every executed narrow-phase call consumes one test from the snapshot's
     * operation budget. Budget exhaustion is fail closed: the caller must not
     * accept a position whose rigid geometry could not be proven clear.</p>
     *
     * @param failClosedOnBudgetExhaustion when {@code true}, returning
     *        {@code true} reports an indeterminate (treated-as-collision)
     *        result instead of a proven new collision
     */
    static boolean introducesDynamicRigidCollision(
            CollisionBody oldBody,
            CollisionBody requestedBody,
            RigidOccupancySnapshot snapshot,
            boolean failClosedOnBudgetExhaustion
    ) {
        if (snapshot.indeterminate()) {
            return failClosedOnBudgetExhaustion;
        }
        CollisionBody oldQuery =
                packetInterior(oldBody);

        CollisionBody requestedQuery =
                packetInterior(requestedBody);

        for (CollisionBody rigid : snapshot.endpointBodies()) {
            if (!snapshot.chargeNarrowPhaseTest()) {
                return failClosedOnBudgetExhaustion;
            }

            /*
             * Packet occupancy is an endpoint occupancy query, not a second
             * movement sweep. t=1 is the captured publication's terminal/current
             * pose.
             */
            boolean newOverlap =
                    CollisionNarrowPhase.intersects(
                            requestedQuery,
                            rigid
                    );

            if (!newOverlap) continue;
            if (!snapshot.chargeNarrowPhaseTest()) {
                return failClosedOnBudgetExhaustion;
            }
            boolean oldOverlap =
                    CollisionNarrowPhase.intersects(
                            oldQuery,
                            rigid
                    );

            if (newOverlap && !oldOverlap) {
                return true;
            }
        }

        return false;
    }

    static boolean dynamicRigidClear(
            CollisionBody body,
            RigidOccupancySnapshot snapshot
    ) {
        return dynamicRigidClear(body, snapshot, false);
    }

    /**
     * Old-occupancy dynamic-rigid check against one snapshot.
     *
     * @param failClosedOnBudgetExhaustion when {@code true}, budget
     *        exhaustion reports "not proven clear"
     */
    static boolean dynamicRigidClear(
            CollisionBody body,
            RigidOccupancySnapshot snapshot,
            boolean failClosedOnBudgetExhaustion
    ) {
        if (snapshot.indeterminate()) {
            return !failClosedOnBudgetExhaustion;
        }
        CollisionBody query =
                packetInterior(body);

        for (CollisionBody rigid : snapshot.endpointBodies()) {
            if (!snapshot.chargeNarrowPhaseTest()) {
                return !failClosedOnBudgetExhaustion;
            }

            if (CollisionNarrowPhase.intersects(
                    query,
                    rigid
            )) {
                return false;
            }
        }

        return true;
    }

    /**
     * Reproduce EntityGetter#getEntityCollisions candidate discovery while
     * retaining entity identity.
     *
     * <p>Vanilla 1.21.1:</p>
     *
     * <pre>
     * if (collisionBox.getSize() < 1e-7) empty
     *
     * predicate =
     *   actor == null
     *     ? CAN_BE_COLLIDED_WITH
     *     : NO_SPECTATORS.and(actor::canCollideWith)
     *
     * getEntities(
     *   actor,
     *   collisionBox.inflate(1e-7),
     *   predicate
     * )
     * </pre>
     */
    private static List<Entity> entityCandidates(
            CollisionGetter level,
            Entity entity,
            AABB collisionBox
    ) {
        if (collisionBox.getSize()
                < ENTITY_COLLISION_MIN_SIZE) {
            return List.of();
        }

        /*
         * ServerLevel/Level implement EntityGetter. Do not silently fall back
         * to getEntityCollisions here: doing so would erase identity and turn
         * a custom target's enclosing AABB back into narrow-phase authority.
         */
        if (!(level instanceof EntityGetter getter)) {
            throw new IllegalStateException(
                    "exact packet occupancy requires "
                            + "an EntityGetter collision level"
            );
        }

        Predicate<Entity> predicate =
                entity == null
                        ? EntitySelector.CAN_BE_COLLIDED_WITH
                        : EntitySelector.NO_SPECTATORS.and(
                        entity::canCollideWith
                );

        return getter.getEntities(
                entity,
                collisionBox.inflate(
                        ENTITY_QUERY_INFLATION
                ),
                predicate
        );
    }

    private static CollisionBody packetInterior(
            CollisionBody body
    ) {
        /*
         * Deflate the exact physical body in its own axes.
         * Never reconstruct it from the enclosing proxy.
         *
         * The existing SAT touching epsilon remains unchanged.
         */
        return body.deflated(PACKET_DEFLATION);
    }
}
