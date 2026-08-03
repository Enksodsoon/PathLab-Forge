package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiResultSignerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsIdentityAndProducesVerifiableEd25519Signatures() throws Exception {
        var message = "pathlab-ai-result/v1\njob\nbracs\nsource\nartifact\nmodel\ncode";
        var first = new AiResultSigner(temporaryDirectory).sign(message);
        var second = new AiResultSigner(temporaryDirectory).sign(message);
        assertEquals(first.keyId(), second.keyId());
        var publicBytes = Base64.getUrlDecoder().decode(first.publicKeyDer());
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicBytes)));
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(first.signature())));
        var digest = MessageDigest.getInstance("SHA-256").digest(publicBytes);
        assertEquals(java.util.HexFormat.of().formatHex(digest), first.keyId());
    }
}
