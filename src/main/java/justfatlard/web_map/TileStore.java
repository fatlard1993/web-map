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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Map tiles on disk, as the page's tile layer asks for them: {@code <dimension>/<zoom>/<x>_<y>.png},
 * 512 pixels a side. At {@link #NATIVE_ZOOM} a pixel is a block and a tile is a region; each zoom
 * below it covers twice the blocks across, a tile drawn from the four above it, down to a dimension's
 * floor: the first zoom at which everything drawn fits in the four tiles around the origin, so the
 * page can show the whole of it however far it reaches.
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

	private static final Pattern TILE_NAME = Pattern.compile("(-?\\d+)_(-?\\d+)\\.png");

	private final Path root;
	private final Map<Tile, BufferedImage> unflushed = new HashMap<>();
	/** Per dimension, the native tiles drawn so far: least x, least y, greatest x, greatest y. */
	private final Map<String, int[]> extents = new TreeMap<>();
	private final Map<String, Integer> floors = new HashMap<>();

	TileStore(Path root) {
		this.root = root;
	}

	/** Paints one chunk's colours into its region's tile. */
	void paint(String dimension, int chunkX, int chunkZ, int[] colors) throws IOException {
		Tile tile = new Tile(dimension, NATIVE_ZOOM, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
		extents.merge(dimension, new int[] { tile.x, tile.y, tile.x, tile.y }, TileStore::union);
		BufferedImage image = unflushed.get(tile);
		if (image == null) {
			image = read(file(tile));
			unflushed.put(tile, image);
		}
		image.setRGB(Math.floorMod(chunkX, 32) * 16, Math.floorMod(chunkZ, 32) * 16, 16, 16, colors, 0, 16);
	}

	/** Blanks one chunk where a tile of it was drawn; a chunk never drawn stays as it is, with no tile made for it. */
	void erase(String dimension, int chunkX, int chunkZ) throws IOException {
		Tile tile = new Tile(dimension, NATIVE_ZOOM, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
		if (!unflushed.containsKey(tile) && !Files.exists(file(tile))) return;
		paint(dimension, chunkX, chunkZ, new int[256]);
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
			Set<String> deepened = new HashSet<>();
			for (Tile parent : parents) {
				int floor = floor(parent.dimension);
				if (floor < floors.getOrDefault(parent.dimension, floor)) deepened.add(parent.dimension);
				floors.put(parent.dimension, floor);
			}
			while (!parents.isEmpty()) {
				Set<Tile> above = new HashSet<>();
				for (Tile parent : parents) {
					write(downsample(parent), file(parent));
					if (parent.zoom > floors.get(parent.dimension)) above.add(parent.parent());
				}
				parents = above;
			}
			// A new floor's tiles were drawn only along the painted tiles' line; draw them from all of zoom 0
			for (String dimension : deepened) drawBelowZero(dimension);
			return true;
		} finally {
			unflushed.clear();
		}
	}

	/**
	 * Reads what is already drawn, then draws every zoom below 0 afresh from zoom 0: a map from before
	 * there were floors stops at 0, and one that grew while the server was down has a floor too shallow.
	 */
	void settle() throws IOException {
		if (!Files.isDirectory(root)) return;
		try (Stream<Path> dimensions = Files.list(root)) {
			for (Path folder : dimensions.filter(Files::isDirectory).toList()) {
				String dimension = folder.getFileName().toString();
				// Its name goes into the page's JSON as it is, so only a folder this mod could have named
				if (!Dimensions.folder(dimension).equals(dimension)) continue;
				for (Tile tile : tilesAt(dimension, NATIVE_ZOOM)) {
					extents.merge(dimension, new int[] { tile.x, tile.y, tile.x, tile.y }, TileStore::union);
				}
				if (extents.containsKey(dimension)) drawBelowZero(dimension);
			}
		}
	}

	/** As JSON, per dimension, the blocks its tiles cover and its floor, for the page to fit its view to. */
	String extentsJson() {
		StringBuilder json = new StringBuilder("{");
		for (Map.Entry<String, int[]> entry : extents.entrySet()) {
			int[] e = entry.getValue();
			if (json.length() > 1) json.append(',');
			json.append('"').append(entry.getKey()).append("\":{\"x0\":").append(e[0] * SIZE)
				.append(",\"z0\":").append(e[1] * SIZE).append(",\"x1\":").append((e[2] + 1) * SIZE)
				.append(",\"z1\":").append((e[3] + 1) * SIZE).append(",\"floor\":").append(floor(entry.getKey())).append('}');
		}
		return json.append('}').toString();
	}

	/** The first zoom, 0 or below, whose tiles around the origin hold everything drawn in the dimension. */
	int floor(String dimension) {
		int[] e = extents.get(dimension);
		int zoom = 0;
		if (e == null) return zoom;
		// Past a shift of 31 every int is 0 or -1, so this ends however far out a tile claims to be
		while (zoom > NATIVE_ZOOM - 31 && !aroundOrigin(e, NATIVE_ZOOM - zoom)) zoom--;
		return zoom;
	}

	private static boolean aroundOrigin(int[] extent, int shift) {
		return extent[0] >> shift >= -1 && extent[1] >> shift >= -1 && extent[2] >> shift <= 0 && extent[3] >> shift <= 0;
	}

	private static int[] union(int[] a, int[] b) {
		return new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3]) };
	}

	private void drawBelowZero(String dimension) throws IOException {
		int floor = floor(dimension);
		floors.put(dimension, floor);
		for (int zoom = -1; zoom >= floor; zoom--) {
			Set<Tile> level = new HashSet<>();
			for (Tile child : tilesAt(dimension, zoom + 1)) level.add(child.parent());
			for (Tile tile : level) write(downsample(tile), file(tile));
		}
	}

	private Set<Tile> tilesAt(String dimension, int zoom) throws IOException {
		Set<Tile> found = new HashSet<>();
		Path folder = root.resolve(dimension).resolve(Integer.toString(zoom));
		if (!Files.isDirectory(folder)) return found;
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.toList()) {
				Matcher name = TILE_NAME.matcher(file.getFileName().toString());
				if (name.matches()) {
					try {
						found.add(new Tile(dimension, zoom, Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2))));
					} catch (NumberFormatException e) {
						// Not a tile this store wrote
					}
				}
			}
		}
		return found;
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
