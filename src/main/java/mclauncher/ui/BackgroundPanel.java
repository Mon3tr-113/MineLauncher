package mclauncher.ui;

import javax.swing.*;
import java.awt.*;

/** 背景面板：可绘制一张背景图（等比缩放居中铺满，不拉伸变形），没有图时用 Ui.BG 填充。 */
public class BackgroundPanel extends JPanel {

    private Image image;

    public BackgroundPanel(LayoutManager layout) {
        super(layout);
        setOpaque(true);
        setBackground(Ui.opaque(Ui.BG));
    }

    public void setImage(Image img) {
        this.image = img;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image == null) return;
        int iw = image.getWidth(null);
        int ih = image.getHeight(null);
        if (iw <= 0 || ih <= 0) return;
        double scale = Math.min((double) getWidth() / iw, (double) getHeight() / ih);
        int w = (int) Math.round(iw * scale);
        int h = (int) Math.round(ih * scale);
        int x = (getWidth() - w) / 2;
        int y = (getHeight() - h) / 2;
        g.drawImage(image, x, y, w, h, this);
    }
}
