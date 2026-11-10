package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;

import java.util.Objects;

/**
 * Immutable decision for exactly one physical {@code Entity.move}.
 *
 * <p>Locomotion policy, installed physical representation, committed gravity
 * application and external collision ownership are independent facts. Only
 * this outer-boundary value may combine them into one collision ownership
 * decision; every inner Vanilla seam consumes the frozen route that
 * {@link GravityOperationState#beginMovement(GravityCollisionRoute)} publishes
 * from it.</p>
 *
 * <p>{@link #operationRequired()} is deliberately physical:
 *
 * <pre>
 * committed application requires a GE operation
 *     OR an exact GE body is installed
 *     OR external rigid collision ownership requires GE routing
 * </pre>
 *
 * <p>{@code MovementMode.NATIVE_FALLBACK} never appears in that predicate. It
 * only means Vanilla may own locomotion/special-movement semantics; the exact
 * installed body still owns collision until the representation handoff has
 * actually committed {@code EXACT_BODY -> NATIVE_AABB}.</p>
 *
 * @param movementMode            locomotion policy for this move
 * @param installedRepresentation actually installed collider representation at
 *                                the instant this decision describes
 * @param committedApplication    committed gravity application plan
 * @param externalCollisionProviders whether GE routing is required by an
 *                                external rigid collision provider
 * @param operationRequired       whether this physical move must open a
 *                                GravityOperation and freeze a route
 * @param collisionRoute          the selected route, or {@code null} before
 *                                the outer boundary resolves it
 * @param reason                  short diagnostic reason for the decision
 */
public record MovementExecutionPlan(
        GravityOperationState.MovementMode movementMode,
        BodyRepresentation installedRepresentation,
        GravityApplicationPlan committedApplication,
        boolean externalCollisionProviders,
        boolean operationRequired,
        GravityCollisionRoute collisionRoute,
        String reason
) {
    public MovementExecutionPlan {
        Objects.requireNonNull(movementMode, "movementMode");
        Objects.requireNonNull(
                installedRepresentation,
                "installedRepresentation"
        );
        Objects.requireNonNull(
                committedApplication,
                "committedApplication"
        );
        Objects.requireNonNull(reason, "reason");

        boolean physicallyRequiresOperation = requiresOperation(
                installedRepresentation,
                committedApplication,
                externalCollisionProviders
        );

        if (operationRequired != physicallyRequiresOperation) {
            throw new IllegalArgumentException(
                    "operationRequired does not match committed physical state: "
                            + "representation="
                            + installedRepresentation
                            + ", application="
                            + committedApplication
                            + ", externalCollisionProviders="
                            + externalCollisionProviders
                            + ", expected="
                            + physicallyRequiresOperation
                            + ", actual="
                            + operationRequired
            );
        }

        if (collisionRoute != null) {
            requireCoherentExecution(
                    installedRepresentation,
                    collisionRoute
            );

            if (collisionRoute != GravityCollisionRoute.VANILLA
                    && !operationRequired) {
                throw new IllegalStateException(
                        "Engine-owned collision route requires a "
                                + "GravityOperation: representation="
                                + installedRepresentation
                                + ", route="
                                + collisionRoute
                );
            }
        }
    }
    private static boolean requiresOperation(
            BodyRepresentation installedRepresentation,
            GravityApplicationPlan committedApplication,
            boolean externalCollisionProviders
    ) {
        Objects.requireNonNull(
                installedRepresentation,
                "installedRepresentation"
        );
        Objects.requireNonNull(
                committedApplication,
                "committedApplication"
        );

        return committedApplication.needsGravityOperation()
                || installedRepresentation.isExact()
                || externalCollisionProviders;
    }

    /**
     * Pure physical-state decision. The four inputs are independent facts and
     * no input describes a side, a pending transition or a desired mode.
     */
    public static MovementExecutionPlan decide(
            GravityOperationState.MovementMode movementMode,
            BodyRepresentation installedRepresentation,
            GravityApplicationPlan committedApplication,
            boolean externalCollisionProviders
    ) {
        Objects.requireNonNull(movementMode, "movementMode");
        Objects.requireNonNull(
                installedRepresentation,
                "installedRepresentation"
        );
        Objects.requireNonNull(
                committedApplication,
                "committedApplication"
        );
        boolean operationRequired = requiresOperation(
                installedRepresentation,
                committedApplication,
                externalCollisionProviders
        );
        return new MovementExecutionPlan(
                movementMode,
                installedRepresentation,
                committedApplication,
                externalCollisionProviders,
                operationRequired,
                null,
                reason(
                        committedApplication,
                        installedRepresentation,
                        externalCollisionProviders
                )
        );
    }

    private static String reason(
            GravityApplicationPlan application,
            BodyRepresentation representation,
            boolean externalCollision
    ) {
        if (application.needsGravityOperation()) {
            return "committed-application-"
                    + application.kind();
        }
        if (representation.isExact()) {
            return "installed-exact-body";
        }
        if (externalCollision) {
            return "external-collision-ownership";
        }
        return "no-ge-ownership";
    }

    /**
     * Resolves the final execution plan for one physical move.
     *
     * <p>The receiver contributes the locomotion mode and the committed
     * application/external-ownership facts, none of which operation-time
     * geometry preparation changes. {@code executionRepresentation} is the
     * installed representation read <em>after</em> that preparation, so the
     * returned value describes exactly one physical instant:</p>
     *
     * <pre>
     * executionRepresentation
     * collisionRoute
     * operationRequired = f(executionRepresentation, committed application,
     *                       external collision ownership)
     * </pre>
     *
     * <p>It fails fast when the combination is not physically executable, so a
     * mixed pre-operation/post-preparation snapshot can never reach
     * {@code Entity.move}.</p>
     */
    public MovementExecutionPlan resolveExecution(
            BodyRepresentation executionRepresentation,
            GravityCollisionRoute route
    ) {
        Objects.requireNonNull(
                executionRepresentation,
                "executionRepresentation"
        );
        Objects.requireNonNull(route, "route");

        boolean executionRequiresOperation = requiresOperation(
                executionRepresentation,
                committedApplication,
                externalCollisionProviders
        );

        return new MovementExecutionPlan(
                movementMode,
                executionRepresentation,
                committedApplication,
                externalCollisionProviders,
                executionRequiresOperation,
                route,
                reason(
                        committedApplication,
                        executionRepresentation,
                        externalCollisionProviders
                )
        );
    }

    /**
     * Outer-movement-boundary physical consistency invariant.
     *
     * <p>An installed exact GravityEngine body is a committed physical fact, so
     * this physical move must not hand it to pure native-AABB collision
     * ownership. The inverse is deliberately not asserted: a native
     * representation may still require an engine operation through a committed
     * application or an external rigid collision provider.</p>
     */
    public static void requireCoherentExecution(
            BodyRepresentation executionRepresentation,
            GravityCollisionRoute route
    ) {
        Objects.requireNonNull(
                executionRepresentation,
                "executionRepresentation"
        );
        Objects.requireNonNull(route, "route");

        if (executionRepresentation.isExact()
                && route == GravityCollisionRoute.VANILLA) {
            throw new IllegalStateException(
                    "Installed exact body cannot execute with Vanilla "
                            + "collision ownership: representation="
                            + executionRepresentation
                            + ", route="
                            + route
            );
        }
    }

    /** True when this move's collision is owned by GravityEngine. */
    public boolean engineOwnsCollision() {
        return collisionRoute != null
                && collisionRoute != GravityCollisionRoute.VANILLA;
    }
}
