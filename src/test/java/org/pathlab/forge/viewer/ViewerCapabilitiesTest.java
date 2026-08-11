package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class ViewerCapabilitiesTest {
    @Test
    void acceptsOnlyTheExactFactorTwoV1Profile() throws Exception {
        var exact = ViewerCapabilities.parse(document(2, true, true));
        assertTrue(exact.supportsDynamicOme());
        assertTrue(exact.accepts(1024));

        assertFalse(ViewerCapabilities.parse(document(4, true, true)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, false, true)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, true, false)).supportsDynamicOme());
    }

    @Test
    void malformedOrLooseCapabilitiesFailClosed() {
        assertThrows(IOException.class, () -> ViewerCapabilities.parse(
                "{\"ingestModes\":[\"ome-dynamic-v1\"],\"maxChunkBytes\":1}"));
    }

    private static String document(int factor, boolean nativeTiles, boolean persistedSha) {
        return "{\"ingestModes\":[\"prepared-v2\",\"ome-dynamic-v1\"],"
                + "\"omeProfiles\":[{\"id\":\"ome-dynamic-v1\",\"pixelType\":\"uint8\","
                + "\"channels\":3,\"colorSpace\":\"sRGB\",\"tileWidth\":512,"
                + "\"tileHeight\":512,\"pyramidFactor\":" + factor
                + ",\"compression\":\"jpeg\",\"tiffKinds\":[\"classic\",\"bigtiff\"],"
                + "\"nativeJpegTiles\":" + nativeTiles + ",\"persistedSha256\":"
                + persistedSha + "}],\"maxChunkBytes\":67108864,"
                + "\"recommendedChunkBytes\":67108864,\"maxUploadBytes\":5368709120}";
    }
}
