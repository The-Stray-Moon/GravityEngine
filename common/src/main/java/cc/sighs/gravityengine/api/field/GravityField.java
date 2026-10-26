package cc.sighs.gravityengine.api.field;

/**
 * Pure position/velocity/time gravity-field evaluator.
 *
 * <p>This is the supported loader-neutral field SPI. A field is a
 * mathematical rule that maps one {@link GravityFieldQuery} to one
 * acceleration vector. It never owns source identity, influence geometry,
 * revisions, or Minecraft/entity state; those live in the registration
 * layer. In particular it has no notion of a "center" unless the concrete
 * evaluator is point-like, and no field may reference Minecraft or collision
 * types.</p>
 *
 * <p>Implementations must be pure and side-effect free: the same query must
 * always produce the same acceleration regardless of how many times, by whom,
 * or in which composition group it is evaluated. Implementations are allowed
 * to return a zero acceleration; field presence is decided by the
 * registration that owns the instance, never by the magnitude of this
 * result.</p>
 *
 * <p>Implementations are evaluated on the thread that owns the
 * {@code Level} they are published to (server thread for a server level,
 * client thread for a client level). They must not touch world or entity
 * state.</p>
 */
public interface GravityField {

    /** Evaluates this field at one pure query point. */
    GravityFieldSample sample(GravityFieldQuery query);
}
