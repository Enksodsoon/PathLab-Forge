package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.Optional;

public interface CredentialStore {
    void write(String value) throws IOException;

    Optional<String> read() throws IOException;

    void delete() throws IOException;
}
