package panamage.jxl.imageio;

import java.io.IOException;

import javax.imageio.stream.ImageInputStream;

/**
 * Recognizes JPEG XL data by its first bytes, without loading native code.
 */
final class JxlSignature {

    /** A bare codestream starts with these bytes. */
    private static final byte[] CODESTREAM = {(byte) 0xFF, 0x0A};

    /** A file in the ISO BMFF based container format starts with this box. */
    private static final byte[] CONTAINER = {
            0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A
    };

    private JxlSignature() {
    }

    /**
     * Checks the next bytes of the stream and restores its position.
     */
    static boolean matches(ImageInputStream stream) throws IOException {
        byte[] header = new byte[CONTAINER.length];
        stream.mark();
        try {
            int length = 0;
            while (length < header.length) {
                int read = stream.read(header, length, header.length - length);
                if (read < 0) {
                    break;
                }
                length += read;
            }
            return matches(header, length);
        } finally {
            stream.reset();
        }
    }

    /**
     * Checks the first {@code length} bytes of {@code header}.
     */
    static boolean matches(byte[] header, int length) {
        return startsWith(header, length, CODESTREAM) || startsWith(header, length, CONTAINER);
    }

    private static boolean startsWith(byte[] header, int length, byte[] signature) {
        if (length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
