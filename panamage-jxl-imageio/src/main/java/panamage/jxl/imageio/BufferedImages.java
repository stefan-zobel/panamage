package panamage.jxl.imageio;

import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.function.Consumer;

import javax.imageio.IIOParam;
import javax.imageio.ImageTypeSpecifier;

import panamage.jxl.JxlImage;
import panamage.jxl.JxlSampleType;

/**
 * Conversions between {@link JxlImage} and {@link BufferedImage}.
 */
final class BufferedImages {

    private BufferedImages() {
    }

    /**
     * Returns the color space for an ICC profile, or {@code null} if the
     * pixels are sRGB or the profile cannot be used (reported as a warning).
     */
    static ColorSpace iccColorSpace(byte[] iccProfile, int channels, Consumer<String> warnings) {
        if (iccProfile == null) {
            return null;
        }
        ColorSpace space;
        try {
            space = new ICC_ColorSpace(ICC_Profile.getInstance(iccProfile));
        } catch (IllegalArgumentException e) {
            warnings.accept("Ignoring invalid ICC profile, assuming sRGB: " + e.getMessage());
            return null;
        }
        if (space.getNumComponents() != colorChannels(channels)) {
            warnings.accept("Ignoring ICC profile with " + space.getNumComponents()
                    + " components for an image with " + colorChannels(channels) + ", assuming sRGB");
            return null;
        }
        return space;
    }

    /**
     * Returns the type of the image that {@link #toBufferedImage} creates.
     * 8-bit sRGB images use the standard types {@code TYPE_BYTE_GRAY},
     * {@code TYPE_3BYTE_BGR} and {@code TYPE_4BYTE_ABGR}, 16-bit sRGB gray
     * images {@code TYPE_USHORT_GRAY}; all others are interleaved in the
     * channel order of the JPEG XL image, in the color space of the ICC
     * profile, or in sRGB (color) or the default gray color space (gray).
     */
    static ImageTypeSpecifier imageType(int channels, JxlSampleType type, ColorSpace iccSpace) {
        if (iccSpace == null && type == JxlSampleType.UINT8) {
            return ImageTypeSpecifier.createFromBufferedImageType(srgbType(channels));
        }
        if (iccSpace == null && type == JxlSampleType.UINT16 && channels == 1) {
            return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
        }
        ColorSpace space = iccSpace != null ? iccSpace
                : ColorSpace.getInstance(channels <= 2 ? ColorSpace.CS_GRAY : ColorSpace.CS_sRGB);
        return ImageTypeSpecifier.createInterleaved(space, bandOffsets(channels), dataType(type),
                hasAlpha(channels), false);
    }

    /**
     * Creates a buffered image of the type given by {@link #imageType} from
     * decoded pixels. Except for 8-bit sRGB images, the image shares the pixel
     * array.
     */
    static BufferedImage toBufferedImage(JxlImage image, ColorSpace iccSpace) {
        return switch (image) {
            case JxlImage.Uint8 img when iccSpace == null -> toSrgbBufferedImage(img);
            case JxlImage.Uint8 img -> wrap(img, iccSpace, new DataBufferByte(img.pixels(), img.pixels().length));
            case JxlImage.Uint16 img -> wrap(img, iccSpace, new DataBufferUShort(img.pixels(), img.pixels().length));
            case JxlImage.Float32 img -> wrap(img, iccSpace, new DataBufferFloat(img.pixels(), img.pixels().length));
        };
    }

    private static BufferedImage wrap(JxlImage image, ColorSpace iccSpace, DataBuffer buffer) {
        ColorModel colorModel = imageType(image.channels(), image.sampleType(), iccSpace).getColorModel();
        WritableRaster raster = Raster.createWritableRaster(
                colorModel.createCompatibleSampleModel(image.width(), image.height()), buffer, null);
        return new BufferedImage(colorModel, raster, false, null);
    }

