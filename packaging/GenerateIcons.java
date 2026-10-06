import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the Creator CRM icon and writes every format the installers need.
 * Run from the project root:  java packaging/GenerateIcons.java
 */
public class GenerateIcons {

    public static void main(String[] args) throws IOException {
        Path icons = Path.of("packaging", "icons");
        Files.createDirectories(icons);
        Files.createDirectories(Path.of("src/main/resources/desktop"));

        ImageIO.write(draw(512), "png", icons.resolve("creator-crm.png").toFile());
        Files.write(icons.resolve("creator-crm.ico"), ico(List.of(16, 24, 32, 48, 64, 128, 256)));
        Files.write(icons.resolve("creator-crm.icns"), icns());
        ImageIO.write(draw(64), "png", Path.of("src/main/resources/desktop/tray.png").toFile());
        ImageIO.write(draw(64), "png", Path.of("src/main/resources/static/favicon.png").toFile());

        // Phone home-screen icons (manifest.webmanifest and the apple-touch-icon link in the pages)
        Path web = Path.of("src/main/resources/static/icons");
        Files.createDirectories(web);
        ImageIO.write(draw(192), "png", web.resolve("icon-192.png").toFile());
        ImageIO.write(draw(512), "png", web.resolve("icon-512.png").toFile());
        ImageIO.write(drawFullBleed(512), "png", web.resolve("maskable-512.png").toFile());
        ImageIO.write(drawFullBleed(180), "png", web.resolve("apple-touch-icon.png").toFile());
        System.out.println("Icons written to " + icons.toAbsolutePath());
    }

    /** Rounded orange square with a white "C" ring and a spark: a conversation that keeps moving. */
    static BufferedImage draw(int size) {
        return draw(size, false);
    }

    /**
     * Square orange tile with no transparent corners, for phones that cut their own shape (Android "maskable")
     * or would fill the corners with black (iPhone). The mark is shrunk to stay inside the safe circle.
     */
    static BufferedImage drawFullBleed(int size) {
        return draw(size, true);
    }

    private static BufferedImage draw(int size, boolean fullBleed) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        double pad = s * 0.04;
        g.setPaint(new GradientPaint(0, 0, new Color(0xF9, 0x73, 0x16), (float) s, (float) s, new Color(0xC2, 0x41, 0x0C)));
        if (fullBleed) {
            g.fillRect(0, 0, size, size);
            g.translate(s * 0.15, s * 0.15);
            g.scale(0.7, 0.7);
        } else {
            g.fill(new RoundRectangle2D.Double(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.42, s * 0.42));
        }

        g.setColor(Color.WHITE);
        float stroke = (float) (s * 0.13);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double r = s * 0.25;
        double cx = s * 0.47;
        double cy = s * 0.53;
        g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 40, 280, Arc2D.OPEN));

        double dot = s * 0.12;
        g.fill(new Ellipse2D.Double(s * 0.80 - dot / 2, s * 0.22 - dot / 2, dot, dot));
        g.dispose();
        return img;
    }

    static byte[] png(int size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(draw(size), "png", out);
        return out.toByteArray();
    }

    /** Windows .ico with PNG-compressed entries (supported since Windows Vista). */
    static byte[] ico(List<Integer> sizes) throws IOException {
        List<byte[]> images = new java.util.ArrayList<>();
        for (int sz : sizes) images.add(png(sz));
        int headerSize = 6 + 16 * sizes.size();
        ByteBuffer dir = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        dir.putShort((short) 0).putShort((short) 1).putShort((short) sizes.size());
        int offset = headerSize;
        for (int i = 0; i < sizes.size(); i++) {
            int sz = sizes.get(i);
            dir.put((byte) (sz >= 256 ? 0 : sz)).put((byte) (sz >= 256 ? 0 : sz)).put((byte) 0).put((byte) 0);
            dir.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(dir.array());
        for (byte[] b : images) out.write(b);
        return out.toByteArray();
    }

    /** macOS .icns with PNG entries: ic07=128, ic08=256, ic09=512, ic10=1024. */
    static byte[] icns() throws IOException {
        String[][] entries = {{"ic07", "128"}, {"ic08", "256"}, {"ic09", "512"}, {"ic10", "1024"}};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        for (String[] e : entries) {
            byte[] p = png(Integer.parseInt(e[1]));
            d.writeBytes(e[0]);
            d.writeInt(p.length + 8);
            d.write(p);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(out);
        o.writeBytes("icns");
        o.writeInt(body.size() + 8);
        o.write(body.toByteArray());
        return out.toByteArray();
    }
}
