package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.math.Quatd;

/** Pure three-axis controller preview. Anatomical limits belong solely to body follow. */
public final class BodyLookResolver {
    private BodyLookResolver() {}
    public static BodyLookControlSample resolve(BodyRelativeViewState previous, BodyAttitudeInput input,
            Quatd body, BodyAttitudeConfigSnapshot config, double seconds) {
        var before = previous.semantic(body);
        return new BodyLookControlSample(BodyRelativeViewState.fromSemantic(before.previewController(input, config, seconds), body,
                previous.localLook()), before);
    }
}