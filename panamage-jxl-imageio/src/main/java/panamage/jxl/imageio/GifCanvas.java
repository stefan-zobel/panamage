package panamage.jxl.imageio;

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;

/**
 * Composes the frames of a GIF animation as browsers show them: the JDK's
 * GIF reader returns every frame as it is stored, often only the area that
 * changed, at an offset and with a disposal method for the next frame.
 */
final class GifCanvas {

    private final BufferedImage canvas;
    /** The canvas before the last frame was drawn, if its disposal restores it. */
    private BufferedImage saved;
    private GifMetadata.Frame last;
    private int lastWidth;
    private int lastHeight;

    private GifCanvas(int width, int height) {
        canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Creates a transparent canvas of the logical screen size, or, without
     * it, large enough for the first frame at its position.
     */
    static GifCanvas create(Dimension screen, RenderedImage first, GifMetadata.Frame frame) {
        if (screen != null) {
            return new GifCanvas(screen.width, screen.height);
        }
        return new GifCanvas(Math.max(1, frame.left() + first.getWidth()),
                Math.max(1, frame.top() + first.getHeight()));
    }

    /**
     * Disposes of the previous frame, draws the image at its position and
     * returns the canvas; it changes with the next call.
     */
    BufferedImage draw(RenderedImage image, GifMetadata.Frame frame) {
        Graphics2D graphics = canvas.createGraphics();
        try {
            if (last != null) {
                switch (last.disposal()) {
                    case CLEAR -> {
                        graphics.setComposite(AlphaComposite.Clear);
                        graphics.fillRect(last.left(), last.top(), lastWidth, lastHeight);
                    }
                    case RESTORE -> {
                        graphics.setComposite(AlphaComposite.Src);
                        graphics.drawImage(saved, 0, 0, null);
                    }
                    case KEEP -> {
                        // The frame stays.
                    }
                }
            }
            if (frame.disposal() == GifMetadata.Disposal.RESTORE) {
                saved = copy(canvas, saved);
            }
            // Transparent pixels of the frame keep what is below them.
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawRenderedImage(image, AffineTransform.getTranslateInstance(frame.left(), frame.top()));
        } finally {
            graphics.dispose();
        }
        last = frame;
        lastWidth = image.getWidth();
        lastHeight = image.getHeight();
        return canvas;
    }

    private static BufferedImage copy(BufferedImage source, BufferedImage target) {
        BufferedImage copy = target != null ? target
                : new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copy.setData(source.getRaster());
        return copy;
    }
}