    private static BufferedImage toSrgbBufferedImage(JxlImage.Uint8 image) {
        int channels = image.channels();
        BufferedImage result = new BufferedImage(image.width(), image.height(), srgbType(channels));
        byte[] target = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        byte[] source = image.pixels();
        int pixelCount = image.width() * image.height();
        switch (channels) {
            case 1 -> System.arraycopy(source, 0, target, 0, pixelCount);
            case 2 -> {
                for (int p = 0; p < pixelCount; p++) {
                    byte gray = source[p * 2];
                    target[p * 4] = source[p * 2 + 1];
                    target[p * 4 + 1] = gray;
                    target[p * 4 + 2] = gray;
                    target[p * 4 + 3] = gray;
                }
            }
            case 3 -> {
                for (int p = 0; p < pixelCount; p++) {
                    target[p * 3] = source[p * 3 + 2];
                    target[p * 3 + 1] = source[p * 3 + 1];
                    target[p * 3 + 2] = source[p * 3];
                }
            }
            default -> {
                for (int p = 0; p < pixelCount; p++) {
                    target[p * 4] = source[p * 4 + 3];
                    target[p * 4 + 1] = source[p * 4 + 2];
                    target[p * 4 + 2] = source[p * 4 + 1];
                    target[p * 4 + 3] = source[p * 4];
                }
            }
        }
        return result;
    }

    /**
     * Applies the source region and subsampling of a read or write parameter.
     * Returns the image itself if the parameter selects all pixels.
     */
    static BufferedImage applySourceRegion(BufferedImage image, IIOParam param) {
        if (param == null) {
            return image;
        }
        Rectangle region = new Rectangle(0, 0, image.getWidth(), image.getHeight());
        if (param.getSourceRegion() != null) {
            region = region.intersection(param.getSourceRegion());
        }
        int stepX = param.getSourceXSubsampling();
        int stepY = param.getSourceYSubsampling();
        int offsetX = param.getSubsamplingXOffset();
        int offsetY = param.getSubsamplingYOffset();
        if (region.width == image.getWidth() && region.height == image.getHeight()
                && stepX == 1 && stepY == 1 && offsetX == 0 && offsetY == 0) {
            return image;
        }
        int width = (region.width - offsetX + stepX - 1) / stepX;
        int height = (region.height - offsetY + stepY - 1) / stepY;
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("The source region and subsampling select no pixels");
        }

