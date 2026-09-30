package panamage.jxl;

import java.util.Objects;

/**
 * A decoded frame of a JPEG XL animation.
 *
 * @param image the pixels of the frame, as large as the whole image
 * @param info  the duration and name of the frame
 */
public record JxlFrame(JxlImage image, JxlFrameInfo info) {

    /**
     * Validates the frame.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public JxlFrame {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(info, "info");
    }
}
