package panamage.jxl.imageio;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.function.Consumer;

import javax.imageio.IIOParam;
import javax.imageio.ImageTypeSpecifier;

import panamage.jxl.JxlImage;

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
     */
    static ImageTypeSpecifier imageType(int channels, ColorSpace iccSpace) {
        if (iccSpace != null) {
            return ImageTypeSpecifier.createInterleaved(iccSpace, bandOffsets(channels), DataBuffer.TYPE_BYTE,
                    hasAlpha(channels), false);
        }
        return ImageTypeSpecifier.createFromBufferedImageType(srgbType(channels));
    }

    /**
     * Creates a buffered image from decoded pixels. sRGB images use the
     * standard types {@code TYPE_BYTE_GRAY}, {@code TYPE_3BYTE_BGR} and
     * {@code TYPE_4BYTE_ABGR}; images with an ICC profile share the pixel
     * array with a component color model in that color space.
     */
    static BufferedImage toBufferedImage(JxlImage image, ColorSpace iccSpace) {
        int channels = image.channels();
        if (iccSpace != null) {
            ComponentColorModel colorModel = new ComponentColorModel(iccSpace, hasAlpha(channels), false,
                    hasAlpha(channels) ? Transparency.TRANSLUCENT : Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
            DataBufferByte buffer = new DataBufferByte(image.pixels(), image.pixels().length);
            WritableRaster raster = Raster.createInterleavedRaster(buffer, image.width(), image.height(),
                    image.width() * channels, channels, bandOffsets(channels), new Point(0, 0));
            return new BufferedImage(colorModel, raster, false, null);
        }

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
     * Converts any rendered image to 8-bit sRGB samples: gray for
     * {@code TYPE_BYTE_GRAY}, otherwise RGB, or RGBA if the image has alpha.
     */
    static JxlImage toJxlImage(RenderedImage rendered) {
        BufferedImage image = asBufferedImage(rendered);
        int width = image.getWidth();
        int height = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            byte[] gray = (byte[]) image.getRaster().getDataElements(0, 0, width, height, null);
            return new JxlImage(width, height, 1, gray);
        }

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
        return new JxlImage(width, height, channels, pixels);
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
