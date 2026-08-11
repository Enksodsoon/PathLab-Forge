package org.pathlab.forge.viewer;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.win32.W32APITypeMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public final class WindowsCredentialStore implements CredentialStore {
    public static final String DEFAULT_TARGET = "PathLab Forge/Viewer desktop credential";
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private final String target;

    public WindowsCredentialStore() {
        this(System.getProperty("pathlab.forge.viewerCredentialTarget", DEFAULT_TARGET));
    }

    public WindowsCredentialStore(String target) {
        if (target == null || target.isBlank() || target.length() > 240) {
            throw new IllegalArgumentException("Windows credential target is invalid");
        }
        this.target = target;
    }

    @Override
    public void write(String value) throws IOException {
        requireWindows();
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var blob = new Memory(bytes.length);
        blob.write(0, bytes, 0, bytes.length);
        var credential = new Credential();
        credential.type = CRED_TYPE_GENERIC;
        credential.targetName = new WString(target);
        credential.credentialBlobSize = bytes.length;
        credential.credentialBlob = blob;
        credential.persist = CRED_PERSIST_LOCAL_MACHINE;
        credential.userName = new WString(System.getProperty("user.name", "PathLab Forge"));
        credential.write();
        if (!CredentialApi.INSTANCE.CredWriteW(credential, 0)) {
            throw new IOException("Windows Credential Manager write failed: " + Native.getLastError());
        }
    }

    @Override
    public Optional<String> read() throws IOException {
        requireWindows();
        var reference = new PointerByReference();
        if (!CredentialApi.INSTANCE.CredReadW(
                new WString(target), CRED_TYPE_GENERIC, 0, reference)) {
            if (Native.getLastError() == 1168) {
                return Optional.empty();
            }
            throw new IOException("Windows Credential Manager read failed: " + Native.getLastError());
        }
        var pointer = reference.getValue();
        try {
            var credential = new Credential(pointer);
            var bytes = credential.credentialBlob.getByteArray(
                    0, credential.credentialBlobSize);
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            CredentialApi.INSTANCE.CredFree(pointer);
        }
    }

    @Override
    public void delete() throws IOException {
        requireWindows();
        if (!CredentialApi.INSTANCE.CredDeleteW(
                        new WString(target), CRED_TYPE_GENERIC, 0)
                && Native.getLastError() != 1168) {
            throw new IOException("Windows Credential Manager delete failed: " + Native.getLastError());
        }
    }

    private static void requireWindows() {
        if (!System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows")) {
            throw new IllegalStateException("Windows Credential Manager is only available on Windows");
        }
    }

    private interface CredentialApi extends StdCallLibrary {
        CredentialApi INSTANCE =
                Native.load("Advapi32", CredentialApi.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CredWriteW(Credential credential, int flags);

        boolean CredReadW(
                WString targetName, int type, int flags, PointerByReference credential);

        boolean CredDeleteW(WString targetName, int type, int flags);

        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({
        "flags",
        "type",
        "targetName",
        "comment",
        "lastWrittenLow",
        "lastWrittenHigh",
        "credentialBlobSize",
        "credentialBlob",
        "persist",
        "attributeCount",
        "attributes",
        "targetAlias",
        "userName"
    })
    public static final class Credential extends Structure {
        public int flags;
        public int type;
        public WString targetName;
        public WString comment;
        public int lastWrittenLow;
        public int lastWrittenHigh;
        public int credentialBlobSize;
        public Pointer credentialBlob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public WString targetAlias;
        public WString userName;

        public Credential() {}

        public Credential(Pointer pointer) {
            super(pointer, ALIGN_DEFAULT, W32APITypeMapper.UNICODE);
            read();
        }
    }
}
