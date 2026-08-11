package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class ViewerCapabilitiesTest {
    @Test
    void acceptsOnlyTheExactFactorTwoV1Profile() throws Exception {
        var exact = ViewerCapabilities.parse(document(2, 75, true, true));
        assertTrue(exact.supportsDynamicOme());
        assertTrue(exact.accepts(1024));

        assertFalse(ViewerCapabilities.parse(document(4, 75, true, true)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, 80, true, true)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, 75, false, true)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, 75, true, false)).supportsDynamicOme());
        assertFalse(ViewerCapabilities.parse(document(2, 75, true, true)
                        .replace("\"classic\",\"bigtiff\"", "\"classic\",\"bigtiff\",\"future\""))
                .supportsDynamicOme());
    }

    @Test
    void malformedOrLooseCapabilitiesFailClosed() {
        assertThrows(IOException.class, () -> ViewerCapabilities.parse(
                "{\"ingestModes\":[\"ome-dynamic-v1\"],\"maxChunkBytes\":1}"));
    }

    @Test
    void futureOnlyProfileDoesNotEnableV1() throws Exception {
        var future = ViewerCapabilities.parse(
                document(2, 75, true, true).replace("ome-dynamic-v1", "ome-dynamic-v2"));
        assertFalse(future.supportsDynamicOme());
    }

    @Test
    void profileWithoutJpegQualityFailsClosed() {
        assertThrows(IOException.class, () -> ViewerCapabilities.parse(
                document(2, 75, true, true).replace("\"jpegQuality\":75,", "")));
    }

    @Test
    void unrelatedNestedObjectCannotSynthesizeAnAcceptedProfile() throws Exception {
        var injected = "{\"ingestModes\":[\"prepared-v2\",\"ome-dynamic-v1\"],"
                + "\"omeProfiles\":[],\"unrelated\":"
                + document(2, 75, true, true).substring(
                        document(2, 75, true, true).indexOf("{\"id\""),
                        document(2, 75, true, true).indexOf("}],\"maxChunkBytes") + 1)
                + ",\"maxChunkBytes\":67108864,\"recommendedChunkBytes\":67108864,"
                + "\"maxUploadBytes\":5368709120}";

        assertFalse(ViewerCapabilities.parse(injected).supportsDynamicOme());
    }

    private static String document(
            int factor, int jpegQuality, boolean nativeTiles, boolean persistedSha) {
        return "{\"ingestModes\":[\"prepared-v2\",\"ome-dynamic-v1\"],"
                + "\"omeProfiles\":[{\"id\":\"ome-dynamic-v1\",\"pixelType\":\"uint8\","
                + "\"channels\":3,\"colorSpace\":\"sRGB\",\"tileWidth\":512,"
                + "\"tileHeight\":512,\"pyramidFactor\":" + factor
                + ",\"compression\":\"jpeg\",\"jpegQuality\":" + jpegQuality
                + ",\"tiffKinds\":[\"classic\",\"bigtiff\"],"
                + "\"nativeJpegTiles\":" + nativeTiles + ",\"persistedSha256\":"
                + persistedSha + "}],\"maxChunkBytes\":67108864,"
                + "\"recommendedChunkBytes\":67108864,\"maxUploadBytes\":5368709120}";
    }
}
