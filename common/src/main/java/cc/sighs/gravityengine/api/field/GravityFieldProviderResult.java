package cc.sighs.gravityengine.api.field;

import java.util.List;
import java.util.Objects;

/** Immutable evidence for one query in one provider domain. Partial positive
 * contributions are legal and never prove authoritative presence. */
public record GravityFieldProviderResult(FieldCoverage coverage, List<GravityContribution> contributions) {
    public GravityFieldProviderResult {
        Objects.requireNonNull(coverage, "coverage");
        contributions = List.copyOf(contributions);
    }
}
