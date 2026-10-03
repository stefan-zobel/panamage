package panamage.jxl.imageio;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import panamage.jxl.JxlAnimationInfo;
import panamage.jxl.JxlDecodeOptions;
import panamage.jxl.JxlException;
import panamage.jxl.JxlFrame;
import panamage.jxl.JxlFrameDecoder;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlLimits;
import panamage.jxl.JxlMetadata;
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
 * interleaved samples.
 * <p>
 * Images in another color space than sRGB (such as Display P3, Adobe RGB,
 * linear RGB or gray with its own profile) are converted to sRGB by libjxl's
 * color management, so they get the standard types above and look right in
 * any code that assumes sRGB. Colors outside the sRGB gamut are clipped (or
 * kept beyond 0.0 to 1.0 with floating point samples). To keep the color
 * space of the image instead, with its ICC profile in the color model, call
 * {@link #setConvertToSrgb(boolean) setConvertToSrgb(false)}, or choose a
 * destination type with that color space from {@link #getImageTypes}.
 * {@snippet :
 * JxlImageReader reader = (JxlImageReader) ImageIO.getImageReadersByFormatName("jxl").next();
 * reader.setConvertToSrgb(false);
 * }
 * <p>
 * Each frame of an animation is an image: {@link #getNumImages(boolean)}
 * returns the number of frames (or -1 without {@code allowSearch}, until the
 * frames have been counted), and {@code read(i)} returns frame {@code i} as it
 * is displayed, with the size of the whole image. Reading the frames in order
 * decodes each frame once; the image metadata of a frame holds its duration
 * and name ({@link JxlImageMetadata#getFrameInfo()}).
 * <p>
 * Floating point samples are not clipped: HDR values above 1.0 and small
 * negative values from lossy encoding are kept. Java 2D does not clip them
 * either, so {@code getRGB} and drawing may show wrong colors for such
 * samples; request 8 or 16 bits with {@link ImageReadParam#setDestinationType}
 * for display.
 * <p>
 * {@link ImageReadParam} source regions and subsampling are supported, and
 * {@link ImageReadParam#setDestinationType} selects another precision or
 * color space from {@link #getImageTypes}, for example 8 bits for a 16-bit
 * image. Destination images and band selection are ignored. Image metadata
 * ({@link JxlImageMetadata}) provides EXIF, XMP and the ICC profile of the
 * image, also if its pixels are converted to sRGB.
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
 * <p>
 * The encoded input is read once and kept in native memory until the next
 * input, {@link #reset()} or {@link #dispose()}.
 */
public final class JxlImageReader extends ImageReader {

    private JxlLimits limits = JxlLimits.defaults();
    private boolean convertToSrgb = true;
    /** The encoded input in native memory, read once per input. */
    private EncodedInput input;
    private JxlImageInfo info;
    /** The color space of the image if it is not sRGB, once read; see {@link #originalSpace()}. */
    private ColorSpace originalSpace;
    private boolean originalSpaceRead;
    private JxlAnimationInfo animation;
    private JxlMetadata boxes;

    /** Decodes the frames of an animation in order; reopened to go back. */
    private JxlFrameDecoder frames;
    private JxlSampleType framesType;
    private JxlLimits framesLimits;
    private boolean framesSrgb;

    /** The sample type and color space of the decoded pixels. */
    private record Target(JxlSampleType type, boolean srgb) {
    }

    /**
     * Creates a reader; usually called through {@link JxlImageReaderSpi}.
     *
     * @param provider the service provider, or {@code null}
     */
    public JxlImageReader(JxlImageReaderSpi provider) {
        super(provider);
    }

    /**
     * Creates a reader for {@link JxlImageReaderSpi#createReaderInstance};
     * the declared return type keeps the Java 8 verifier from loading this
     * class when it verifies the provider.
     */
    static ImageReader create(JxlImageReaderSpi provider) {
        return new JxlImageReader(provider);
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

    /**
     * Sets whether images in another color space than sRGB are converted to
     * sRGB, if no destination type is given. The setting stays in effect for
     * later inputs, until {@link #reset()}.
     *
     * @param convertToSrgb {@code true} (the default) to convert the pixels to
     *                      sRGB, {@code false} to keep the color space of the
     *                      image
     */
    public void setConvertToSrgb(boolean convertToSrgb) {
        this.convertToSrgb = convertToSrgb;
    }

    /**
     * Returns whether images in another color space than sRGB are converted
     * to sRGB.
     *
     * @return {@code true} if the pixels are converted to sRGB
     */
    public boolean isConvertToSrgb() {
        return convertToSrgb;
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        clear();
    }

    /**
     * Returns 1 for a still image and the number of frames for an animation.
     * Counting the frames reads their headers; without {@code allowSearch},
     * -1 is returned for an animation until the frames have been counted.
     */
    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        requireInput();
        if (!info().animated()) {
            return 1;
        }
        return animation == null && !allowSearch ? -1 : animation().frameCount();
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
     * types with the other sample types (8-bit, 16-bit, floating point). For
     * an image in another color space than sRGB, these types are in sRGB if
     * the reader converts to sRGB, followed by the same types in the color
     * space of the image; otherwise the other way round.
     */
    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ColorSpace original = originalSpace();
        List<ImageTypeSpecifier> types = new ArrayList<>();
        if (original == null) {
            addImageTypes(types, null);
        } else if (convertToSrgb) {
            addImageTypes(types, null);
            addImageTypes(types, original);
        } else {
            addImageTypes(types, original);
            addImageTypes(types, null);
        }
        return types.iterator();
    }

    /** Adds the types of all sample types in the given color space, or in sRGB for {@code null}. */
    private void addImageTypes(List<ImageTypeSpecifier> types, ColorSpace space) throws IOException {
        JxlImageInfo imageInfo = info();
        types.add(BufferedImages.imageType(imageInfo.channels(), imageInfo.sampleType(), space));
        for (JxlSampleType type : JxlSampleType.values()) {
            if (type != imageInfo.sampleType()) {
                types.add(BufferedImages.imageType(imageInfo.channels(), type, space));
            }
        }
    }

    /**
     * Returns the sample type and color space for the parameter's destination
     * type: the sample type of its data type, and sRGB unless it has the color
     * space of the image. Without a destination type, the image keeps its
     * sample type and is converted as set with
     * {@link #setConvertToSrgb(boolean)}. Images that are sRGB need no
     * conversion.
     */
    private Target target(ImageReadParam param) throws IOException {
        boolean srgbImage = info().iccProfile() == null;
        ImageTypeSpecifier destinationType = param == null ? null : param.getDestinationType();
        if (destinationType == null) {
            return new Target(info().sampleType(), !srgbImage && convertToSrgb);
        }
        JxlSampleType type = switch (destinationType.getSampleModel().getDataType()) {
            case DataBuffer.TYPE_BYTE -> JxlSampleType.UINT8;
            case DataBuffer.TYPE_USHORT -> JxlSampleType.UINT16;
            case DataBuffer.TYPE_FLOAT -> JxlSampleType.FLOAT32;
            default -> throw new IIOException("Unsupported destination type; use one of getImageTypes");
        };
        ColorSpace space = destinationType.getColorModel().getColorSpace();
        return new Target(type, !srgbImage && !sameSpace(space, originalSpace()));
    }

    /**
     * Compares a color space with the one of the image (which may be
     * {@code null}) by their ICC profiles, which do not compare equal.
     */
    private static boolean sameSpace(ColorSpace space, ColorSpace original) {
        return space == original || space instanceof ICC_ColorSpace icc && original instanceof ICC_ColorSpace other
                && Arrays.equals(icc.getProfile().getData(), other.getProfile().getData());
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    /**
     * Returns the EXIF and XMP metadata, for images that are not sRGB the ICC
     * profile (also if the pixels are converted to sRGB) and for animations
     * the duration and name of the frame, as read-only
     * {@link JxlImageMetadata}.
     */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        JxlImageInfo imageInfo = info();
        if (boxes == null) {
            try {
                boxes = input().readMetadata(JxlDecodeOptions.defaults().withLimits(limits));
            } catch (JxlException e) {
                throw new IIOException("Cannot read JPEG XL metadata: " + e.getMessage(), e);
            }
        }
        return imageInfo.animated()
                ? new JxlImageMetadata(imageInfo, boxes, true, animation(), imageIndex)
                : new JxlImageMetadata(imageInfo, boxes, true);
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        JxlImageInfo imageInfo = info();
        Target target = target(param);
        if (abortRequested()) {
            processReadAborted();
            return null;
        }

        JxlImage decoded;
        try {
            decoded = imageInfo.animated() ? readFrame(imageIndex, imageInfo.channels(), target)
                    : input().decode(imageInfo.channels(), target.type(), decodeOptions(target));
        } catch (JxlException e) {
            closeFrames();
            throw new IIOException("Cannot decode JPEG XL image: " + e.getMessage(), e);
        }
        processImageProgress(90.0f);

        // The same instance as in the types from getImageTypes, if the pixels keep the color space of the image.
        ColorSpace iccSpace = Arrays.equals(decoded.iccProfile(), imageInfo.iccProfile())
                ? originalSpace() : iccColorSpace(decoded.iccProfile(), decoded.channels());
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

    /**
     * Decodes a frame of an animation, continuing with the open frame decoder
     * if the frame comes after the last one read.
     */
    private JxlImage readFrame(int index, int channels, Target target) throws IOException {
        if (frames == null || framesType != target.type() || framesSrgb != target.srgb()
                || !framesLimits.equals(limits) || frames.nextIndex() > index) {
            closeFrames();
            frames = input().openFrames(channels, target.type(), decodeOptions(target));
            framesType = target.type();
            framesSrgb = target.srgb();
            framesLimits = limits;
        }
        frames.skip(index - frames.nextIndex());
        JxlFrame frame = frames.next();
        if (frame == null) {
            throw new IIOException("The JPEG XL animation has no frame " + index);
        }
        return frame.image();
    }

    private JxlDecodeOptions decodeOptions(Target target) {
        return JxlDecodeOptions.defaults().withLimits(limits).withSrgb(target.srgb());
    }

    /** Also restores the default limits and the conversion to sRGB. */
    @Override
    public void reset() {
        super.reset();
        limits = JxlLimits.defaults();
        convertToSrgb = true;
        clear();
    }

    /** Releases the native copy of the input. */
    @Override
    public void dispose() {
        clear();
    }

    private void clear() {
        // The frame decoder holds a copy of its own.
        closeFrames();
        if (input != null) {
            input.close();
            input = null;
        }
        info = null;
        originalSpace = null;
        originalSpaceRead = false;
        animation = null;
        boxes = null;
    }

    private void closeFrames() {
        if (frames != null) {
            frames.close();
            frames = null;
        }
    }

    private ColorSpace iccColorSpace(byte[] iccProfile, int channels) {
        return BufferedImages.iccColorSpace(iccProfile, channels, this::processWarningOccurred);
    }

    /**
     * Returns the color space of the image, or {@code null} if it is sRGB or
     * its profile cannot be used. Read once per input, so a warning about the
     * profile is reported once, and the destination types from
     * {@link #getImageTypes} share the instance.
     */
    private ColorSpace originalSpace() throws IOException {
        if (!originalSpaceRead) {
            JxlImageInfo imageInfo = info();
            originalSpace = iccColorSpace(imageInfo.iccProfile(), imageInfo.channels());
            originalSpaceRead = true;
        }
        return originalSpace;
    }

    private void checkIndex(int imageIndex) throws IOException {
        requireInput();
        if (imageIndex == 0) {
            return;
        }
        int count = imageIndex > 0 && info().animated() ? animation().frameCount() : 1;
        if (imageIndex < 0 || imageIndex >= count) {
            throw new IndexOutOfBoundsException("Image index " + imageIndex + " is out of range; the JPEG XL file"
                    + (count == 1 ? " has one image" : " has " + count + " frames"));
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
                info = input().readInfo();
            } catch (JxlException e) {
                throw new IIOException("Cannot read JPEG XL header: " + e.getMessage(), e);
            }
        }
        return info;
    }

    private JxlAnimationInfo animation() throws IOException {
        if (animation == null) {
            try {
                animation = input().readAnimationInfo();
            } catch (JxlException e) {
                throw new IIOException("Cannot read JPEG XL frames: " + e.getMessage(), e);
            }
        }
        return animation;
    }

    /** Reads the input from its current position to the end, once. */
    EncodedInput input() throws IOException {
        if (input == null) {
            input = EncodedInput.read(requireInput());
        }
        return input;
    }
}
