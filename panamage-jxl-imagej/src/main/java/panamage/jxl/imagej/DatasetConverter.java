package panamage.jxl.imagej;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable8;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.img.planar.PlanarImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import panamage.jxl.JxlChannels;

/**
 * Converts JPEG XL images to ImageJ2 images ({@link ImgPlus} and
 * {@link Dataset}), with the same channels as {@link ImagePlusConverter}:
 * every channel of the JPEG XL image becomes a channel, the color channels
 * first, with red, green and blue color tables for the color channels of a
 * 16-bit or floating point color image; 8-bit red, green and blue without
 * extra channels become a Dataset merged to RGB.
 * <p>
 * The images have the axes X and Y, followed by CHANNEL, Z and TIME where
 * there is more than one channel, slice or time point. The pixel arrays are
 * shared, not copied.
 * <p>
 * The ImageJ 1.x layer of Fiji shows every such Dataset with its channels,
 * also one merged to RGB: as a composite image with red, green and blue
 * channels instead of an RGB color image.
 */
public final class DatasetConverter {

    private DatasetConverter() {
    }

    /**
     * Converts a JPEG XL image to an ImgLib2 image with ImageJ metadata.
     *
     * @param name  the name of the image
     * @param image the JPEG XL image
     * @return the image, with unsigned 8-bit, unsigned 16-bit or 32-bit
     *         floating point samples depending on the sample type
     */
    public static ImgPlus<? extends RealType<?>> toImgPlus(String name, JxlChannels image) {
        Objects.requireNonNull(image, "image");
        return toImgPlus(name, List.of(image), single(image));
    }

    /**
     * Converts a JPEG XL image to an ImageJ2 Dataset, as returned by a
     * SciJava opener.
     *
     * @param datasets the service that creates the Dataset
     * @param name     the name of the image
     * @param image    the JPEG XL image
     * @return the Dataset, merged to RGB for 8-bit red, green and blue without
     *         extra channels
     */
    public static Dataset toDataset(DatasetService datasets, String name, JxlChannels image) {
        Objects.requireNonNull(image, "image");
        return toDataset(datasets, name, List.of(image), single(image));
    }

    private static StackMetadata single(JxlChannels image) {
        boolean rgb = ChannelLayout.isRgb(image);
        return StackMetadata.of(rgb ? 1 : image.channels(), 1, 1, rgb);
    }

    /**
     * Converts the frames of a JPEG XL file to an ImageJ2 Dataset with the
     * dimensions, calibration, color tables and display ranges of the
     * metadata.
     *
     * @param datasets  the service that creates the Dataset
     * @param name      the name of the image
     * @param positions one image per slice and time point, the slices of a
     *                  time point one after the other; all with the same size,
     *                  sample type and channels
     * @param metadata  the metadata that matches the images
     * @return the Dataset
     */
    static Dataset toDataset(DatasetService datasets, String name, List<JxlChannels> positions,
            StackMetadata metadata) {
        Objects.requireNonNull(datasets, "datasets");
        Objects.requireNonNull(name, "name");
        Dataset dataset = switch (first(positions, metadata)) {
            case JxlChannels.Uint8 img -> datasets.create(uint8(name, positions, metadata));
            case JxlChannels.Uint16 img -> datasets.create(uint16(name, positions, metadata));
            case JxlChannels.Float32 img -> datasets.create(float32(name, positions, metadata));
        };
        dataset.setRGBMerged(metadata.rgb());
        return dataset;
    }

    /**
     * Converts the frames of a JPEG XL file to an ImgLib2 image with the
     * dimensions, calibration, color tables and display ranges of the
     * metadata.
     *
     * @param name      the name of the image
     * @param positions one image per slice and time point, the slices of a
     *                  time point one after the other
     * @param metadata  the metadata that matches the images
     * @return the image
     */
    static ImgPlus<? extends RealType<?>> toImgPlus(String name, List<JxlChannels> positions,
            StackMetadata metadata) {
        Objects.requireNonNull(name, "name");
        return switch (first(positions, metadata)) {
            case JxlChannels.Uint8 img -> uint8(name, positions, metadata);
            case JxlChannels.Uint16 img -> uint16(name, positions, metadata);
            case JxlChannels.Float32 img -> float32(name, positions, metadata);
        };
    }

    /** Checks that the images match each other and the metadata, and returns the first one. */
    private static JxlChannels first(List<JxlChannels> positions, StackMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (positions.isEmpty() || positions.size() != (long) metadata.slices() * metadata.frames()) {
            throw new IllegalArgumentException(positions.size() + " images for " + metadata.slices()
                    + " slices and " + metadata.frames() + " frames");
        }
        JxlChannels first = positions.get(0);
        if (metadata.rgb() ? !ChannelLayout.isRgb(first) : first.channels() != metadata.channels()) {
            throw new IllegalArgumentException("The images do not match the channels of the metadata");
        }
        for (JxlChannels image : positions) {
            if (image.width() != first.width() || image.height() != first.height()
                    || image.sampleType() != first.sampleType() || image.channels() != first.channels()) {
                throw new IllegalArgumentException("All images must have the same size, sample type and channels");
            }
        }
        return first;
    }

