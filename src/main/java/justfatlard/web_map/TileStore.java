package justfatlard.web_map;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Map tiles on disk, as the page's tile layer asks for them: {@code <dimension>/<zoom>/<x>_<y>.png},
 * 512 pixels a side. At {@link #NATIVE_ZOOM} a pixel is a block and a tile is a region; each zoom
 * below it covers twice the blocks across, a tile drawn from the four above it.
 *
 * <p>Touched only from the writer thread.
 */
final class TileStore {
	static final int SIZE = 512;
	static final int NATIVE_ZOOM = 4;

	private record Tile(String dimension, int zoom, int x, int y) {
		Tile parent() {
			return new Tile(dimension, zoom - 1, Math.floorDiv(x, 2), Math.floorDiv(y, 2));
		}
	}

	private final Path root;
	private final Map<Tile, BufferedImage> unflushed = new HashMap<>();

	TileStore(Path root) {
		this.root = root;
	}

	/** Paints one chunk's colours into its region's tile. */
	void paint(String dimension, int chunkX, int chunkZ, int[] colors) throws IOException {
		Tile tile = new Tile(dimension, NATIVE_ZOOM, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
		BufferedImage image = unflushed.get(tile);
		if (image == null) {
			image = read(file(tile));
			unflushed.put(tile, image);
		}
		image.setRGB(Math.floorMod(chunkX, 32) * 16, Math.floorMod(chunkZ, 32) * 16, 16, 16, colors, 0, 16);
	}

	/**
	 * Writes every tile painted since the last flush, then redraws the zoomed-out tiles above them.
	 * Whatever happens, the painted tiles are let go: one that failed is drawn again when its chunk
	 * next changes or loads. Answers whether anything was written.
	 */
	boolean flush() throws IOException {
		if (unflushed.isEmpty()) return false;
		try {
			Set<Tile> parents = new HashSet<>();
			for (Map.Entry<Tile, BufferedImage> entry : unflushed.entrySet()) {
				write(entry.getValue(), file(entry.getKey()));
				parents.add(entry.getKey().parent());
			}
			for (int zoom = NATIVE_ZOOM - 1; zoom >= 0 && !parents.isEmpty(); zoom--) {
				Set<Tile> above = new HashSet<>();
				for (Tile parent : parents) {
					write(downsample(parent), file(parent));
					if (zoom > 0) above.add(parent.parent());
				}
				parents = above;
			}
			return true;
		} finally {
			unflushed.clear();
		}
	}

	private BufferedImage downsample(Tile parent) throws IOException {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int dx = 0; dx < 2; dx++) {
			for (int dy = 0; dy < 2; dy++) {
				Path child = file(new Tile(parent.dimension, parent.zoom + 1, parent.x * 2 + dx, parent.y * 2 + dy));
				if (!Files.exists(child)) continue;
				BufferedImage image = ImageIO.read(child.toFile());
				if (image != null) g.drawImage(image, dx * SIZE / 2, dy * SIZE / 2, SIZE / 2, SIZE / 2, null);
			}
		}
		g.dispose();
		return out;
	}

	private Path file(Tile tile) {
		return root.resolve(tile.dimension).resolve(Integer.toString(tile.zoom)).resolve(tile.x + "_" + tile.y + ".png");
	}

	private static BufferedImage read(Path path) throws IOException {
		BufferedImage copy = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		if (Files.exists(path)) {
			BufferedImage existing = ImageIO.read(path.toFile());
			if (existing != null && existing.getWidth() == SIZE && existing.getHeight() == SIZE) {
				copy.getGraphics().drawImage(existing, 0, 0, null);
			}
		}
		return copy;
	}

	private static void write(BufferedImage image, Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		ImageIO.write(image, "png", temporary.toFile());
		Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
