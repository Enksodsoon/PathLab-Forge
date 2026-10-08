package org.pathlab.forge.viewer;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Generic password in the login Keychain; secrets never enter a command line. */
public final class MacCredentialStore implements CredentialStore {
    private static final int NOT_FOUND = -25300;
    private final byte[] service = WindowsCredentialStore.DEFAULT_TARGET.getBytes(StandardCharsets.UTF_8);
    private final byte[] account = "viewer".getBytes(StandardCharsets.UTF_8);

    private Security api() throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Mac"))
            throw new IOException("macOS Keychain is only available on macOS");
        try { return Native.load("/System/Library/Frameworks/Security.framework/Security", Security.class); }
        catch (RuntimeException | LinkageError error) { throw new IOException("macOS Keychain unavailable", error); }
    }

    @Override public void write(String value) throws IOException {
        var api = api();
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 256 * 1024) throw new IOException("Credential size is invalid");
        var item = new PointerByReference();
        var status = api.SecKeychainFindGenericPassword(null, service.length, service,
                account.length, account, null, null, item);
        try (var secret = new Memory(bytes.length)) {
            secret.write(0, bytes, 0, bytes.length);
            try {
                if (status == NOT_FOUND) status = api.SecKeychainAddGenericPassword(null,
                        service.length, service, account.length, account, bytes.length, secret, null);
                else if (status == 0) status = api.SecKeychainItemModifyAttributesAndData(item.getValue(),
                        null, bytes.length, secret);
                check(status, "write");
            } finally { secret.clear(); }
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (item.getValue() != null) Core.INSTANCE.CFRelease(item.getValue());
        }
    }

    @Override public Optional<String> read() throws IOException {
        var api = api();
        var length = new IntByReference();
        var data = new PointerByReference();
        var status = api.SecKeychainFindGenericPassword(null, service.length, service,
                account.length, account, length, data, null);
        if (status == NOT_FOUND) return Optional.empty();
        check(status, "read");
        try {
            if (length.getValue() < 0 || length.getValue() > 256 * 1024)
                throw new IOException("Keychain credential size is invalid");
            var bytes = data.getValue().getByteArray(0, length.getValue());
            try { return Optional.of(new String(bytes, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(bytes, (byte) 0); }
        } finally { api.SecKeychainItemFreeContent(null, data.getValue()); }
    }

    @Override public void delete() throws IOException {
        var api = api();
        var item = new PointerByReference();
        var status = api.SecKeychainFindGenericPassword(null, service.length, service,
                account.length, account, null, null, item);
        if (status == NOT_FOUND) return;
        check(status, "read");
        try { check(api.SecKeychainItemDelete(item.getValue()), "delete"); }
        finally { Core.INSTANCE.CFRelease(item.getValue()); }
    }

    private static void check(int status, String operation) throws IOException {
        if (status != 0) throw new IOException("macOS Keychain " + operation + " failed: " + status);
    }

    private interface Core extends Library {
        Core INSTANCE = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Core.class);
        void CFRelease(Pointer reference);
    }
    private interface Security extends Library {
        int SecKeychainFindGenericPassword(Pointer keychain, int serviceLength, byte[] service,
                int accountLength, byte[] account, IntByReference length, PointerByReference data,
                PointerByReference item);
        int SecKeychainAddGenericPassword(Pointer keychain, int serviceLength, byte[] service,
                int accountLength, byte[] account, int length, Pointer data, PointerByReference item);
        int SecKeychainItemModifyAttributesAndData(Pointer item, Pointer attributes, int length, Pointer data);
        int SecKeychainItemFreeContent(Pointer attributes, Pointer data);
        int SecKeychainItemDelete(Pointer item);
    }
}
