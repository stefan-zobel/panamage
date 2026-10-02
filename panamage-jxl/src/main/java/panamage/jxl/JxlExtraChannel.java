package panamage.jxl;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The type and name of an extra channel of a {@link JxlChannels} image.
 *
 * @param type the type of the channel
 * @param name the name of the channel, or {@code ""} if it has none; at most
 *             1071 bytes in UTF-8
 */
public record JxlExtraChannel(JxlChannelType type, String name) {

    /** The longest name that JPEG XL can store, in UTF-8 bytes. */
    public static final int MAX_NAME_BYTES = 1071;

    /**
     * Validates the channel.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if the name is too long or contains
     *                                  U+0000
     */
    public JxlExtraChannel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The channel name must not contain U+0000");
        }
        int length = name.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("The channel name has " + length + " bytes in UTF-8, at most "
                    + MAX_NAME_BYTES + " are allowed");
        }
    }

    /**
     * Returns a channel of type {@link JxlChannelType#OPTIONAL} with the given
     * name, for data that viewers may ignore, such as a fluorescence channel.
     *
     * @param name the name of the channel, or {@code ""} for none
     * @return the channel
     * @throws IllegalArgumentException if the name is too long or contains
     *                                  U+0000
     */
    public static JxlExtraChannel of(String name) {
        return new JxlExtraChannel(JxlChannelType.OPTIONAL, name);
    }

    /**
     * Returns an alpha channel without a name.
     *
     * @return the channel
     */
    public static JxlExtraChannel alpha() {
        return new JxlExtraChannel(JxlChannelType.ALPHA, "");
    }
}
