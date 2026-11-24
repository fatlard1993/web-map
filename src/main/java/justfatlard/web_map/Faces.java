package justfatlard.web_map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Players' faces for the page, {@code heads/<name>.png}: the 8x8 face of their skin with its hat
 * layer over it, written again only when the skin changed. Writer thread only.
 */
final class Faces {
	/** A name that is safe as a file name and in a URL; anything else gets no face. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private final Path folder;
	private final Map<String, Integer> written = new HashMap<>();

	Faces(Path folder) {
		this.folder = folder;
	}

	/** The face's version, for the page to ask for it afresh when it changes, or null when there is none. */
	String face(String name, byte[] skin) {
		if (skin == null || !NAME.matcher(name).matches()) return null;
		int version = Arrays.hashCode(skin);
		Integer before = written.get(name);
		if (before != null && before == version) return Integer.toHexString(version);
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(skin));
			if (image == null || image.getWidth() < 64 || image.getHeight() < 32) return null;
			BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 8; x++) {
					int base = image.getRGB(8 + x, 8 + y) | 0xFF000000;
					int hat = image.getRGB(40 + x, 8 + y);
					face.setRGB(x, y, (hat >>> 24) >= 128 ? hat | 0xFF000000 : base);
				}
			}
			Files.createDirectories(folder);
			Path file = folder.resolve(name + ".png");
			Path temporary = folder.resolve(name + ".png.tmp");
			ImageIO.write(face, "png", temporary.toFile());
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			written.put(name, version);
			return Integer.toHexString(version);
		} catch (Exception e) {
			Output.LOGGER.warn("Could not draw {}'s face for the map", name, e);
			return null;
		}
	}
}
