package org.pathlab.forge.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** Persistent per-install Ed25519 identity for immutable evidence manifests. */
public final class EvidenceSigner {
    private final Path privateKey;
    private final Path publicKey;

    public EvidenceSigner(Path root) {
        var normalized = root.toAbsolutePath().normalize();
        privateKey = normalized.resolve("evidence-signing-key.pk8");
        publicKey = normalized.resolve("evidence-signing-key.pub");
    }

    public SignedValue sign(String message) throws IOException {
        try {
            var pair = loadOrCreate();
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var publicBytes = pair.getPublic().getEncoded();
            return new SignedValue(
                    sha256(publicBytes),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch (GeneralSecurityException error) {
            throw new IOException("Ed25519 evidence signing is unavailable", error);
        }
    }

    private KeyPair loadOrCreate() throws IOException, GeneralSecurityException {
        Files.createDirectories(privateKey.getParent());
        var factory = KeyFactory.getInstance("Ed25519");
        if (Files.isRegularFile(privateKey) && Files.isRegularFile(publicKey)) {
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicKey))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateKey))));
        }
        if (Files.exists(privateKey) || Files.exists(publicKey)) {
            throw new IOException("Evidence signing keypair is incomplete");
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        writePrivate(privateKey, pair.getPrivate().getEncoded());
        writePrivate(publicKey, pair.getPublic().getEncoded());
        return pair;
    }

    private static void writePrivate(Path target, byte[] value) throws IOException {
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.write(partial, value);
        try {
            Files.setPosixFilePermissions(partial,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The Windows per-user data root supplies the ACL boundary.
        }
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(byte[] value) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    public record SignedValue(String keyId, String publicKeyDer, String signature) {}
}
