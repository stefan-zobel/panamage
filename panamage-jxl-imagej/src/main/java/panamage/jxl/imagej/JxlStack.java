package panamage.jxl.imagej;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ij.ImagePlus;
import ij.ImageStack;
import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlBox;
import panamage.jxl.JxlChannels;
import panamage.jxl.JxlChannelsFrame;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlFrameDecoder;
import panamage.jxl.JxlFrameEncoder;
import panamage.jxl.JxlLimits;
import panamage.jxl.JxlMetadata;
import panamage.jxl.JxlSampleType;

/**
 * The content of a JPEG XL file for ImageJ: one image per slice and time
 * point, the slices of a time point one after the other, and the metadata.
 * <p>
 * A hyperstack is written as an animation with one frame per slice and time
 * point (other viewers play it), with the dimensions and the rest of the
 * ImageJ metadata in a box (see {@link StackMetadata}); an image with one
 * slice and one time point as a still image. A file without such a box, or
 * with one that does not match the frames, is read with all frames as time
 * points.
 *
 * @param positions one image per slice and time point
 * @param metadata  the metadata
 */
record JxlStack(List<JxlChannels> positions, StackMetadata metadata) {

    /** Every frame is shown for 100 ms when the file is played as an animation. */
    private static final JxlAnimationHeader ANIMATION = JxlAnimationHeader.millis(0);
    private static final long FRAME_DURATION = 100;

    /** The fewest bits a 16-bit image is stored with, so that it is read back as a 16-bit image. */
    private static final int MIN_16_BIT_BITS = 9;

    JxlStack {
        positions = List.copyOf(positions);
        Objects.requireNonNull(metadata, "metadata");
        if (positions.size() != (long) metadata.slices() * metadata.frames()) {
            throw new IllegalArgumentException(positions.size() + " images for " + metadata.slices()
                    + " slices and " + metadata.frames() + " frames");
        }
    }

    /**
     * Decodes a JPEG XL file with every channel in a sample type that holds it
     * without loss.
     *
     * @param data   the file
     * @param limits the limits for the frames and the metadata
     * @return the images and the metadata
     */
    static JxlStack read(byte[] data, JxlLimits limits) {
        JxlSampleType type = ChannelLayout.losslessType(data);
        List<JxlChannels> positions = new ArrayList<>();
        List<Double> durations = new ArrayList<>();
        try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(data, type, limits)) {
            for (JxlChannelsFrame frame = frames.nextChannels(); frame != null; frame = frames.nextChannels()) {
                positions.add(frame.channels());
                durations.add(frame.info().durationMillis());
            }
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("The file has no frames");
        }
        JxlChannels first = positions.get(0);
        StackMetadata metadata = boxMetadata(JxlDecoder.readMetadata(data, limits), first, positions.size());
        if (metadata == null) {
            boolean rgb = ChannelLayout.isRgb(first);
            metadata = StackMetadata.of(rgb ? 1 : first.channels(), 1, positions.size(), rgb);
            double duration = durations.get(0);
            if (positions.size() > 1 && duration > 0 && durations.stream().allMatch(d -> d == duration)) {
                metadata = metadata.withFrameInterval(duration / 1000, "sec");
            }
        }
        return new JxlStack(positions, metadata);
    }

    /** Returns the metadata of the box, or {@code null} if there is none or it does not match the frames. */
    private static StackMetadata boxMetadata(JxlMetadata fileMetadata, JxlChannels first, int frameCount) {
        JxlBox box = fileMetadata.box(StackMetadata.BOX_TYPE);
        if (box == null) {
            return null;
        }
        StackMetadata metadata;
        try {
            metadata = StackMetadata.fromJson(box.content());
        } catch (IllegalArgumentException e) {
            return null;
        }
        boolean matches = metadata.rgb() ? ChannelLayout.isRgb(first) && metadata.channels() == 1
                : metadata.channels() == first.channels();
        if (!matches || (long) metadata.slices() * metadata.frames() != frameCount) {
            return null;
        }
        return metadata;
    }

    /**
     * Converts the images to an ImageJ image with the metadata.
     *
     * @param title the title
     * @return the ImageJ image
     */
    ImagePlus toImagePlus(String title) {
        ImagePlus imp = ImagePlusConverter.toImagePlus(title, positions, metadata.slices(), metadata.frames(),
                metadata.rgb());
        metadata.applyTo(imp);
        return imp;
    }

    /**
     * Encodes an ImageJ image with its metadata.
     * <p>
     * A 16-bit image is stored with the bit depth its largest sample needs,
     * but at least 9 bits: the samples keep their values, and lossy
     * compression measures its errors against the range of the data instead
     * of the whole 16-bit range, in which data such as 12-bit camera images
     * would be nearly black and lose much of their detail.
     *
     * @param imp     the image
     * @param out     the stream that receives the file; it is neither flushed
     *                nor closed
     * @param options the encoder settings
     * @param rgb     whether the first three channels are stored as red, green
     *                and blue (see
     *                {@link ImagePlusConverter#toChannels(ImagePlus, int, int, boolean)})
     * @throws IOException if writing fails
     */
    static void write(ImagePlus imp, OutputStream out, JxlEncodeOptions options, boolean rgb) throws IOException {
        Objects.requireNonNull(imp, "imp");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        JxlMetadata metadata = new JxlMetadata(null, null,
                List.of(JxlBox.of(StackMetadata.BOX_TYPE, StackMetadata.of(imp).toJson())));
        int slices = imp.getNSlices();
        int frames = imp.getNFrames();
        int bits = significantBits(imp);
        if (slices == 1 && frames == 1) {
            JxlEncoder.encode(ImagePlusConverter.toChannels(imp, 1, 1, rgb, bits), options, metadata, out);
            return;
        }
        try (JxlFrameEncoder encoder = JxlFrameEncoder.open(out, ANIMATION, options, metadata)) {
            for (int t = 1; t <= frames; t++) {
                for (int z = 1; z <= slices; z++) {
                    encoder.add(ImagePlusConverter.toChannels(imp, z, t, rgb, bits), FRAME_DURATION);
                }
            }
            encoder.finish();
        }
    }

    /**
     * Returns the bits that the samples of a 16-bit image need, at least
     * {@value #MIN_16_BIT_BITS}.
     *
     * @param imp the image
     * @return the bits per sample, or 0 for an image that is not 16-bit
     */
    static int significantBits(ImagePlus imp) {
        if (imp.getBitDepth() != 16) {
            return 0;
        }
        int max = 0;
        ImageStack stack = imp.getStack();
        for (int n = 1; n <= stack.getSize(); n++) {
            for (short sample : (short[]) stack.getPixels(n)) {
                max = Math.max(max, sample & 0xFFFF);
            }
        }
        return Math.max(MIN_16_BIT_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(max));
    }
}
