package org.pathlab.forge.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AnnotationTransformerTest {
    @Test
    void transformsSourceCoordinatesAfterCropAndDownsample() {
        var transformed = AnnotationTransformer.transform(
                "69790,23372;81126,34412",
                69790,
                23372,
                11336,
                11040,
                1.5);

        assertEquals("0,0;7557.333333,7360", transformed.orElseThrow());
    }

    @Test
    void excludesAnnotationsOutsideTheCrop() {
        assertTrue(AnnotationTransformer.transform(
                        "10,10;20,20",
                        69790,
                        23372,
                        11336,
                        11040,
                        1.5)
                .isEmpty());
    }
}