        ColorModel colorModel = image.getColorModel();
        WritableRaster target = colorModel.createCompatibleWritableRaster(width, height);
        Raster source = image.getRaster();
        Object pixel = null;
        for (int y = 0; y < height; y++) {
            int sourceY = region.y + offsetY + y * stepY;
            for (int x = 0; x < width; x++) {
                pixel = source.getDataElements(region.x + offsetX + x * stepX, sourceY, pixel);
                target.setDataElements(x, y, pixel);
            }
        }
        return new BufferedImage(colorModel, target, image.isAlphaPremultiplied(), null);
    }

    /**
     * Converts a rendered image to a {@link JxlImage}.
     * <p>
     * Gray and RGB images with a {@link ComponentColorModel} and 8-bit, 16-bit
     * or floating point samples keep their precision, their alpha channel
     * (premultiplied alpha is converted to straight alpha) and their color
     * space: sRGB and the default gray color space are written as sRGB, other
     * color spaces with their ICC profile. All other images are converted to
     * 8-bit sRGB: RGB, or RGBA if the image has alpha.
     */
    static JxlImage toJxlImage(RenderedImage rendered) {
        BufferedImage image = asBufferedImage(rendered);
        JxlImage exact = toExactJxlImage(image);
        return exact != null ? exact : toSrgbJxlImage(image);
    }

    /**
     * Copies the samples of a component image unchanged, or returns
     * {@code null} if the image has no suitable color model, color space or
     * sample size.
     */
    private static JxlImage toExactJxlImage(BufferedImage image) {
        if (!(image.getColorModel() instanceof ComponentColorModel colorModel)) {
            return null;
        }
        ColorSpace space = colorModel.getColorSpace();
        int colorChannels = colorModel.getNumColorComponents();
        boolean gray = space.getType() == ColorSpace.TYPE_GRAY && colorChannels == 1;
        boolean rgb = space.getType() == ColorSpace.TYPE_RGB && colorChannels == 3;
        if (!gray && !rgb) {
            return null;
        }
        byte[] iccProfile;
        if (space.isCS_sRGB() || space == ColorSpace.getInstance(ColorSpace.CS_GRAY)) {
            iccProfile = null;
        } else if (space instanceof ICC_ColorSpace iccSpace) {
            iccProfile = iccSpace.getProfile().getData();
        } else {
            return null;
        }
        JxlSampleType type = switch (colorModel.getTransferType()) {
            case DataBuffer.TYPE_BYTE -> JxlSampleType.UINT8;
            case DataBuffer.TYPE_USHORT -> JxlSampleType.UINT16;
            case DataBuffer.TYPE_FLOAT -> JxlSampleType.FLOAT32;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        for (int bits : colorModel.getComponentSize()) {
            if (bits != type.bytesPerSample() * 8) {
                return null;
            }
        }

        WritableRaster raster = image.getRaster();
        if (colorModel.isAlphaPremultiplied()) {
            WritableRaster copy = raster.createCompatibleWritableRaster();
            copy.setRect(raster);
            colorModel.coerceData(copy, false);
            raster = copy;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int channels = colorModel.getNumComponents();
        int rowLength = width * channels;
        return switch (type) {
            case UINT8 -> {
                byte[] pixels = new byte[rowLength * height];
                int[] row = new int[rowLength];
                for (int y = 0; y < height; y++) {
                    raster.getPixels(raster.getMinX(), raster.getMinY() + y, width, 1, row);
                    for (int i = 0; i < rowLength; i++) {
                        pixels[y * rowLength + i] = (byte) row[i];
                    }
                }
                yield new JxlImage.Uint8(width, height, channels, pixels, iccProfile);
            }
            case UINT16 -> {
                short[] pixels = new short[rowLength * height];
                int[] row = new int[rowLength];
                for (int y = 0; y < height; y++) {
                    raster.getPixels(raster.getMinX(), raster.getMinY() + y, width, 1, row);
                    for (int i = 0; i < rowLength; i++) {
                        pixels[y * rowLength + i] = (short) row[i];
                    }
                }
                yield new JxlImage.Uint16(width, height, channels, pixels, iccProfile);
            }
            case FLOAT32 -> {
                float[] pixels = new float[rowLength * height];
                float[] row = new float[rowLength];
                for (int y = 0; y < height; y++) {
                    raster.getPixels(raster.getMinX(), raster.getMinY() + y, width, 1, row);
                    System.arraycopy(row, 0, pixels, y * rowLength, rowLength);
                }
                yield new JxlImage.Float32(width, height, channels, pixels, iccProfile);
            }
        };
    }

    /** Converts any image to 8-bit sRGB samples: RGB, or RGBA if the image has alpha. */
    private static JxlImage toSrgbJxlImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean alpha = image.getColorModel().hasAlpha();
        int channels = alpha ? 4 : 3;
        byte[] pixels = new byte[width * height * channels];
        int[] row = new int[width];
        int i = 0;
        for (int y = 0; y < height; y++) {
            // getRGB converts to non-premultiplied sRGB for every image type.
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                pixels[i++] = (byte) (argb >>> 16);
                pixels[i++] = (byte) (argb >>> 8);
                pixels[i++] = (byte) argb;
                if (alpha) {
                    pixels[i++] = (byte) (argb >>> 24);
                }
            }
        }
        return new JxlImage.Uint8(width, height, channels, pixels);
    }

    private static BufferedImage asBufferedImage(RenderedImage rendered) {
        if (rendered instanceof BufferedImage image) {
            return image;
        }
        ColorModel colorModel = rendered.getColorModel();
        WritableRaster raster = colorModel.createCompatibleWritableRaster(rendered.getWidth(), rendered.getHeight());
        rendered.copyData(raster.createWritableTranslatedChild(rendered.getMinX(), rendered.getMinY()));
        return new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
    }

    private static int srgbType(int channels) {
        return switch (channels) {
            case 1 -> BufferedImage.TYPE_BYTE_GRAY;
            case 3 -> BufferedImage.TYPE_3BYTE_BGR;
            default -> BufferedImage.TYPE_4BYTE_ABGR;
        };
    }

    private static int dataType(JxlSampleType type) {
        return switch (type) {
            case UINT8 -> DataBuffer.TYPE_BYTE;
            case UINT16 -> DataBuffer.TYPE_USHORT;
            case FLOAT32 -> DataBuffer.TYPE_FLOAT;
        };
    }

    private static int colorChannels(int channels) {
        return channels <= 2 ? 1 : 3;
    }

    private static boolean hasAlpha(int channels) {
        return channels == 2 || channels == 4;
    }

    private static int[] bandOffsets(int channels) {
        int[] offsets = new int[channels];
        for (int i = 0; i < channels; i++) {
            offsets[i] = i;
        }
        return offsets;
    }
}
