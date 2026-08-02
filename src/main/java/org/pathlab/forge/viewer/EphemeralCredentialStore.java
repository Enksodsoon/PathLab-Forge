package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.Optional;

/** Process-local credentials for isolated loopback demonstrations and tests. */
public final class EphemeralCredentialStore implements CredentialStore {
    private String value;

    @Override
    public synchronized void write(String next) throws IOException {
        value = next;
    }

    @Override
    public synchronized Optional<String> read() throws IOException {
        return Optional.ofNullable(value);
    }

    @Override
    public synchronized void delete() throws IOException {
        value = null;
    }
}
