package panamage.jxl.imageio;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlException;
import panamage.jxl.JxlFrameEncoder;
import panamage.jxl.JxlFrameInfo;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlMetadata;

/**
 * Writes JPEG XL images and animations with libjxl.
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
 * <p>
 * An animation is written as a sequence: {@link #prepareWriteSequence},
 * {@link #writeToSequence} for every frame and {@link #endWriteSequence}. The
 * first image sets the size, the sample type and the color space of the
 * animation, and every further image must match it after conversion. The
 * first image also provides the tick rate and the loop count
 * ({@link JxlImageMetadata#getAnimationHeader()}, by default 1000 ticks per
 * second and played forever), the EXIF and XMP data and, through its write
 * parameter, the compression of all frames; source region and subsampling
 * apply to every frame. Each frame is shown for the duration of its
 * {@link JxlImageMetadata#getFrameInfo()} in ticks, by default 100
 * milliseconds; only the last frame may have a duration of 0. Metadata read
 * from a JPEG XL animation carries these values, so {@code readAll} of every
 * frame followed by {@code writeToSequence} keeps the timing.
 * <p>
 * The encoded data is written to the output stream as it is produced; a
 * frame of a sequence is written when the next frame is added or the
 * sequence is ended. If encoding fails, the stream may contain part of the
 * image.
 *
 * @see JxlImageWriteParam
 */
public final class JxlImageWriter extends ImageWriter {

    /** The duration of a frame without frame information in its metadata. */
    static final long DEFAULT_FRAME_MILLIS = 100;

    private boolean sequenceStarted;
    private ImageOutputStream sequenceOutput;
    private JxlFrameEncoder sequence;
    private JxlAnimationHeader sequenceHeader;
    private int sequenceIndex;

    /**
     * Creates a writer; usually called through {@link JxlImageWriterSpi}.
     *
     * @param provider the service provider, or {@code null}
     */
    public JxlImageWriter(JxlImageWriterSpi provider) {
        super(provider);
    }

    /**
     * Creates a writer for {@link JxlImageWriterSpi#createWriterInstance};
     * the declared return type keeps the Java 8 verifier from loading this
     * class when it verifies the provider.
     */
    static ImageWriter create(JxlImageWriterSpi provider) {
        return new JxlImageWriter(provider);
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
            return jxl.copyForWriting();
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

    /**
     * Writes a still image; the animation header and frame information of
     * the metadata are ignored.
     *
     * @throws IllegalStateException if no output is set or a sequence is
     *                               being written
     */
    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (sequenceStarted) {
            throw new IllegalStateException("A sequence is being written; call endWriteSequence first");
        }
        ImageOutputStream output = requireOutput();
        Frame frame = prepare(image, param, 0);
        if (frame == null) {
            return;
        }
        JxlMetadata metadata = frame.metadata() == null ? JxlMetadata.NONE : frame.metadata().toJxlMetadata();
        try {
            JxlEncoder.encode(frame.pixels(), JxlImageWriteParam.toOptions(param), metadata,
                    new StreamAdapter(output));
        } catch (JxlException e) {
            throw new IIOException("Cannot encode JPEG XL image: " + e.getMessage(), e);
        }
        output.flush();
        processImageProgress(100.0f);
        processImageComplete();
    }

    /**
     * Returns {@code true}: animations are written as sequences.
     */
    @Override
    public boolean canWriteSequence() {
        return true;
    }

