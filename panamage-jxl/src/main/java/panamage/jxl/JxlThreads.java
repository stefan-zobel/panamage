package panamage.jxl;

/**
 * How many native threads libjxl uses to decode, encode or transcode an
 * image, set with {@link JxlDecodeOptions#withThreads(JxlThreads)} and
 * {@link JxlEncodeOptions#withThreads(JxlThreads)}.
 * {@snippet :
 * JxlImage image = JxlDecoder.decode(data, 4, JxlSampleType.UINT8,
 *         JxlDecodeOptions.defaults().withThreads(JxlThreads.none()));
 * }
 * <p>
 * libjxl splits an image into groups of 256 x 256 pixels and processes them
 * in parallel, so a small image gains nothing from threads. With
 * {@link #auto()}, the default, the number of threads follows the size of the
 * image: a thumbnail is processed on the calling thread alone, a large image
 * by up to one thread per processor. The threads are native threads of
 * libjxl, which never call back into Java.
 * <p>
 * The setting applies to the methods that decode, encode or transcode pixels;
 * headers and metadata are always read on the calling thread. Further kinds of
 * settings, such as a reusable thread pool, may be added in later versions.
 */
public final class JxlThreads {

    /** The thread count of {@link #auto()}. */
    static final int AUTO = -1;

    private static final JxlThreads AUTOMATIC = new JxlThreads(AUTO);
    private static final JxlThreads NONE = new JxlThreads(0);

    /** The number of worker threads, 0 for none or {@link #AUTO}. */
    private final int threads;

    private JxlThreads(int threads) {
        this.threads = threads;
    }

    /**
     * Returns the default setting: as many threads as the size of the image
     * suggests, none for an image of at most one group (256 x 256 pixels) and
     * at most one per processor. Where the size is not known in advance, as
     * in {@link JxlTranscoder#fromJpeg(byte[])}, one thread per processor is
     * used.
     *
     * @return the automatic setting
     */
    public static JxlThreads auto() {
        return AUTOMATIC;
    }

    /**
     * Returns the setting without worker threads: libjxl runs on the calling
     * thread, for example in a server that already processes many images in
     * parallel.
     *
     * @return the setting without worker threads
     */
    public static JxlThreads none() {
        return NONE;
    }

    /**
     * Returns a setting with a fixed number of worker threads, independent of
     * the image size.
     *
     * @param threads the number of worker threads, at least 1
     * @return the setting
     * @throws IllegalArgumentException if {@code threads} is less than 1
     */
    public static JxlThreads fixed(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1: " + threads);
        }
        return new JxlThreads(threads);
    }

    /** Returns the number of worker threads, 0 for none or {@link #AUTO}. */
    int threads() {
        return threads;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlThreads other && threads == other.threads;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(threads);
    }

    @Override
    public String toString() {
        return switch (threads) {
            case AUTO -> "JxlThreads[auto]";
            case 0 -> "JxlThreads[none]";
            default -> "JxlThreads[fixed=" + threads + "]";
        };
    }
}
