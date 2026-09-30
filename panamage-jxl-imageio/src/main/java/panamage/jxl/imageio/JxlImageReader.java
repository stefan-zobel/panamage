package panamage.jxl.imageio;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

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

/**
 * Reads JPEG XL images with libjxl.
 * <p>
 * Images are decoded with 8 bits per sample, upright according to their
 * orientation. sRGB images become {@code TYPE_BYTE_GRAY},
 * {@code TYPE_3BYTE_BGR} or {@code TYPE_4BYTE_ABGR}; images in another color
 * space (typically lossless wide-gamut images) keep their ICC profile in the
 * color model. Of an animation, only the first frame is read.
 * <p>
 * {@link ImageReadParam} source regions and subsampling are supported;
 * destination settings and band selection are ignored. Image metadata
 * ({@link JxlImageMetadata}) provides EXIF, XMP and the ICC profile.
 */
public final class JxlImageReader extends ImageReader {

    private static final int READ_CHUNK_SIZE = 64 * 1024;

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

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        JxlImageInfo imageInfo = info();
        ColorSpace iccSpace = iccColorSpace(imageInfo.iccProfile(), imageInfo.channels());
        return List.of(BufferedImages.imageType(imageInfo.channels(), iccSpace)).iterator();
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
                metadata = new JxlImageMetadata(imageInfo, JxlDecoder.readMetadata(data()), true);
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
        if (abortRequested()) {
            processReadAborted();
            return null;
        }

        JxlImage decoded;
        try {
            decoded = JxlDecoder.decode(data(), imageInfo.channels());
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

    @Override
    public void reset() {
        super.reset();
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
