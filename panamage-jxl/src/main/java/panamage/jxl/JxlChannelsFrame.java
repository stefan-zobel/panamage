package panamage.jxl;

import java.util.Objects;

/**
 * A frame of a JPEG XL animation with every channel in an array of its own,
 * for example one time point or slice of a stack of microscope images.
 *
 * @param channels the channels of the frame, as large as the whole image
 * @param info     the duration and name of the frame
 * @see JxlFrameDecoder#nextChannels()
 * @see JxlFrameEncoder#add(JxlChannelsFrame)
 */
public record JxlChannelsFrame(JxlChannels channels, JxlFrameInfo info) {

    /**
     * Validates the frame.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public JxlChannelsFrame {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(info, "info");
    }
}