    /**
     * Starts an animation. Stream metadata is not supported and ignored with
     * a warning.
     *
     * @throws IllegalStateException if no output is set or a sequence is
     *                               already being written
     */
    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        ImageOutputStream output = requireOutput();
        if (sequenceStarted) {
            throw new IllegalStateException("A sequence is already being written");
        }
        if (streamMetadata != null) {
            processWarningOccurred(0, "Stream metadata is not written");
        }
        sequenceStarted = true;
        sequenceOutput = output;
        sequenceIndex = 0;
    }

    /**
     * Adds a frame to the animation; the previous frame is encoded and
     * written now. If the writer is aborted while the image is converted, the
     * image is skipped and the sequence can be continued or ended. If
     * encoding or writing fails, the sequence is closed and its output is
     * incomplete.
     *
     * @throws IllegalStateException    if {@link #prepareWriteSequence} was
     *                                  not called
     * @throws IllegalArgumentException if the image does not match the first
     *                                  image of the sequence or the previous
     *                                  frame has a duration of 0; the sequence
     *                                  is unchanged
     */
    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        if (!sequenceStarted) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        Frame frame = prepare(image, param, sequenceIndex);
        if (frame == null) {
            return;
        }
        JxlImageMetadata metadata = frame.metadata();
        try {
            if (sequence == null) {
                JxlAnimationHeader header = metadata == null ? null : metadata.getAnimationHeader();
                sequenceHeader = header == null ? JxlAnimationHeader.millis(0) : header;
                sequence = JxlFrameEncoder.open(new StreamAdapter(sequenceOutput), sequenceHeader,
                        JxlImageWriteParam.toOptions(param),
                        metadata == null ? JxlMetadata.NONE : metadata.toJxlMetadata());
            }
            JxlFrameInfo info = metadata == null ? null : metadata.getFrameInfo();
            long ticks = info == null ? defaultTicks(sequenceHeader) : info.durationTicks();
            sequence.add(frame.pixels(), ticks, metadata == null ? "" : metadata.frameName());
        } catch (JxlException e) {
            closeSequence();
            throw new IIOException("Cannot encode JPEG XL frame: " + e.getMessage(), e);
        } catch (IOException e) {
            closeSequence();
            throw e;
        }
        processImageProgress(100.0f);
        processImageComplete();
        sequenceIndex++;
    }

    /**
     * Encodes the last frame and completes the animation.
     *
     * @throws IllegalStateException if {@link #prepareWriteSequence} was not
     *                               called
     * @throws IIOException          if no image was written to the sequence or
     *                               encoding fails
     */
    @Override
    public void endWriteSequence() throws IOException {
        if (!sequenceStarted) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        ImageOutputStream output = sequenceOutput;
        try {
            if (sequence == null) {
                throw new IIOException("No image was written to the sequence");
            }
            sequence.finish();
        } catch (JxlException e) {
            throw new IIOException("Cannot encode JPEG XL frame: " + e.getMessage(), e);
        } finally {
            closeSequence();
        }
        output.flush();
    }

    /**
     * Sets the output; a sequence that is being written is abandoned, so its
     * output is incomplete.
     */
    @Override
    public void setOutput(Object output) {
        closeSequence();
        super.setOutput(output);
    }

    @Override
    public void reset() {
        closeSequence();
        super.reset();
    }

    @Override
    public void dispose() {
        closeSequence();
        super.dispose();
    }

    /** The default frame duration of 100 milliseconds in ticks of the header, at least 1. */
    static long defaultTicks(JxlAnimationHeader header) {
        double ticks = DEFAULT_FRAME_MILLIS / 1000.0 * header.ticksPerSecondNumerator()
                / header.ticksPerSecondDenominator();
        return Math.max(1, Math.round(ticks));
    }

    private ImageOutputStream requireOutput() {
        if (!(getOutput() instanceof ImageOutputStream output)) {
            throw new IllegalStateException("No output set");
        }
        return output;
    }

    /**
     * Checks the image, converts its metadata and pixels and reports the start
     * of the image to the listeners.
     *
     * @return the frame, or {@code null} if the writer was aborted
     */
    private Frame prepare(IIOImage image, ImageWriteParam param, int index) {
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("Writing rasters is not supported");
        }
        if (image.getNumThumbnails() > 0) {
            processWarningOccurred(index, "Thumbnails are not written");
        }
        JxlImageMetadata metadata = null;
        if (convertImageMetadata(image.getMetadata(), null, param) instanceof JxlImageMetadata converted) {
            metadata = converted;
        } else if (image.getMetadata() != null) {
            processWarningOccurred(index, "Unsupported image metadata is not written");
        }
        clearAbortRequest();
        processImageStarted(index);

        RenderedImage source = image.getRenderedImage();
        if (param != null && source instanceof BufferedImage buffered) {
            source = BufferedImages.applySourceRegion(buffered, param);
        }
        JxlImage pixels = BufferedImages.toJxlImage(source);
        if (abortRequested()) {
            processWriteAborted();
            return null;
        }
        return new Frame(pixels, metadata);
    }

    private void closeSequence() {
        if (sequence != null) {
            sequence.close();
        }
        sequence = null;
        sequenceHeader = null;
        sequenceOutput = null;
        sequenceStarted = false;
        sequenceIndex = 0;
    }

    /** The converted pixels and metadata of an image. */
    private record Frame(JxlImage pixels, JxlImageMetadata metadata) {
    }

    /** Lets the encoder write directly to the image output stream; closing it has no effect. */
    private static final class StreamAdapter extends OutputStream {

        private final ImageOutputStream output;

        StreamAdapter(ImageOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int b) throws IOException {
            output.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            output.write(b, off, len);
        }
    }
}