    private static ImgPlus<UnsignedByteType> uint8(String name, List<JxlChannels> positions,
            StackMetadata metadata) {
        List<byte[]> planes = new ArrayList<>();
        positions.forEach(image -> planes.addAll(((JxlChannels.Uint8) image).planes()));
        return toImgPlus(name, positions.get(0), metadata, PlanarImgs.unsignedBytes(dimensions(positions, metadata)),
                planes, ByteArray::new);
    }

    private static ImgPlus<UnsignedShortType> uint16(String name, List<JxlChannels> positions,
            StackMetadata metadata) {
        List<short[]> planes = new ArrayList<>();
        positions.forEach(image -> planes.addAll(((JxlChannels.Uint16) image).planes()));
        return toImgPlus(name, positions.get(0), metadata, PlanarImgs.unsignedShorts(dimensions(positions, metadata)),
                planes, ShortArray::new);
    }

    private static ImgPlus<FloatType> float32(String name, List<JxlChannels> positions, StackMetadata metadata) {
        List<float[]> planes = new ArrayList<>();
        positions.forEach(image -> planes.addAll(((JxlChannels.Float32) image).planes()));
        return toImgPlus(name, positions.get(0), metadata, PlanarImgs.floats(dimensions(positions, metadata)),
                planes, FloatArray::new);
    }

    private static <T extends NativeType<T> & RealType<T>, A extends ArrayDataAccess<A>, P> ImgPlus<T> toImgPlus(
            String name, JxlChannels first, StackMetadata metadata, PlanarImg<T, A> img, List<P> planes,
            Function<P, A> access) {
        // The planes are in the order of the dimensions: channel, then slice, then time point.
        for (int i = 0; i < planes.size(); i++) {
            img.setPlane(i, access.apply(planes.get(i)));
        }
        int channels = first.channels();
        ImgPlus<T> imgPlus = new ImgPlus<>(img, name, axes(metadata, channels));
        if (channels > 1) {
            imgPlus.setCompositeChannelCount(channels);
        }
        List<StackMetadata.ChannelDisplay> displays = metadata.channelDisplays();
        if (displays.size() == channels) {
            // The color table of a channel is the one of its first plane, the channel index.
            imgPlus.initializeColorTables(channels);
            for (int c = 0; c < channels; c++) {
                StackMetadata.ChannelDisplay display = displays.get(c);
                byte[] lut = display.lut();
                int size = lut.length / 3;
                imgPlus.setColorTable(new ColorTable8(Arrays.copyOfRange(lut, 0, size),
                        Arrays.copyOfRange(lut, size, 2 * size), Arrays.copyOfRange(lut, 2 * size, 3 * size)), c);
                imgPlus.setChannelMinimum(c, display.min());
                imgPlus.setChannelMaximum(c, display.max());
            }
        } else if (first.colorChannels() == 3 && !metadata.rgb()) {
            imgPlus.initializeColorTables(channels);
            imgPlus.setColorTable(ColorTables.RED, 0);
            imgPlus.setColorTable(ColorTables.GREEN, 1);
            imgPlus.setColorTable(ColorTables.BLUE, 2);
        }
        return imgPlus;
    }

    private static long[] dimensions(List<JxlChannels> positions, StackMetadata metadata) {
        JxlChannels first = positions.get(0);
        List<Long> dimensions = new ArrayList<>(List.of((long) first.width(), (long) first.height()));
        for (int size : new int[] {first.channels(), metadata.slices(), metadata.frames()}) {
            if (size > 1) {
                dimensions.add((long) size);
            }
        }
        return dimensions.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Returns the axes in the order of {@link #dimensions}; the origins of X,
     * Y and Z are passed on as ImageJ 1.x stores them, as Fiji itself does
     * between ImageJ 1.x and ImageJ2 images.
     */
    private static CalibratedAxis[] axes(StackMetadata metadata, int channels) {
        List<CalibratedAxis> axes = new ArrayList<>();
        axes.add(spatial(Axes.X, metadata.x()));
        axes.add(spatial(Axes.Y, metadata.y()));
        if (channels > 1) {
            axes.add(new DefaultLinearAxis(Axes.CHANNEL));
        }
        if (metadata.slices() > 1) {
            axes.add(spatial(Axes.Z, metadata.z()));
        }
        if (metadata.frames() > 1) {
            axes.add(metadata.frameInterval() > 0
                    ? new DefaultLinearAxis(Axes.TIME, metadata.timeUnit(), metadata.frameInterval())
                    : new DefaultLinearAxis(Axes.TIME));
        }
        return axes.toArray(CalibratedAxis[]::new);
    }

    private static CalibratedAxis spatial(AxisType type, StackMetadata.Axis axis) {
        return axis == null ? new DefaultLinearAxis(type)
                : new DefaultLinearAxis(type, axis.unit(), axis.scale(), axis.origin());
    }
}
