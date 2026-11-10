package cc.sighs.gravityengine.attitude.presentation;

import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeService;

/** Display-only interval for the roll accepted by one local tick. Never transported or persisted. */
public record LocalControllerRoll(BodyRelativeViewState endpoint, long step, long lifecycle, long stream, double radians) {
    public static LocalControllerRoll committed(BodyAttitudeComponent.Snapshot before,
            BodyAttitudeComponent.Snapshot after, BodyAttitudeInput input, BodyAttitudeConfigSnapshot config) {
        if (config == null || before.lastLocalSimulationStep() == after.lastLocalSimulationStep()
                || before.continuity() != BodyAttitudeContinuity.CONTINUOUS
                || !before.view().initialized() || !before.decision().controllerRoll()
                || after.continuity() != BodyAttitudeContinuity.CONTINUOUS
                || !after.decision().controllerRoll() || before.lifecycleEpoch() != after.lifecycleEpoch()
                || before.authoritativeStreamEpoch() != after.authoritativeStreamEpoch()) return null;
        return new LocalControllerRoll(after.view(), after.lastLocalSimulationStep(), after.lifecycleEpoch(),
                after.authoritativeStreamEpoch(), input.rollAxis() * config.controllerRollRateRadiansPerSecond()
                * BodyAttitudeService.GAME_TICK_SECONDS);
    }

    public double radiansAt(BodyAttitudeComponent.RenderableSnapshot snapshot, long actorStep) {
        return snapshot.view() == endpoint && snapshot.lastLocalSimulationStep() == step && actorStep == step
                && snapshot.lifecycleEpoch() == lifecycle && snapshot.authoritativeStreamEpoch() == stream
                ? radians : 0;
    }
}
