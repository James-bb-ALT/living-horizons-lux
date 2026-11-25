package dev.lhlux.post;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpscalerJitterTest {
    @Test
    void haltonJitterProduces8DistinctSubPixelSamplesWithinHalfTexel() {
        float[] ndc = new float[2];
        float[] uv = new float[2];
        Set<String> seen = new HashSet<>();
        int lowW = 1280;
        int lowH = 720;

        for (int i = 0; i < 8; i++) {
            SpatialResolutionScaler.computeJitterOffset(i, lowW, lowH, ndc, uv);
            float pxX = uv[0] * lowW;
            float pxY = uv[1] * lowH;
            assertTrue(pxX >= -0.5f && pxX <= 0.5f);
            assertTrue(pxY >= -0.5f && pxY <= 0.5f);
            assertEquals(uv[0] * 2.0f, ndc[0], 1e-6f);
            assertEquals(uv[1] * 2.0f, ndc[1], 1e-6f);
            seen.add(String.format("%.4f,%.4f", pxX, pxY));
        }
        assertEquals(8, seen.size());
    }
}
