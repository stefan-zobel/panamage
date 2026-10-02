package panamage.jxl;

/**
 * Limits that protect decoding against decompression bombs: small files that
 * declare huge images or expand to huge metadata or JPEG files.
 * <p>
 * A JPEG XL file of a few hundred bytes can declare an image of a billion
 * pixels, which libjxl would allocate in native memory. Native memory is not
 * bounded by the Java heap size ({@code -Xmx}), so such a file could exhaust
 * the memory of the whole process. {@link JxlDecoder} and
 * {@link JxlTranscoder} therefore reject images beyond these limits before
 * the pixels are allocated, with a {@link JxlLimitException}.
 * <p>
 * The pixel limit applies to the image and to every layer of the decoded
 * frames (layers may be larger than the image). An image with more than 4
 * channels counts once for every 4 channels started, including extra
 * channels, so a gray, RGB or RGBA image of 16384 x 16384 pixels just fits the
 * default limit. {@link JxlDecoder#decodeFrames} returns all frames of an
 * animation at once, so there the limit applies to all frames together;
 * {@link JxlFrameDecoder} holds one frame at a time and applies it to each
 * frame. The metadata limit applies to each metadata box after decompression,
 * and to all application-specific boxes ({@link JxlBox}) together. The JPEG
 * limit applies to the JPEG file that {@link JxlTranscoder#toJpeg}
 * reconstructs.
 * <p>
 * The methods without a {@code JxlLimits} parameter use {@link #defaults()},
 * which can be configured with the system properties
 * {@value #MAX_PIXELS_PROPERTY}, {@value #MAX_METADATA_BYTES_PROPERTY} and
 * {@value #MAX_JPEG_BYTES_PROPERTY}.
 * {@snippet :
 * JxlImage image = JxlDecoder.decode(data, 4, JxlSampleType.UINT8,
 *         JxlLimits.defaults().withMaxPixels(50_000_000));
 * }
 *
 * @param maxPixels        the largest number of pixels of the image or of one
 *                         of its layers, counted as described above
 * @param maxMetadataBytes the largest size of a metadata box after
 *                         decompression, and of all application-specific
 *                         boxes together, in bytes
 * @param maxJpegBytes     the largest size of a reconstructed JPEG file, in
 *                         bytes
 */
public record JxlLimits(long maxPixels, long maxMetadataBytes, long maxJpegBytes) {

    /** The default pixel limit, 2^28 pixels (256 megapixels, e.g. 16384 x 16384). */
    public static final long DEFAULT_MAX_PIXELS = 1L << 28;

    /** The default metadata limit, 16 MiB per box and for all application-specific boxes together. */
    public static final long DEFAULT_MAX_METADATA_BYTES = 16L << 20;

    /**
     * The default JPEG limit, 1 GiB, as large as an 8-bit RGBA image of
     * {@link #DEFAULT_MAX_PIXELS} pixels.
     */
    public static final long DEFAULT_MAX_JPEG_BYTES = 1L << 30;

    /** System property that overrides {@link #DEFAULT_MAX_PIXELS} in {@link #defaults()}. */
    public static final String MAX_PIXELS_PROPERTY = "panamage.jxl.max.pixels";

    /** System property that overrides {@link #DEFAULT_MAX_METADATA_BYTES} in {@link #defaults()}. */
    public static final String MAX_METADATA_BYTES_PROPERTY = "panamage.jxl.max.metadata.bytes";

    /** System property that overrides {@link #DEFAULT_MAX_JPEG_BYTES} in {@link #defaults()}. */
    public static final String MAX_JPEG_BYTES_PROPERTY = "panamage.jxl.max.jpeg.bytes";

    /** The number of channels that count as one pixel. */
    private static final int CHANNELS_PER_PIXEL = 4;

    /**
     * Validates the limits.
     *
     * @throws IllegalArgumentException if a limit is not positive
     */
    public JxlLimits {
        if (maxPixels <= 0) {
            throw new IllegalArgumentException("maxPixels must be positive: " + maxPixels);
        }
        if (maxMetadataBytes <= 0) {
            throw new IllegalArgumentException("maxMetadataBytes must be positive: " + maxMetadataBytes);
        }
        if (maxJpegBytes <= 0) {
            throw new IllegalArgumentException("maxJpegBytes must be positive: " + maxJpegBytes);
        }
    }

    /**
     * Creates limits with the given pixel and metadata limits and
     * {@link #DEFAULT_MAX_JPEG_BYTES}.
     *
     * @param maxPixels        the pixel limit
     * @param maxMetadataBytes the metadata limit in bytes
     * @throws IllegalArgumentException if a limit is not positive
     */
    public JxlLimits(long maxPixels, long maxMetadataBytes) {
        this(maxPixels, maxMetadataBytes, DEFAULT_MAX_JPEG_BYTES);
    }

