package panamage.jxl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An image with every channel in an array of its own: the color channels (gray,
 * or red, green and blue) and any number of named extra channels, such as
 * alpha, a depth map or the fluorescence channels of a microscope image.
 * <p>
 * Every plane holds the samples of one channel row by row, top to bottom,
 * {@code width * height} values. The {@code planes()} of every implementation
 * list the color channels first and then the extra channels in the order of
 * {@link #extraChannels()}. All channels have the same sample type; the color
 * channels are in sRGB unless an ICC profile is given, and an alpha channel is
 * straight (not premultiplied). Only extra channels have names in JPEG XL; the
 * color channels have none. Integer samples may use fewer bits than their
 * type, see {@link #bitsPerSample()}; the encoder stores them with their
 * values unchanged, and the decoder returns them unchanged where it can,
 * instead of scaling them to the range of the type as for a {@link JxlImage}.
 * {@snippet :
 * JxlChannels image = JxlChannels.builder(width, height)
 *         .gray(dapi)            // short[]
 *         .add("GFP", gfp)       // short[]
 *         .add("mCherry", mcherry)
 *         .build();
 * byte[] jxl = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
 * }
 * The sample type is given by the implementation, so a {@code switch} covers
 * all cases:
 * {@snippet :
 * switch (image) {
 *     case JxlChannels.Uint8 img -> process(img.planes());   // List<byte[]>
 *     case JxlChannels.Uint16 img -> process(img.planes());  // List<short[]>
 *     case JxlChannels.Float32 img -> process(img.planes()); // List<float[]>
 * }
 * }
 * Images with at most 4 channels can also be represented as a
 * {@link JxlImage} with interleaved samples.
 */
public sealed interface JxlChannels permits JxlChannels.Uint8, JxlChannels.Uint16, JxlChannels.Float32 {

    /**
     * Returns the width.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the height.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Returns the number of color channels.
     *
     * @return 1 for gray, 3 for red, green and blue
     */
    int colorChannels();

    /**
     * Returns the type and name of the extra channels, in the order of their
     * planes after the color channels.
     *
     * @return the extra channels, an unmodifiable list
     */
    List<JxlExtraChannel> extraChannels();

    /**
     * Returns the ICC profile of the color channels.
     *
     * @return the ICC profile, or {@code null} for sRGB
     */
    byte[] iccProfile();

    /**
     * Returns the type of the samples.
     *
     * @return the sample type
     */
    JxlSampleType sampleType();

    /**
     * Returns the number of significant bits of the samples of all channels.
     * Integer samples range from 0 to {@code 2^bitsPerSample - 1}; for
     * example, the data of a 12-bit camera in a {@link Uint16} image has 12
     * bits and samples up to 4095.
     *
     * @return the bits per sample, at most the size of the sample type
     */
    int bitsPerSample();

    /**
     * Returns the number of all channels.
     *
     * @return the number of color and extra channels, the size of the list of
     *         planes
     */
    default int channels() {
        return colorChannels() + extraChannels().size();
    }

    /**
     * Returns the index of the plane of the first extra channel with the
     * given name.
     *
     * @param name the name of the extra channel
     * @return the index into the list of planes, or -1 if no extra channel
     *         has this name
     */
    default int indexOf(String name) {
        Objects.requireNonNull(name, "name");
        List<JxlExtraChannel> extra = extraChannels();
        for (int i = 0; i < extra.size(); i++) {
            if (extra.get(i).name().equals(name)) {
                return colorChannels() + i;
            }
        }
        return -1;
    }

    /**
     * Returns whether the color channels are in the sRGB color space.
     *
     * @return {@code true} if there is no ICC profile
     */
    default boolean isSrgb() {
        return iccProfile() == null;
    }

    /**
     * Returns a builder for an image of the given size; the sample type
     * follows from the arrays passed to it.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @return a new builder
     */
    static Builder builder(int width, int height) {
        return new Builder(width, height);
    }

    /**
     * An image with unsigned 8-bit samples.
     *
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
     * @param planes        the color channels followed by the extra channels,
     *                      each with {@code width * height} bytes
     * @param extraChannels the type and name of the extra channels
     * @param bitsPerSample the number of significant bits, 1 to 8; the
     *                      samples range from 0 to {@code 2^bitsPerSample - 1}
     * @param iccProfile    the ICC profile of the color channels, or
     *                      {@code null} for sRGB
     */
    record Uint8(int width, int height, int colorChannels, List<byte[]> planes, List<JxlExtraChannel> extraChannels,
            int bitsPerSample, byte[] iccProfile) implements JxlChannels {

        /**
         * Validates the dimensions against the planes and copies the lists;
         * the arrays are not copied.
         *
         * @throws NullPointerException     if a list, a plane or an extra
         *                                  channel is {@code null}
         * @throws IllegalArgumentException if the dimensions or the number of
         *                                  planes do not match
         */
        public Uint8 {
            planes = List.copyOf(planes);
            extraChannels = List.copyOf(extraChannels);
            validate(width, height, colorChannels, planes.stream().mapToInt(p -> p.length).toArray(), extraChannels,
                    JxlSampleType.UINT8, bitsPerSample, iccProfile);
        }

        /**
         * Creates an image in sRGB that uses the full range of the sample type.
         *
         * @param width         the width in pixels
         * @param height        the height in pixels
         * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
         * @param planes        the color channels followed by the extra
         *                      channels, each with {@code width * height} bytes
         * @param extraChannels the type and name of the extra channels
         */
        public Uint8(int width, int height, int colorChannels, List<byte[]> planes,
                List<JxlExtraChannel> extraChannels) {
            this(width, height, colorChannels, planes, extraChannels, 8, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.UINT8;
        }
    }

    /**
     * An image with unsigned 16-bit samples from 0 to 65535. Java has no
     * unsigned short, so use {@link Short#toUnsignedInt(short)} to read them.
     *
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
     * @param planes        the color channels followed by the extra channels,
     *                      each with {@code width * height} values
     * @param extraChannels the type and name of the extra channels
     * @param bitsPerSample the number of significant bits, 1 to 16, for
     *                      example 12 for the data of a 12-bit camera; the
     *                      samples range from 0 to {@code 2^bitsPerSample - 1}
     * @param iccProfile    the ICC profile of the color channels, or
     *                      {@code null} for sRGB
     */
    record Uint16(int width, int height, int colorChannels, List<short[]> planes, List<JxlExtraChannel> extraChannels,
            int bitsPerSample, byte[] iccProfile) implements JxlChannels {

        /**
         * Validates the dimensions against the planes and copies the lists;
         * the arrays are not copied.
         *
         * @throws NullPointerException     if a list, a plane or an extra
         *                                  channel is {@code null}
         * @throws IllegalArgumentException if the dimensions or the number of
         *                                  planes do not match
         */
        public Uint16 {
            planes = List.copyOf(planes);
            extraChannels = List.copyOf(extraChannels);
            validate(width, height, colorChannels, planes.stream().mapToInt(p -> p.length).toArray(), extraChannels,
                    JxlSampleType.UINT16, bitsPerSample, iccProfile);
        }

        /**
         * Creates an image in sRGB that uses the full range of the sample type.
         *
         * @param width         the width in pixels
         * @param height        the height in pixels
         * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
         * @param planes        the color channels followed by the extra
         *                      channels, each with {@code width * height} values
         * @param extraChannels the type and name of the extra channels
         */
        public Uint16(int width, int height, int colorChannels, List<short[]> planes,
                List<JxlExtraChannel> extraChannels) {
            this(width, height, colorChannels, planes, extraChannels, 16, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.UINT16;
        }
    }

    /**
     * An image with 32-bit floating point samples. The nominal range is 0.0 to
     * 1.0; other values are kept by lossless encoding.
     *
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
     * @param planes        the color channels followed by the extra channels,
     *                      each with {@code width * height} values
     * @param extraChannels the type and name of the extra channels
     * @param bitsPerSample always 32
     * @param iccProfile    the ICC profile of the color channels, or
     *                      {@code null} for sRGB
     */
    record Float32(int width, int height, int colorChannels, List<float[]> planes, List<JxlExtraChannel> extraChannels,
            int bitsPerSample, byte[] iccProfile) implements JxlChannels {

        /**
         * Validates the dimensions against the planes and copies the lists;
         * the arrays are not copied.
         *
         * @throws NullPointerException     if a list, a plane or an extra
         *                                  channel is {@code null}
         * @throws IllegalArgumentException if the dimensions or the number of
         *                                  planes do not match
         */
        public Float32 {
            planes = List.copyOf(planes);
            extraChannels = List.copyOf(extraChannels);
            validate(width, height, colorChannels, planes.stream().mapToInt(p -> p.length).toArray(), extraChannels,
                    JxlSampleType.FLOAT32, bitsPerSample, iccProfile);
        }

        /**
         * Creates an image in sRGB that uses the full range of the sample type.
         *
         * @param width         the width in pixels
         * @param height        the height in pixels
         * @param colorChannels the number of color channels, 1 (gray) or 3 (RGB)
         * @param planes        the color channels followed by the extra
         *                      channels, each with {@code width * height} values
         * @param extraChannels the type and name of the extra channels
         */
        public Float32(int width, int height, int colorChannels, List<float[]> planes,
                List<JxlExtraChannel> extraChannels) {
            this(width, height, colorChannels, planes, extraChannels, 32, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.FLOAT32;
        }
    }

    /**
     * Collects the planes of a {@link JxlChannels} image. The first array
     * passed sets the sample type; all further arrays must have the same
     * type.
     */
    final class Builder {

        private final int width;
        private final int height;
        private final List<Object> colorPlanes = new ArrayList<>();
        private final List<Object> extraPlanes = new ArrayList<>();
        private final List<JxlExtraChannel> extraChannels = new ArrayList<>();
        private JxlSampleType sampleType;
        private int bitsPerSample;
        private byte[] iccProfile;

        private Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /**
         * Sets the gray channel.
         *
         * @param plane the samples, {@code width * height} bytes
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder gray(byte[] plane) {
            return color(JxlSampleType.UINT8, plane);
        }

        /**
         * Sets the gray channel.
         *
         * @param plane the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder gray(short[] plane) {
            return color(JxlSampleType.UINT16, plane);
        }

        /**
         * Sets the gray channel.
         *
         * @param plane the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder gray(float[] plane) {
            return color(JxlSampleType.FLOAT32, plane);
        }

        /**
         * Sets the red, green and blue channels.
         *
         * @param red   the red samples, {@code width * height} bytes
         * @param green the green samples
         * @param blue  the blue samples
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder rgb(byte[] red, byte[] green, byte[] blue) {
            return color(JxlSampleType.UINT8, red, green, blue);
        }

        /**
         * Sets the red, green and blue channels.
         *
         * @param red   the red samples, {@code width * height} values
         * @param green the green samples
         * @param blue  the blue samples
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder rgb(short[] red, short[] green, short[] blue) {
            return color(JxlSampleType.UINT16, red, green, blue);
        }

        /**
         * Sets the red, green and blue channels.
         *
         * @param red   the red samples, {@code width * height} values
         * @param green the green samples
         * @param blue  the blue samples
         * @return this builder
         * @throws IllegalStateException    if the color channels are already set
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder rgb(float[] red, float[] green, float[] blue) {
            return color(JxlSampleType.FLOAT32, red, green, blue);
        }

        /**
         * Adds an extra channel of type {@link JxlChannelType#OPTIONAL}.
         *
         * @param name  the name of the channel
         * @param plane the samples, {@code width * height} bytes
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or the sample
         *                                  type differs from earlier planes
         */
        public Builder add(String name, byte[] plane) {
            return add(JxlExtraChannel.of(name), plane);
        }

        /**
         * Adds an extra channel of type {@link JxlChannelType#OPTIONAL}.
         *
         * @param name  the name of the channel
         * @param plane the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or the sample
         *                                  type differs from earlier planes
         */
        public Builder add(String name, short[] plane) {
            return add(JxlExtraChannel.of(name), plane);
        }

        /**
         * Adds an extra channel of type {@link JxlChannelType#OPTIONAL}.
         *
         * @param name  the name of the channel
         * @param plane the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or the sample
         *                                  type differs from earlier planes
         */
        public Builder add(String name, float[] plane) {
            return add(JxlExtraChannel.of(name), plane);
        }

        /**
         * Adds an extra channel.
         *
         * @param channel the type and name of the channel
         * @param plane   the samples, {@code width * height} bytes
         * @return this builder
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder add(JxlExtraChannel channel, byte[] plane) {
            return extra(JxlSampleType.UINT8, channel, plane);
        }

        /**
         * Adds an extra channel.
         *
         * @param channel the type and name of the channel
         * @param plane   the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder add(JxlExtraChannel channel, short[] plane) {
            return extra(JxlSampleType.UINT16, channel, plane);
        }

        /**
         * Adds an extra channel.
         *
         * @param channel the type and name of the channel
         * @param plane   the samples, {@code width * height} values
         * @return this builder
         * @throws IllegalArgumentException if the sample type differs from
         *                                  earlier planes
         */
        public Builder add(JxlExtraChannel channel, float[] plane) {
            return extra(JxlSampleType.FLOAT32, channel, plane);
        }

        /**
         * Sets the number of significant bits of integer samples, for example
         * 12 for the data of a 12-bit camera in {@code short[]} arrays, with
         * samples from 0 to 4095. By default, the samples use the full range
         * of their type.
         *
         * @param bitsPerSample the bits per sample, 1 to the size of the
         *                      sample type
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Sets the ICC profile of the color channels.
         *
         * @param iccProfile the ICC profile, or {@code null} for sRGB
         * @return this builder
         */
        public Builder iccProfile(byte[] iccProfile) {
            this.iccProfile = iccProfile;
            return this;
        }

        /**
         * Creates the image. The arrays are not copied.
         *
         * @return the image
         * @throws IllegalStateException    if the color channels are not set
         * @throws IllegalArgumentException if the size of a plane, the bits
         *                                  per sample or the ICC profile is
         *                                  invalid
         */
        @SuppressWarnings("unchecked")
        public JxlChannels build() {
            if (colorPlanes.isEmpty()) {
                throw new IllegalStateException("The color channels are not set; call gray or rgb");
            }
            List<Object> planes = new ArrayList<>(colorPlanes);
            planes.addAll(extraPlanes);
            List<?> all = planes;
            int bits = bitsPerSample == 0 ? sampleType.bits() : bitsPerSample;
            return switch (sampleType) {
                case UINT8 -> new Uint8(width, height, colorPlanes.size(), (List<byte[]>) all, extraChannels, bits,
                        iccProfile);
                case UINT16 -> new Uint16(width, height, colorPlanes.size(), (List<short[]>) all, extraChannels, bits,
                        iccProfile);
                case FLOAT32 -> new Float32(width, height, colorPlanes.size(), (List<float[]>) all, extraChannels,
                        bits, iccProfile);
            };
        }

        private Builder color(JxlSampleType type, Object... planes) {
            if (!colorPlanes.isEmpty()) {
                throw new IllegalStateException("The color channels are already set");
            }
            for (Object plane : planes) {
                Objects.requireNonNull(plane, "plane");
            }
            checkType(type);
            colorPlanes.addAll(List.of(planes));
            return this;
        }

        private Builder extra(JxlSampleType type, JxlExtraChannel channel, Object plane) {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(plane, "plane");
            checkType(type);
            extraChannels.add(channel);
            extraPlanes.add(plane);
            return this;
        }

        private void checkType(JxlSampleType type) {
            if (sampleType == null) {
                sampleType = type;
            } else if (sampleType != type) {
                throw new IllegalArgumentException("All planes must have the same sample type: " + sampleType
                        + " and " + type);
            }
        }
    }

    private static void validate(int width, int height, int colorChannels, int[] planeLengths,
            List<JxlExtraChannel> extraChannels, JxlSampleType type, int bitsPerSample, byte[] iccProfile) {
        boolean integer = type != JxlSampleType.FLOAT32;
        if (integer ? bitsPerSample < 1 || bitsPerSample > type.bits() : bitsPerSample != type.bits()) {
            throw new IllegalArgumentException("bitsPerSample must be " + (integer ? "1 to " : "") + type.bits()
                    + " for " + type + " samples: " + bitsPerSample);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }
        if (colorChannels != 1 && colorChannels != 3) {
            throw new IllegalArgumentException("colorChannels must be 1 or 3: " + colorChannels);
        }
        if (planeLengths.length != colorChannels + extraChannels.size()) {
            throw new IllegalArgumentException("Expected " + (colorChannels + extraChannels.size()) + " planes ("
                    + colorChannels + " color and " + extraChannels.size() + " extra channels), got "
                    + planeLengths.length);
        }
        long samples = (long) width * height;
        for (int i = 0; i < planeLengths.length; i++) {
            if (planeLengths[i] != samples) {
                throw new IllegalArgumentException("Plane " + i + ": expected " + samples + " samples, got "
                        + planeLengths[i]);
            }
        }
        if (iccProfile != null && iccProfile.length == 0) {
            throw new IllegalArgumentException("iccProfile must not be empty; use null for sRGB");
        }
    }
}
