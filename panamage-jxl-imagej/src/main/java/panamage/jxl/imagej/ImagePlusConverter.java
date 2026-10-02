package panamage.jxl.imagej;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;
import panamage.jxl.JxlChannels;
import panamage.jxl.JxlExtraChannel;

/**
 * Converts between JPEG XL images and ImageJ 1.x images ({@link ImagePlus}).
 * <p>
 * Every channel of a JPEG XL image becomes a channel of the ImageJ image, the
 * color channels first; images with several channels are shown as a
 * {@link CompositeImage}, with red, green and blue for the color channels of
 * a 16-bit or floating point color image. 8-bit red, green and blue without
 * extra channels, or with only alpha, become an RGB color image without the
 * alpha channel, as ImageJ opens PNG images. In the other direction, the first
 * channel of an ImageJ image becomes the gray color channel of the JPEG XL
 * image and every further channel an extra channel, so that no color
 * transform touches the data, unless the first three channels are stored as
 * red, green and blue on request; an RGB color image becomes red, green and
 * blue.
 * <p>
 * The pixel arrays are shared, not copied.
 */
public final class ImagePlusConverter {

    private ImagePlusConverter() {
    }

    /**
     * Converts a JPEG XL image to an ImageJ image with one slice and one
     * frame: 8-bit, 16-bit or 32-bit depending on the sample type, and an RGB
     * color image for 8-bit red, green and blue without extra channels or with
     * only alpha, which is left out.
     *
     * @param title the title of the image
     * @param image the JPEG XL image
     * @return the ImageJ image, a {@link CompositeImage} if it has more than
     *         one channel
     */
    public static ImagePlus toImagePlus(String title, JxlChannels image) {
        Objects.requireNonNull(image, "image");
        return toImagePlus(title, List.of(image), 1, 1, ChannelLayout.isRgb(image));
    }

    /**
     * Converts the frames of a JPEG XL file to an ImageJ hyperstack.
     *
     * @param title     the title of the image
     * @param positions one image per slice and time point, the slices of a
     *                  time point one after the other; all with the same size,
     *                  sample type and channels
     * @param slices    the number of slices (Z)
     * @param frames    the number of time points (T)
     * @param rgb       whether 8-bit red, green and blue without extra
     *                  channels become an RGB color image
     * @return the ImageJ image
     * @throws IllegalArgumentException if the images do not match each other
     *                                  or the dimensions
     */
    static ImagePlus toImagePlus(String title, List<JxlChannels> positions, int slices, int frames, boolean rgb) {
        Objects.requireNonNull(title, "title");
        if (positions.isEmpty() || positions.size() != (long) slices * frames) {
            throw new IllegalArgumentException(positions.size() + " images for " + slices + " slices and " + frames
                    + " frames");
        }
        JxlChannels first = positions.get(0);
        rgb &= ChannelLayout.isRgb(first);
        int width = first.width();
        int height = first.height();
        ImageStack stack = new ImageStack(width, height);
        for (JxlChannels image : positions) {
            checkMatches(first, image);
            if (rgb) {
                List<byte[]> planes = ((JxlChannels.Uint8) image).planes();
                ColorProcessor processor = new ColorProcessor(width, height);
                processor.setRGB(planes.get(0), planes.get(1), planes.get(2));
                stack.addSlice(null, processor);
                continue;
            }
            switch (image) {
                case JxlChannels.Uint8 img -> img.planes()
                        .forEach(plane -> stack.addSlice(null, new ByteProcessor(width, height, plane)));
                case JxlChannels.Uint16 img -> img.planes()
                        .forEach(plane -> stack.addSlice(null, new ShortProcessor(width, height, plane, null)));
                case JxlChannels.Float32 img -> img.planes()
                        .forEach(plane -> stack.addSlice(null, new FloatProcessor(width, height, plane)));
            }
        }
        ImagePlus imp = new ImagePlus(title, stack);
        int channels = rgb ? 1 : first.channels();
        imp.setDimensions(channels, slices, frames);
        if (imp.getNDimensions() > 3) {
            imp.setOpenAsHyperStack(true);
        }
        if (channels == 1 || channels > CompositeImage.MAX_CHANNELS) {
            if (channels > 1) {
                imp.setOpenAsHyperStack(true);
            }
            return imp;
        }
        CompositeImage composite = new CompositeImage(imp, IJ.COMPOSITE);
        if (first.colorChannels() == 3) {
            composite.setChannelLut(LUT.createLutFromColor(Color.RED), 1);
            composite.setChannelLut(LUT.createLutFromColor(Color.GREEN), 2);
            composite.setChannelLut(LUT.createLutFromColor(Color.BLUE), 3);
        }
        // Every channel gets the range of its own samples, as in Fiji when a
        // Dataset is shown; a new composite image has the range of the first
        // channel, and a new lookup table the range 0 to 0.
        composite.resetDisplayRanges();
        return composite;
    }

