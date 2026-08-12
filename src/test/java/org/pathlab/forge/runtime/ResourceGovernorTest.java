package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ResourceGovernorTest {
    @Test
    void distinguishesLaunchWaitAndCheckpointPauseThresholds() {
        var governor = new ResourceGovernor(RuntimeProfile.target(), () -> 0);
        var mib = 1024L * 1024;

        assertEquals(ResourceGovernor.Decision.RUN, governor.decide(1_250 * mib));
        assertEquals(ResourceGovernor.Decision.WAIT, governor.decide(1_249 * mib));
        assertEquals(ResourceGovernor.Decision.PAUSE, governor.decide(767 * mib));
    }
}
