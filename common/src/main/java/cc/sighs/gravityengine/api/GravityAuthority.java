package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;

/**
 * Read-only provenance of an entity's assigned gravity state.
 *
 * <p>{@link #FIELD} means the world field authority owns the assignment.
 * {@link #DIRECT} means an explicit non-field owner holds it and field
 * composition is bypassed until it is released.</p>
 *
 * <p>Use the target GravityEngineApi to install or release DIRECT authority.
 * See {@code docs/API_BOUNDARY.md} for mutation and coverage semantics.</p>
 */
public enum GravityAuthority {
    FIELD,
    DIRECT;

    static GravityAuthority fromInternal(GravityAuthorityMode mode) {
        return switch (mode) {
            case FIELD -> FIELD;
            case DIRECT -> DIRECT;
        };
    }
}