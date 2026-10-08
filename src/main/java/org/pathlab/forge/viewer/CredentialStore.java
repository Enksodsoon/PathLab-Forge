package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.Optional;

public interface CredentialStore {
    static CredentialStore platformDefault() {
        return System.getProperty("os.name", "").startsWith("Mac")
                ? new MacCredentialStore() : new WindowsCredentialStore();
    }

    void write(String value) throws IOException;

    Optional<String> read() throws IOException;

    void delete() throws IOException;
}
