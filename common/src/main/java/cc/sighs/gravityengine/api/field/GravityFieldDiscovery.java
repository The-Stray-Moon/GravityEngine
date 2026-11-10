package cc.sighs.gravityengine.api.field;

import java.util.List;

/** Conservative block wake-up domains, never evidence of query completeness.
 * Empty bounded domains mean sampling-only. Unbounded discovery explicitly opts
 * into budgeted traversal of all loaded chunks. Values are captured per tick;
 * continuous fields are revisited without requiring a publication revision. */
public record GravityFieldDiscovery(List<GravityFieldBounds> bounds, boolean unbounded) {
    public static final GravityFieldDiscovery SAMPLING_ONLY = new GravityFieldDiscovery(List.of(), false);
    public static final GravityFieldDiscovery UNBOUNDED = new GravityFieldDiscovery(List.of(), true);

    public GravityFieldDiscovery {
        bounds = List.copyOf(bounds);
    }
}
