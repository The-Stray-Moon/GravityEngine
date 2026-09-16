package cc.sighs.gravityengine.gravity.collision;

import java.util.List;
import java.util.Objects;

/**
 * Immutable narrow-phase sweep result for one obstacle.
 *
 * <p>An empty contact list means no blocking intersection only when the
 * initial state is not {@link SweepInitialState#OVERLAPPING}. Meaningful
 * penetration is therefore never encoded as an empty optional.</p>
 */
public record SweepContactResult(
        SweepInitialState initialState,
        List<CollisionContact> contacts,
        boolean indeterminate
) {
    public SweepContactResult(SweepInitialState initialState, List<CollisionContact> contacts) {
        this(initialState, contacts, false);
    }

    public SweepContactResult {
        Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(contacts, "contacts");
        contacts = List.copyOf(contacts);
        if (initialState == SweepInitialState.OVERLAPPING && contacts.isEmpty()) {
            throw new IllegalArgumentException("overlapping sweep must retain a penetration contact");
        }
        if (indeterminate && !contacts.isEmpty()) {
            throw new IllegalArgumentException("indeterminate sweep may not masquerade as a hit");
        }
    }

    public boolean hasBlockingContacts() {
        return !this.contacts.isEmpty();
    }
}
