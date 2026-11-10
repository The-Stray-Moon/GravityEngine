package cc.sighs.gravityengine.gravity.model;

/**
 * Which collision owner resolves one gravity-sensitive movement.
 *
 * <p>This is a GravityEngine movement/collision decision, not a NeoForge or
 * Minecraft API type. A target decides the route from platform state and then
 * hands the common operation state this vocabulary; the common runtime never
 * consults a platform policy object.</p>
 *
 * <p>Routing is movement-path ownership, not installed body identity. It can
 * change while the installed collider stays exactly the same (an external
 * provider offers exact collision for one operation, a passive solve owns one
 * operation's frame, a falling block falls back to Vanilla). A route change is
 * therefore never evidence that entity dimensions, the pose, the body
 * representation or the installed collision orientation changed.</p>
 *
 * <ul>
 *   <li>{@link #VANILLA} - Vanilla owns collision and ground/traction;</li>
 *   <li>{@link #PASSIVE_AABB} - GravityEngine resolves a passive AABB body
 *       against the frozen scene;</li>
 *   <li>{@link #EXACT_BODY} - GravityEngine resolves the exact oriented
 *       character/collision body against the frozen scene.</li>
 * </ul>
 */
public enum GravityCollisionRoute {
    VANILLA,
    PASSIVE_AABB,
    EXACT_BODY
}
