package panamage.jxl.imageio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Access to the test images shared with the codec module.
 */
final class Resources {

    static final int GRADIENT_WIDTH = 64;
    static final int GRADIENT_HEIGHT = 48;

    static final int ANIMATION_WIDTH = 16;
    static final int ANIMATION_HEIGHT = 12;

    private Resources() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Resources.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The reference pixels of gradient.jxl as ARGB values. */
    static int[] gradientArgb() {
        return argb(bytes("gradient.rgba"));
    }

    /** The reference pixels of a frame of animation.jxl as ARGB values. */
    static int[] animationArgb(int frame) {
        int size = ANIMATION_WIDTH * ANIMATION_HEIGHT * 4;
        return argb(Arrays.copyOfRange(bytes("animation.rgba"), frame * size, (frame + 1) * size));
    }

    private static int[] argb(byte[] rgba) {
        int[] argb = new int[rgba.length / 4];
        for (int p = 0; p < argb.length; p++) {
            argb[p] = (rgba[p * 4 + 3] & 0xFF) << 24 | (rgba[p * 4] & 0xFF) << 16
                    | (rgba[p * 4 + 1] & 0xFF) << 8 | (rgba[p * 4 + 2] & 0xFF);
        }
        return argb;
    }
}
