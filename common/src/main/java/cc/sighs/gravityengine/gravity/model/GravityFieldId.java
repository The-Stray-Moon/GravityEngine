package cc.sighs.gravityengine.gravity.model;

import java.util.Objects;

/**
 * Loader-neutral identity of one registered gravity field.
 *
 * <p>The identity is deliberately independent from the concrete field
 * evaluator, from collision geometry and from any world/loader dimension
 * handle. A block-backed gravity core is only one possible producer of a
 * field id; global, scripted, entity-backed or synthetic fields may use their
 * own ids.</p>
 *
 * <p>Because the engine owns exactly one field registry per world scope, the
 * world/dimension is not part of this identity. World scope is expressed by
 * {@code GravityFieldRegistry} ownership instead of by a duplicated platform
 * dimension key.</p>
 *
 * <p>The identifier is a namespaced path pair. Ordering is the deterministic
 * {@code (namespace, path)} string order, which is the engine's stable
 * tie-break for equal structural source order.</p>
 */
public record GravityFieldId(String namespace, String path)
        implements Comparable<GravityFieldId> {

    public GravityFieldId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (namespace.indexOf(':') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "namespace and path must not contain ':': "
                            + namespace + ":" + path
            );
        }
    }

    /** Canonical {@code namespace:path} textual form. */
    public String value() {
        return this.namespace + ":" + this.path;
    }

    @Override
    public String toString() {
        return value();
    }

    @Override
    public int compareTo(GravityFieldId other) {
        Objects.requireNonNull(other, "other");
        int namespaceOrder = this.namespace.compareTo(other.namespace);
        if (namespaceOrder != 0) {
            return namespaceOrder;
        }
        return this.path.compareTo(other.path);
    }
}
