package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason;

/**
 * Explicit stable ids for body-attitude wire values; enum ordinals are never
 * encoded.  There is deliberately no controller-mode mapping: the core
 * constraint/profile is tick-local environment input, not wire state.
 */
final class BodyAttitudeWireValues {
    private BodyAttitudeWireValues() {}

    static int continuityId(BodyAttitudeContinuity continuity) {
        return switch (continuity) {
            case PENDING_BOOTSTRAP -> 0;
            case CONTINUOUS -> 1;
            case INVALID -> 2;
        };
    }

    static BodyAttitudeContinuity continuity(int id) {
        return switch (id) {
            case 0 -> BodyAttitudeContinuity.PENDING_BOOTSTRAP;
            case 1 -> BodyAttitudeContinuity.CONTINUOUS;
            case 2 -> BodyAttitudeContinuity.INVALID;
            default -> throw new IllegalArgumentException(
                    "unknown body-attitude continuity wire id: " + id);
        };
    }

    static int ownershipId(BodyAttitudeOwnership ownership) {
        return switch (ownership) {
            case INACTIVE -> 0;
            case ACTIVE -> 1;
        };
    }

    static BodyAttitudeOwnership ownership(int id) {
        return switch (id) {
            case 0 -> BodyAttitudeOwnership.INACTIVE;
            case 1 -> BodyAttitudeOwnership.ACTIVE;
            default -> throw new IllegalArgumentException(
                    "unknown body-attitude ownership wire id: " + id);
        };
    }

    static int suspensionReasonId(BodyAttitudeSuspensionReason reason) {
        return switch (reason) {
            case NONE -> 0;
            case NOT_EVALUATED -> 1;
            case INACTIVE -> 2;
            case NO_PHYSICS -> 3;
            case SPECTATOR -> 4;
            case PASSENGER -> 5;
            case SLEEPING -> 6;
            case DEAD_OR_DYING -> 7;
            case SWIMMING_OR_FLUID_POSE -> 8;
            case AUTO_SPIN_ATTACK -> 9;
            case CLIMBING -> 10;
            case CONTROLLED_FLIGHT -> 11;
            case UPSIDE_DOWN_PRESENTATION -> 12;
            case OTHER_VANILLA_POSE -> 13;
            case LIFECYCLE_INVALIDATED -> 14;
        };
    }

    static BodyAttitudeSuspensionReason suspensionReason(int id) {
        return switch (id) {
            case 0 -> BodyAttitudeSuspensionReason.NONE;
            case 1 -> BodyAttitudeSuspensionReason.NOT_EVALUATED;
            case 2 -> BodyAttitudeSuspensionReason.INACTIVE;
            case 3 -> BodyAttitudeSuspensionReason.NO_PHYSICS;
            case 4 -> BodyAttitudeSuspensionReason.SPECTATOR;
            case 5 -> BodyAttitudeSuspensionReason.PASSENGER;
            case 6 -> BodyAttitudeSuspensionReason.SLEEPING;
            case 7 -> BodyAttitudeSuspensionReason.DEAD_OR_DYING;
            case 8 -> BodyAttitudeSuspensionReason.SWIMMING_OR_FLUID_POSE;
            case 9 -> BodyAttitudeSuspensionReason.AUTO_SPIN_ATTACK;
            case 10 -> BodyAttitudeSuspensionReason.CLIMBING;
            case 11 -> BodyAttitudeSuspensionReason.CONTROLLED_FLIGHT;
            case 12 -> BodyAttitudeSuspensionReason.UPSIDE_DOWN_PRESENTATION;
            case 13 -> BodyAttitudeSuspensionReason.OTHER_VANILLA_POSE;
            case 14 -> BodyAttitudeSuspensionReason.LIFECYCLE_INVALIDATED;
            default -> throw new IllegalArgumentException(
                    "unknown body-attitude suspension reason wire id: " + id);
        };
    }
}