    private static void checkMatches(JxlChannels first, JxlChannels image) {
        if (image.width() != first.width() || image.height() != first.height()
                || image.sampleType() != first.sampleType() || image.colorChannels() != first.colorChannels()
                || image.channels() != first.channels()) {
            throw new IllegalArgumentException("All images must have the same size, sample type and channels");
        }
    }

    /**
     * Converts the channels of an ImageJ image at one slice and frame to a
     * JPEG XL image. The first channel becomes the gray color channel and
     * every further channel an extra channel without a name; an RGB color
     * image becomes red, green and blue. 8-bit images with a lookup table are
     * stored as gray.
     *
     * @param imp   the ImageJ image
     * @param slice the slice (Z), from 1 to the number of slices
     * @param frame the frame (T), from 1 to the number of frames
     * @return the JPEG XL image
     * @throws IllegalArgumentException if the slice or frame is out of range,
     *                                  or the image is an RGB color image with
     *                                  more than one channel
     */
    public static JxlChannels toChannels(ImagePlus imp, int slice, int frame) {
        return toChannels(imp, slice, frame, false);
    }

    /**
     * Converts the channels of an ImageJ image at one slice and frame to a
     * JPEG XL image, optionally with the first three channels as red, green
     * and blue, for example for a color photograph opened as three channels.
     * Otherwise, as {@link #toChannels(ImagePlus, int, int)}.
     *
     * @param imp   the ImageJ image
     * @param slice the slice (Z), from 1 to the number of slices
     * @param frame the frame (T), from 1 to the number of frames
     * @param rgb   whether the first three channels become red, green and
     *              blue, and every further channel an extra channel; an RGB
     *              color image always becomes red, green and blue
     * @return the JPEG XL image
     * @throws IllegalArgumentException if the slice or frame is out of range,
     *                                  the image is an RGB color image with
     *                                  more than one channel, or {@code rgb}
     *                                  is requested for fewer than three
     *                                  channels
     */
    public static JxlChannels toChannels(ImagePlus imp, int slice, int frame, boolean rgb) {
        Objects.requireNonNull(imp, "imp");
        if (slice < 1 || slice > imp.getNSlices()) {
            throw new IllegalArgumentException("The slice must be from 1 to " + imp.getNSlices() + ": " + slice);
        }
        if (frame < 1 || frame > imp.getNFrames()) {
            throw new IllegalArgumentException("The frame must be from 1 to " + imp.getNFrames() + ": " + frame);
        }
        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack stack = imp.getStack();
        int channels = imp.getNChannels();
        JxlChannels.Builder builder = JxlChannels.builder(width, height);
        if (imp.getType() == ImagePlus.COLOR_RGB) {
            if (channels != 1) {
                throw new IllegalArgumentException("RGB color images with " + channels
                        + " channels are not supported");
            }
            ColorProcessor processor = (ColorProcessor) stack.getProcessor(imp.getStackIndex(1, slice, frame));
            int samples = width * height;
            byte[] red = new byte[samples];
            byte[] green = new byte[samples];
            byte[] blue = new byte[samples];
            processor.getRGB(red, green, blue);
            return builder.rgb(red, green, blue).build();
        }
        if (rgb && channels < 3) {
            throw new IllegalArgumentException("Red, green and blue need 3 channels: " + channels);
        }
        List<Object> planes = new ArrayList<>();
        for (int channel = 1; channel <= channels; channel++) {
            planes.add(stack.getPixels(imp.getStackIndex(channel, slice, frame)));
        }
        int colorChannels = rgb ? 3 : 1;
        switch (planes.get(0)) {
            case byte[] plane -> {
                if (rgb) {
                    builder.rgb(plane, (byte[]) planes.get(1), (byte[]) planes.get(2));
                } else {
                    builder.gray(plane);
                }
            }
            case short[] plane -> {
                if (rgb) {
                    builder.rgb(plane, (short[]) planes.get(1), (short[]) planes.get(2));
                } else {
                    builder.gray(plane);
                }
            }
            case float[] plane -> {
                if (rgb) {
                    builder.rgb(plane, (float[]) planes.get(1), (float[]) planes.get(2));
                } else {
                    builder.gray(plane);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported pixel type: " + planes.get(0).getClass());
        }
        JxlExtraChannel extra = JxlExtraChannel.of("");
        for (Object pixels : planes.subList(colorChannels, channels)) {
            switch (pixels) {
                case byte[] plane -> builder.add(extra, plane);
                case short[] plane -> builder.add(extra, plane);
                case float[] plane -> builder.add(extra, plane);
                default -> throw new IllegalArgumentException("Unsupported pixel type: " + pixels.getClass());
            }
        }
        return builder.build();
    }
}
