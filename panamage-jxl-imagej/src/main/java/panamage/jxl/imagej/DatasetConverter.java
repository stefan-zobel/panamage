package panamage.jxl.imagej;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.display.ColorTables;
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
 * The images have the axes X and Y, and CHANNEL if there is more than one
 * channel. The pixel arrays are shared, not copied.
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
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        return switch (image) {
            case JxlChannels.Uint8 img -> toImgPlus(name, img);
            case JxlChannels.Uint16 img -> toImgPlus(name, img);
            case JxlChannels.Float32 img -> toImgPlus(name, img);
        };
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
        Objects.requireNonNull(datasets, "datasets");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Dataset dataset = switch (image) {
            case JxlChannels.Uint8 img -> datasets.create(toImgPlus(name, img));
            case JxlChannels.Uint16 img -> datasets.create(toImgPlus(name, img));
            case JxlChannels.Float32 img -> datasets.create(toImgPlus(name, img));
        };
        dataset.setRGBMerged(ChannelLayout.isRgb(image));
        return dataset;
    }

    private static ImgPlus<UnsignedByteType> toImgPlus(String name, JxlChannels.Uint8 image) {
        return toImgPlus(name, image, PlanarImgs.unsignedBytes(dimensions(image)), image.planes(), ByteArray::new);
    }

    private static ImgPlus<UnsignedShortType> toImgPlus(String name, JxlChannels.Uint16 image) {
        return toImgPlus(name, image, PlanarImgs.unsignedShorts(dimensions(image)), image.planes(), ShortArray::new);
    }

    private static ImgPlus<FloatType> toImgPlus(String name, JxlChannels.Float32 image) {
        return toImgPlus(name, image, PlanarImgs.floats(dimensions(image)), image.planes(), FloatArray::new);
    }

    private static <T extends NativeType<T> & RealType<T>, A extends ArrayDataAccess<A>, P> ImgPlus<T> toImgPlus(
            String name, JxlChannels image, PlanarImg<T, A> img, List<P> planes, Function<P, A> access) {
        for (int i = 0; i < planes.size(); i++) {
            img.setPlane(i, access.apply(planes.get(i)));
        }
        int channels = image.channels();
        if (channels == 1) {
            return new ImgPlus<>(img, name, new AxisType[] {Axes.X, Axes.Y});
        }
        ImgPlus<T> imgPlus = new ImgPlus<>(img, name, new AxisType[] {Axes.X, Axes.Y, Axes.CHANNEL});
        imgPlus.setCompositeChannelCount(channels);
        if (image.colorChannels() == 3 && !ChannelLayout.isRgb(image)) {
            imgPlus.initializeColorTables(channels);
            imgPlus.setColorTable(ColorTables.RED, 0);
            imgPlus.setColorTable(ColorTables.GREEN, 1);
            imgPlus.setColorTable(ColorTables.BLUE, 2);
        }
        return imgPlus;
    }

    private static long[] dimensions(JxlChannels image) {
        int channels = image.channels();
        return channels == 1 ? new long[] {image.width(), image.height()}
                : new long[] {image.width(), image.height(), channels};
    }
}
