package panamage.jxl.imageio;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlException;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlLimits;
import panamage.jxl.JxlSampleType;

/**
 * Reads JPEG XL images with libjxl.
 * <p>
 * Images are decoded upright according to their orientation, with the
 * precision of the image: up to 8 bits per sample as bytes, up to 16 bits as
 * unsigned shorts, floating point samples (and integers with more than 16
 * bits) as floats. 8-bit sRGB images become {@code TYPE_BYTE_GRAY},
 * {@code TYPE_3BYTE_BGR} or {@code TYPE_4BYTE_ABGR}, 16-bit sRGB gray images
 * {@code TYPE_USHORT_GRAY}; all others use a component color model with
 * interleaved samples. Images in another color space than sRGB (typically
 * lossless wide-gamut images) keep their ICC profile in the color model. Of an
 * animation, only the first frame is read.
 * <p>
 * Floating point samples are not clipped: HDR values above 1.0 and small
 * negative values from lossy encoding are kept. Java 2D does not clip them
 * either, so {@code getRGB} and drawing may show wrong colors for such
 * samples; request 8 or 16 bits with {@link ImageReadParam#setDestinationType}
 * for display.
 * <p>
 * {@link ImageReadParam} source regions and subsampling are supported, and
 * {@link ImageReadParam#setDestinationType} selects another precision from
 * {@link #getImageTypes}, for example 8 bits for a 16-bit image. Destination
 * images and band selection are ignored. Image metadata
 * ({@link JxlImageMetadata}) provides EXIF, XMP and the ICC profile.
 * <p>
 * Images and metadata boxes beyond the reader's {@link JxlLimits} (by default
 * {@link JxlLimits#defaults()}: 256 megapixels and 16 MiB per metadata box)
 * are rejected with an {@link IIOException} caused by a
 * {@link panamage.jxl.JxlLimitException}, before their memory is allocated.
 * The width and height can still be queried. The limits can be set per reader:
 * {@snippet :
 * ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
 * ((JxlImageReader) reader).setLimits(JxlLimits.defaults().withMaxPixels(50_000_000));
 * }
 */
public final class JxlImageReader extends ImageReader {

    private static final int READ_CHUNK_SIZE = 64 * 1024;

    private JxlLimits limits = JxlLimits.defaults();
    private byte[] data;
    private JxlImageInfo info;
    private JxlImageMetadata metadata;

    /**
     * Creates a reader; usually called through {@link JxlImageReaderSpi}.
     *
     * @param provider the service provider, or {@code null}
     */
    public JxlImageReader(JxlImageReaderSpi provider) {
        super(provider);
    }

    /**
     * Sets the limits for decoding images and reading metadata. They stay in
     * effect for later inputs, until {@link #reset()}.
     *
     * @param limits the new limits
     */
    public void setLimits(JxlLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Returns the limits for decoding images and reading metadata.
     *
     * @return the current limits
     */
    public JxlLimits getLimits() {
        return limits;
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        data = null;
        info = null;
        metadata = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        requireInput();
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return info().width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return info().height();
    }

    /**
     * Returns the type with the precision of the image first, followed by the
     * types with the other sample types (8-bit, 16-bit, floating point).
     */
    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        JxlImageInfo imageInfo = info();
        ColorSpace iccSpace = iccColorSpace(imageInfo.iccProfile(), imageInfo.channels());
        List<ImageTypeSpecifier> types = new ArrayList<>();
        types.add(BufferedImages.imageType(imageInfo.channels(), imageInfo.sampleType(), iccSpace));
        for (JxlSampleType type : JxlSampleType.values()) {
            if (type != imageInfo.sampleType()) {
                types.add(BufferedImages.imageType(imageInfo.channels(), type, iccSpace));
            }
        }
        return types.iterator();
    }

    /**
     * Returns the sample type selected by the data type of the parameter's
     * destination type, or the sample type of the image.
     */
    private JxlSampleType sampleType(ImageReadParam param) throws IOException {
        ImageTypeSpecifier destinationType = param == null ? null : param.getDestinationType();
        if (destinationType == null) {
            return info().sampleType();
        }
        // Only the data type matters: color spaces from ICC profiles do not compare equal.
        return switch (destinationType.getSampleModel().getDataType()) {
            case DataBuffer.TYPE_BYTE -> JxlSampleType.UINT8;
            case DataBuffer.TYPE_USHORT -> JxlSampleType.UINT16;
            case DataBuffer.TYPE_FLOAT -> JxlSampleType.FLOAT32;
            default -> throw new IIOException("Unsupported destination type; use one of getImageTypes");
        };
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    /**
     * Returns the EXIF and XMP metadata and, for images that are not sRGB,
     * the ICC profile, as read-only {@link JxlImageMetadata}.
     */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        if (metadata == null) {
            JxlImageInfo imageInfo = info();
            try {
                metadata = new JxlImageMetadata(imageInfo, JxlDecoder.readMetadata(data(), limits), true);
            } catch (JxlException e) {
                throw new IIOException("Cannot read JPEG XL metadata: " + e.getMessage(), e);
            }
        }
        return metadata;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        JxlImageInfo imageInfo = info();
        JxlSampleType type = sampleType(param);
        if (abortRequested()) {
            processReadAborted();
            return null;
        }

        JxlImage decoded;
        try {
            decoded = JxlDecoder.decode(data(), imageInfo.channels(), type, limits);
        } catch (JxlException e) {
            throw new IIOException("Cannot decode JPEG XL image: " + e.getMessage(), e);
        }
        processImageProgress(90.0f);

        ColorSpace iccSpace = iccColorSpace(decoded.iccProfile(), decoded.channels());
        BufferedImage image = BufferedImages.applySourceRegion(
                BufferedImages.toBufferedImage(decoded, iccSpace), param);
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageProgress(100.0f);
            processImageComplete();
        }
        return image;
    }

    /** Also restores the default limits. */
    @Override
    public void reset() {
        super.reset();
        limits = JxlLimits.defaults();
        data = null;
        info = null;
        metadata = null;
    }

    @Override
    public void dispose() {
        data = null;
        info = null;
        metadata = null;
    }

    private ColorSpace iccColorSpace(byte[] iccProfile, int channels) {
        return BufferedImages.iccColorSpace(iccProfile, channels, this::processWarningOccurred);
    }

    private void checkIndex(int imageIndex) {
        requireInput();
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("A JPEG XL file contains one image: " + imageIndex);
        }
    }

    private ImageInputStream requireInput() {
        if (!(getInput() instanceof ImageInputStream stream)) {
            throw new IllegalStateException("No input set");
        }
        return stream;
    }

    private JxlImageInfo info() throws IOException {
        if (info == null) {
            try {
                info = JxlDecoder.readInfo(data());
            } catch (JxlException e) {
                throw new IIOException("Cannot read JPEG XL header: " + e.getMessage(), e);
            }
        }
        return info;
    }

    /** Reads the input from its current position to the end, once. */
    private byte[] data() throws IOException {
        if (data == null) {
            ImageInputStream stream = requireInput();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[READ_CHUNK_SIZE];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            data = out.toByteArray();
        }
        return data;
    }
}
