package panamage.jxl.imagej;

/** Planes with distinct samples for the tests. */
final class TestPlanes {

    static final int WIDTH = 23;
    static final int HEIGHT = 17;
    static final int SAMPLES = WIDTH * HEIGHT;

    private TestPlanes() {
    }

    static byte[] uint8(int seed) {
        byte[] plane = new byte[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (byte) (i * 7 + seed * 31);
        }
        return plane;
    }

    static short[] uint16(int seed) {
        short[] plane = new short[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (short) (i * 263 + seed * 4099);
        }
        return plane;
    }

    static float[] float32(int seed) {
        float[] plane = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (i - 100) * 0.25f + seed * 1000.5f;
        }
        return plane;
    }
}
