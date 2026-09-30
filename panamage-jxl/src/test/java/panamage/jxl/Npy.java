package panamage.jxl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A float32 array in the NumPy {@code .npy} format, as used for the reference
 * images of the conformance corpus.
 *
 * @param shape the dimensions, outermost first (frames, height, width, channels)
 * @param data  the values in C order
 */
record Npy(int[] shape, float[] data) {

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};
    private static final Pattern DESCR = Pattern.compile("'descr':\\s*'([^']*)'");
    private static final Pattern FORTRAN_ORDER = Pattern.compile("'fortran_order':\\s*(True|False)");
    private static final Pattern SHAPE = Pattern.compile("'shape':\\s*\\(([^)]*)\\)");

    /**
     * Reads a little-endian float32 array in C order.
     *
     * @throws IOException if the file cannot be read or has another format
     */
    static Npy read(Path path) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IOException("Not a .npy file: " + path);
        }
        int major = buffer.get();
        buffer.get(); // minor version
        int headerLength = major == 1 ? Short.toUnsignedInt(buffer.getShort()) : buffer.getInt();
        byte[] headerBytes = new byte[headerLength];
        buffer.get(headerBytes);
        String header = new String(headerBytes, StandardCharsets.ISO_8859_1);

        String descr = group(DESCR, header, path);
        if (!descr.equals("<f4")) {
            throw new IOException("Unsupported data type " + descr + " in " + path);
        }
        if (group(FORTRAN_ORDER, header, path).equals("True")) {
            throw new IOException("Fortran order is not supported: " + path);
        }
        int[] shape = Arrays.stream(group(SHAPE, header, path).split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();

        long count = Arrays.stream(shape).asLongStream().reduce(1L, Math::multiplyExact);
        if (count != buffer.remaining() / 4L) {
            throw new IOException("Expected " + count + " values, found " + buffer.remaining() / 4 + " in " + path);
        }
        float[] data = new float[(int) count];
        buffer.asFloatBuffer().get(data);
        return new Npy(shape, data);
    }

    private static String group(Pattern pattern, String header, Path path) throws IOException {
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) {
            throw new IOException("Malformed header " + header.strip() + " in " + path);
        }
        return matcher.group(1);
    }
}
