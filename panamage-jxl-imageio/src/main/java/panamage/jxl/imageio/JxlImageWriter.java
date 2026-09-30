package panamage.jxl.imageio;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;

import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlException;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlMetadata;

/**
 * Writes JPEG XL images with libjxl.
 * <p>
 * Gray and RGB images with a {@link java.awt.image.ComponentColorModel} and
 * 8-bit, 16-bit or floating point samples (for example {@code TYPE_BYTE_GRAY},
 * {@code TYPE_USHORT_GRAY}, {@code TYPE_3BYTE_BGR}, {@code TYPE_4BYTE_ABGR} and
 * the images of the JDK's PNG reader) are written with their precision, alpha
 * channel and color space; a color space other than sRGB is stored as ICC
 * profile. All other images, such as {@code TYPE_INT_RGB} or indexed images,
 * are converted to 8-bit sRGB: RGB, or RGBA if they have alpha. Source regions
 * and subsampling of the write parameter are supported.
 * <p>
 * EXIF and XMP are written from {@link JxlImageMetadata} or from the metadata
 * of the JDK's JPEG reader, so {@code readAll} on a JPEG followed by
 * {@code write} keeps them. An EXIF orientation is applied as the image
 * orientation, so images read from JPEG files are displayed as intended.
 * Thumbnails are not written.
 *
 * @see JxlImageWriteParam
 */
public final class JxlImageWriter extends ImageWriter {

    /**
     * Creates a writer; usually called through {@link JxlImageWriterSpi}.
     *
     * @param provider the service provider, or {@code null}
     */
    public JxlImageWriter(JxlImageWriterSpi provider) {
        super(provider);
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new JxlImageWriteParam(getLocale());
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    /**
     * Returns empty, modifiable {@link JxlImageMetadata}.
     */
    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return new JxlImageMetadata();
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    /**
     * Converts {@link JxlImageMetadata}, metadata in the native format
     * {@value JxlImageMetadataFormat#NAME} and the metadata of the JDK's JPEG
     * reader (EXIF and XMP from APP1 segments) to modifiable
     * {@link JxlImageMetadata}; returns {@code null} for other metadata.
     */
    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType,
            ImageWriteParam param) {
        if (inData == null) {
            return null;
        }
        if (inData instanceof JxlImageMetadata jxl) {
            return new JxlImageMetadata(null, jxl.toJxlMetadata(), false);
        }
        if (JpegMetadata.isSupported(inData)) {
            return new JxlImageMetadata(null, JpegMetadata.extract(inData), false);
        }
        if (JxlImageMetadataFormat.NAME.equals(inData.getNativeMetadataFormatName())) {
            JxlImageMetadata converted = new JxlImageMetadata();
            try {
                converted.mergeTree(JxlImageMetadataFormat.NAME, inData.getAsTree(JxlImageMetadataFormat.NAME));
                return converted;
            } catch (IIOInvalidTreeException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (!(getOutput() instanceof ImageOutputStream output)) {
            throw new IllegalStateException("No output set");
        }
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("Writing rasters is not supported");
        }
        if (image.getNumThumbnails() > 0) {
            processWarningOccurred(0, "Thumbnails are not written");
        }
        JxlMetadata metadata = JxlMetadata.NONE;
        if (convertImageMetadata(image.getMetadata(), null, param) instanceof JxlImageMetadata converted) {
            metadata = converted.toJxlMetadata();
        } else if (image.getMetadata() != null) {
            processWarningOccurred(0, "Unsupported image metadata is not written");
        }
        clearAbortRequest();
        processImageStarted(0);

        RenderedImage source = image.getRenderedImage();
        if (param != null && source instanceof BufferedImage buffered) {
            source = BufferedImages.applySourceRegion(buffered, param);
        }
        JxlImage pixels = BufferedImages.toJxlImage(source);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }

        byte[] encoded;
        try {
            encoded = JxlEncoder.encode(pixels, JxlImageWriteParam.toOptions(param), metadata);
        } catch (JxlException e) {
            throw new IIOException("Cannot encode JPEG XL image: " + e.getMessage(), e);
        }
        output.write(encoded);
        output.flush();
        processImageProgress(100.0f);
        processImageComplete();
    }
}