    /**
     * Returns the default limits: {@link #DEFAULT_MAX_PIXELS},
     * {@link #DEFAULT_MAX_METADATA_BYTES} and {@link #DEFAULT_MAX_JPEG_BYTES},
     * unless the system properties {@value #MAX_PIXELS_PROPERTY},
     * {@value #MAX_METADATA_BYTES_PROPERTY} or {@value #MAX_JPEG_BYTES_PROPERTY}
     * set other values. The properties are read on every call.
     *
     * @return the default limits
     * @throws IllegalArgumentException if a system property is not a positive
     *                                  integer
     */
    public static JxlLimits defaults() {
        return new JxlLimits(property(MAX_PIXELS_PROPERTY, DEFAULT_MAX_PIXELS),
                property(MAX_METADATA_BYTES_PROPERTY, DEFAULT_MAX_METADATA_BYTES),
                property(MAX_JPEG_BYTES_PROPERTY, DEFAULT_MAX_JPEG_BYTES));
    }

    /**
     * Returns limits that accept every image libjxl can decode. Use them only
     * for trusted input.
     *
     * @return limits without restrictions
     */
    public static JxlLimits unlimited() {
        return new JxlLimits(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Returns a copy of these limits with a different pixel limit.
     *
     * @param maxPixels the new pixel limit
     * @return the new limits
     * @throws IllegalArgumentException if the limit is not positive
     */
    public JxlLimits withMaxPixels(long maxPixels) {
        return new JxlLimits(maxPixels, maxMetadataBytes, maxJpegBytes);
    }

    /**
     * Returns a copy of these limits with a different metadata limit.
     *
     * @param maxMetadataBytes the new metadata limit in bytes
     * @return the new limits
     * @throws IllegalArgumentException if the limit is not positive
     */
    public JxlLimits withMaxMetadataBytes(long maxMetadataBytes) {
        return new JxlLimits(maxPixels, maxMetadataBytes, maxJpegBytes);
    }

    /**
     * Returns a copy of these limits with a different JPEG limit.
     *
     * @param maxJpegBytes the new JPEG limit in bytes
     * @return the new limits
     * @throws IllegalArgumentException if the limit is not positive
     */
    public JxlLimits withMaxJpegBytes(long maxJpegBytes) {
        return new JxlLimits(maxPixels, maxMetadataBytes, maxJpegBytes);
    }

    /**
     * Throws a {@link JxlLimitException} if an area of the given size with the
     * given number of channels exceeds the pixel limit.
     *
     * @param what     what is checked, for the message ("Image", "Frame layer")
     * @param width    the width in pixels (unsigned values converted to long)
     * @param height   the height in pixels
     * @param channels the number of color and extra channels
     */
    void checkPixels(String what, long width, long height, int channels) {
        if (width <= 0 || height <= 0) {
            return;
        }
        long units = units(channels);
        // The first test also guarantees that width * height does not overflow.
        if (height > maxPixels / width || width * height > maxPixels / units) {
            throw exceeded(what + " of " + size(width, height, channels));
        }
    }

    /**
     * Throws a {@link JxlLimitException} if the given number of frames of the
     * given size together exceed the pixel limit.
     *
     * @param frames   the number of frames
     * @param width    the width of a frame in pixels
     * @param height   the height of a frame in pixels
     * @param channels the number of color and extra channels
     */
    void checkAnimation(long frames, long width, long height, int channels) {
        if (frames <= 0 || width <= 0 || height <= 0) {
            return;
        }
        long pixels = maxPixels / units(channels);
        // Each test guarantees that the product in the next one does not overflow.
        if (height > maxPixels / width || width * height > pixels || frames > pixels / (width * height)) {
            throw exceeded("Animation of " + frames + " frames of " + size(width, height, channels));
        }
    }

    /** The number of times an area counts, once for every 4 channels started. */
    private static long units(int channels) {
        return Math.max(1, (channels + CHANNELS_PER_PIXEL - 1) / CHANNELS_PER_PIXEL);
    }

    private static String size(long width, long height, int channels) {
        return width + " x " + height + " pixels" + (units(channels) > 1 ? " with " + channels + " channels" : "");
    }

    private JxlLimitException exceeded(String what) {
        return new JxlLimitException(what + " exceeds the limit of " + maxPixels + " pixels");
    }

    /**
     * Throws a {@link JxlLimitException} if a metadata box of the given size
     * exceeds the metadata limit.
     */
    void checkMetadata(String boxType, long size) {
        if (size > maxMetadataBytes) {
            throw new JxlLimitException("Metadata box '" + boxType + "' exceeds the limit of " + maxMetadataBytes
                    + " bytes");
        }
    }

    /**
     * Throws a {@link JxlLimitException} if the application-specific boxes
     * read so far exceed the metadata limit together.
     */
    void checkMetadataBoxes(long size) {
        if (size > maxMetadataBytes) {
            throw new JxlLimitException("The metadata boxes exceed the limit of " + maxMetadataBytes
                    + " bytes together");
        }
    }

    /**
     * Throws a {@link JxlLimitException} if a reconstructed JPEG file of the
     * given size exceeds the JPEG limit.
     */
    void checkJpeg(long size) {
        if (size > maxJpegBytes) {
            throw new JxlLimitException("Reconstructed JPEG exceeds the limit of " + maxJpegBytes + " bytes");
        }
    }

    private static long property(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("System property " + name + " must be a positive integer: " + value);
    }
}
