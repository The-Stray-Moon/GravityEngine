package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;

/**
 * Read-only provenance of an entity's assigned gravity state.
 *
 * <p>{@link #FIELD} means the world field authority owns the assignment.
 * {@link #DIRECT} means an explicit non-field owner holds it and field
 * composition is bypassed until it is released.</p>
 *
 * <p>This first API version exposes authority as a read-only fact but does
 * not expose a supported way to install or release {@link #DIRECT}
 * authority. See {@code docs/API_BOUNDARY.md}.</p>
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