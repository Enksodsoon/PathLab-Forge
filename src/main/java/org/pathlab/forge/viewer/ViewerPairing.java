package org.pathlab.forge.viewer;

public record ViewerPairing(
        String userCode,
        String verificationUrl,
        String verificationUrlComplete,
        int pollIntervalSeconds,
        String expiresAt) {}
