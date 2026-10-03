package panamage.jxl.imageio;

import java.util.Locale;
import java.util.Objects;

import javax.imageio.ImageWriteParam;

import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlThreads;

/**
 * Settings for {@link JxlImageWriter}.
 * <p>
 * With {@link #MODE_EXPLICIT}, the compression type {@value #LOSSY} maps the
 * compression quality from 0 to 1 onto the JPEG XL quality scale from 0 to 100
 * (1.0 is lossless), and {@value #LOSSLESS} encodes bit-exact. In every mode,
 * {@link #setEffort(int)} trades encoding speed for file size, and
 * {@link #setThreads(JxlThreads)} sets how many native threads libjxl uses
 * (by default as many as the image size suggests). Without explicit settings
 * (no parameter, or the modes {@link #MODE_DEFAULT} and
 * {@link #MODE_COPY_FROM_METADATA}), palette images such as GIFs and 8-bit
 * PNGs are encoded losslessly, which keeps them small, and all other images
 * lossy at distance 1.0 (visually lossless).
 * {@snippet :
 * JxlImageWriteParam param = (JxlImageWriteParam) writer.getDefaultWriteParam();
 * param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
 * param.setCompressionType(JxlImageWriteParam.LOSSLESS);
 * param.setEffort(9);
 * }
 */
public class JxlImageWriteParam extends ImageWriteParam {

    /** Compression type for lossy encoding with a quality from 0 to 1. */
    public static final String LOSSY = "Lossy";

    /** Compression type for bit-exact lossless encoding. */
    public static final String LOSSLESS = "Lossless";

    /** The quality that corresponds to the default distance of 1.0. */
    private static final float DEFAULT_QUALITY = 0.9f;

    private int effort = JxlEncodeOptions.DEFAULT_EFFORT;
    private JxlThreads threads = JxlThreads.auto();

    /**
     * Creates the default settings.
     *
     * @param locale the locale for compression descriptions, or {@code null}
     */
    public JxlImageWriteParam(Locale locale) {
        super(locale);
        canWriteCompressed = true;
        compressionTypes = new String[] {LOSSY, LOSSLESS};
        compressionType = LOSSY;
        compressionQuality = DEFAULT_QUALITY;
    }

    /**
     * Restores the default compression settings: {@value #LOSSY} with the
     * quality that corresponds to distance 1.0. Also called when the mode is
     * set to {@link #MODE_EXPLICIT}.
     */
    @Override
    public void unsetCompression() {
        super.unsetCompression();
        compressionType = LOSSY;
        compressionQuality = DEFAULT_QUALITY;
    }

    /**
     * Sets the encoder effort from 1 (fastest) to 10 (smallest output). The
     * effort does not affect decoding speed.
     *
     * @param effort the effort
     * @throws IllegalArgumentException if the effort is out of range
     */
    public void setEffort(int effort) {
        if (effort < JxlEncodeOptions.MIN_EFFORT || effort > JxlEncodeOptions.MAX_EFFORT) {
            throw new IllegalArgumentException("effort must be in [" + JxlEncodeOptions.MIN_EFFORT + ", "
                    + JxlEncodeOptions.MAX_EFFORT + "]: " + effort);
        }
        this.effort = effort;
    }

    /**
     * Returns the encoder effort.
     *
     * @return the effort from 1 to 10
     */
    public int getEffort() {
        return effort;
    }

    /**
     * Sets how many native threads libjxl uses to encode.
     *
     * @param threads the thread setting; {@link JxlThreads#auto()} by default
     */
    public void setThreads(JxlThreads threads) {
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    /**
     * Returns how many native threads libjxl uses to encode.
     *
     * @return the thread setting
     */
    public JxlThreads getThreads() {
        return threads;
    }

    @Override
    public boolean isCompressionLossless() {
        // Validates the mode and type as specified by ImageWriteParam.
        super.isCompressionLossless();
        return LOSSLESS.equals(getCompressionType()) || getCompressionQuality() >= 1.0f;
    }

    /**
     * Converts any write parameter, including {@code null} and parameters
     * of other writers, to encoder options.
     */
    static JxlEncodeOptions toOptions(ImageWriteParam param) {
        JxlThreads threads = param instanceof JxlImageWriteParam jxlParam ? jxlParam.getThreads()
                : JxlThreads.auto();
        return compression(param).withThreads(threads);
    }

    /**
     * Converts a write parameter like {@link #toOptions(ImageWriteParam)}, but
     * encodes a palette image losslessly unless the parameter sets the
     * compression explicitly.
     *
     * @param palette whether the image is a palette image or comes from a GIF
     */
    static JxlEncodeOptions toOptions(ImageWriteParam param, boolean palette) {
        if (!palette || isExplicit(param)) {
            return toOptions(param);
        }
        if (param instanceof JxlImageWriteParam jxlParam) {
            return JxlEncodeOptions.ofLossless().withEffort(jxlParam.getEffort()).withThreads(jxlParam.getThreads());
        }
        return JxlEncodeOptions.ofLossless();
    }

    /** Whether the parameter chooses the compression itself. */
    private static boolean isExplicit(ImageWriteParam param) {
        return param != null && param.canWriteCompressed()
                && (param.getCompressionMode() == MODE_EXPLICIT || param.getCompressionMode() == MODE_DISABLED);
    }

    /** Converts the compression settings and the effort. */
    private static JxlEncodeOptions compression(ImageWriteParam param) {
        if (param == null) {
            return JxlEncodeOptions.defaults();
        }
        int effort = param instanceof JxlImageWriteParam jxlParam ? jxlParam.getEffort()
                : JxlEncodeOptions.DEFAULT_EFFORT;
        if (!param.canWriteCompressed()) {
            return JxlEncodeOptions.defaults().withEffort(effort);
        }
        return switch (param.getCompressionMode()) {
            case MODE_DISABLED -> JxlEncodeOptions.ofLossless().withEffort(effort);
            case MODE_EXPLICIT -> {
                if (param.getCompressionType() == null) {
                    yield JxlEncodeOptions.defaults().withEffort(effort);
                } else if (LOSSLESS.equals(param.getCompressionType())) {
                    yield JxlEncodeOptions.ofLossless().withEffort(effort);
                }
                yield JxlEncodeOptions.ofQuality(param.getCompressionQuality() * 100.0f).withEffort(effort);
            }
            default -> JxlEncodeOptions.defaults().withEffort(effort);
        };
    }
}
