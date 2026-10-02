package panamage.jxl.imagej;

import java.awt.Color;
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
 * extra channels become an RGB color image. In the other direction, the first
 * channel of an ImageJ image becomes the gray color channel of the JPEG XL
 * image and every further channel an extra channel, so that no color
 * transform touches the data; an RGB color image becomes red, green and blue.
 * <p>
 * The pixel arrays are shared, not copied.
 */
public final class ImagePlusConverter {

    private ImagePlusConverter() {
    }

    /**
     * Converts a JPEG XL image to an ImageJ image with one slice and one
     * frame: 8-bit, 16-bit or 32-bit depending on the sample type, and an RGB
     * color image for 8-bit red, green and blue without extra channels.
     *
     * @param title the title of the image
     * @param image the JPEG XL image
     * @return the ImageJ image, a {@link CompositeImage} if it has more than
     *         one channel
     */
    public static ImagePlus toImagePlus(String title, JxlChannels image) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(image, "image");
        int width = image.width();
        int height = image.height();
        if (ChannelLayout.isRgb(image)) {
            List<byte[]> planes = ((JxlChannels.Uint8) image).planes();
            ColorProcessor processor = new ColorProcessor(width, height);
            processor.setRGB(planes.get(0), planes.get(1), planes.get(2));
            return new ImagePlus(title, processor);
        }
        ImageStack stack = new ImageStack(width, height);
        switch (image) {
            case JxlChannels.Uint8 img -> img.planes()
                    .forEach(plane -> stack.addSlice(null, new ByteProcessor(width, height, plane)));
            case JxlChannels.Uint16 img -> img.planes()
                    .forEach(plane -> stack.addSlice(null, new ShortProcessor(width, height, plane, null)));
            case JxlChannels.Float32 img -> img.planes()
                    .forEach(plane -> stack.addSlice(null, new FloatProcessor(width, height, plane)));
        }
        ImagePlus imp = new ImagePlus(title, stack);
        int channels = image.channels();
        if (channels == 1) {
            return imp;
        }
        imp.setDimensions(channels, 1, 1);
        if (channels > CompositeImage.MAX_CHANNELS) {
            imp.setOpenAsHyperStack(true);
            return imp;
        }
        CompositeImage composite = new CompositeImage(imp, IJ.COMPOSITE);
        if (image.colorChannels() == 3) {
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
        for (int channel = 1; channel <= channels; channel++) {
            Object pixels = stack.getPixels(imp.getStackIndex(channel, slice, frame));
            boolean color = channel == 1;
            JxlExtraChannel extra = JxlExtraChannel.of("");
            switch (pixels) {
                case byte[] plane -> builder = color ? builder.gray(plane) : builder.add(extra, plane);
                case short[] plane -> builder = color ? builder.gray(plane) : builder.add(extra, plane);
                case float[] plane -> builder = color ? builder.gray(plane) : builder.add(extra, plane);
                default -> throw new IllegalArgumentException("Unsupported pixel type: " + pixels.getClass());
            }
        }
        return builder.build();
    }
}
