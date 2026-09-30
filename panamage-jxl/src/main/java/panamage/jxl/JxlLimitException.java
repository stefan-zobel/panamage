package panamage.jxl;

import java.io.Serial;

/**
 * Thrown when an image or a metadata box exceeds the {@link JxlLimits} of a
 * decoding call. The data may be valid; it is rejected before the memory for
 * it is allocated.
 */
public class JxlLimitException extends JxlException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given detail message.
     *
     * @param message the detail message
     */
    public JxlLimitException(String message) {
        super(message);
    }
}
