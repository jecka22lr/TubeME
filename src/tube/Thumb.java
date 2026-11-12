package tube;

import javax.microedition.lcdui.CustomItem;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Thumbnail drawn by the app. Some phones (Nokia C6-00) hide ImageItems in
 * forms, so the picture is painted on a CustomItem instead.
 */
final class Thumb extends CustomItem {
    private final Image img;

    Thumb(Image img) {
        super(null);
        this.img = img;
    }

    protected int getMinContentWidth() { return img.getWidth(); }
    protected int getMinContentHeight() { return img.getHeight(); }
    protected int getPrefContentWidth(int h) { return img.getWidth(); }
    protected int getPrefContentHeight(int w) { return img.getHeight(); }

    protected void paint(Graphics g, int w, int h) {
        g.drawImage(img, (w - img.getWidth()) / 2, 0, Graphics.TOP | Graphics.LEFT);
    }
}
