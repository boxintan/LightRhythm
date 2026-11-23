import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Desktop diagnostic samples only; original files are read without modification. */
public class PrepareResolutionSamples {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: PrepareResolutionSamples.java NEW-output-directory image...");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Choose a new output directory; existing samples are preserved");
        }
        Files.createDirectories(output);
        StringBuilder manifest = new StringBuilder("id\tpath\tlabel\tsplit\tgroup\n");
        for (int sample = 1; sample < args.length; sample++) {
            BufferedImage original = ImageIO.read(new File(args[sample]));
            if (original == null) throw new IllegalArgumentException("Cannot decode " + args[sample]);
            try {
                for (int edge : new int[]{1920, 1600, 960, 480}) {
                    double scale = Math.min(1.0, (double) edge / Math.max(original.getWidth(), original.getHeight()));
                    int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
                    int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
                    BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = resized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(original, 0, 0, width, height, null);
                    } finally {
                        graphics.dispose();
                    }
                    String id = "sample-" + sample + "-" + edge;
                    Path file = output.resolve(id + ".png");
                    try {
                        if (!ImageIO.write(resized, "png", file.toFile())) throw new IllegalStateException("PNG encoder unavailable");
                    } finally {
                        resized.flush();
                    }
                    manifest.append(id).append('\t').append(file).append("\tunknown\tdev\tresolution-development\n");
                }
            } finally {
                original.flush();
            }
        }
        Files.writeString(output.resolve("manifest.tsv"), manifest.toString());
        System.out.println("Desktop bilinear samples: " + output.resolve("manifest.tsv"));
    }
}
