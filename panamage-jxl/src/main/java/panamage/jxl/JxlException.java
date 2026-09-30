package panamage.jxl;

import java.io.Serial;

/**
 * Thrown when libjxl reports an error, for example for invalid or truncated
 * JPEG XL data.
 */
public class JxlException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given detail message.
     *
     * @param message the detail message
     */
    public JxlException(String message) {
        super(message);
    }
}
