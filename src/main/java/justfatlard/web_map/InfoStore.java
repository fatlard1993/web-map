package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is in each column of the map, for the page to say what a clicked spot is: per region, at the
 * native zoom only, {@code info/<dimension>/<x>_<y>.png}, two pixels a column across, 1024 by 512.
 * The first pixel is the top block's number in the palette, shifted up 12 bits, and the biome's; the
 * second is the ground's number under the top block, shifted up 12 bits, and the height plus 2048.
 * Number 0 is nothing: an empty column, no ground besides the top block, or past the palette's 4095.
 *
 * <p>A picture rather than JSON because the page already fetches pictures cheaply, and an opaque RGB
 * PNG comes back to a canvas with its numbers exact. The palette, {@code info/palette.json}, only ever
 * grows, so a number once written means the same thing for good; the copy kept in the world folder is
 * the one read back, never the one served.
 *
 * <p>Touched only from the writer thread.
 */
final class InfoStore {
	static final int SIZE = TileStore.SIZE;
	private static final int MOST = 4095;
	private static final int HEIGHT_OFFSET = 2048;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private record Region(String dimension, int x, int y) {}

	private static final class Palette {
		List<String> blocks = new ArrayList<>();
		List<String> biomes = new ArrayList<>();
	}

	private final Path root;
	private final Path kept;
	private final Map<Region, BufferedImage> unflushed = new HashMap<>();
	private Palette palette = new Palette();
	private final Map<String, Integer> blockNumbers = new HashMap<>();
	private final Map<String, Integer> biomeNumbers = new HashMap<>();
	private boolean paletteChanged;

	/**
	 * @param root where the page reads from
	 * @param kept the palette's copy in the world folder
	 */
	InfoStore(Path root, Path kept) {
		this.root = root;
		this.kept = kept;
		try {
			if (Files.exists(kept)) {
				Palette read = GSON.fromJson(Files.readString(kept, StandardCharsets.UTF_8), Palette.class);
				if (read != null && read.blocks != null && read.biomes != null) palette = read;
			}
		} catch (Exception e) {
			// Numbers already in the info pictures would be read against a different palette, so they are drawn again as chunks change
			Output.LOGGER.warn("Could not read the map's palette from {}; starting a new one", kept, e);
		}
		for (int i = 0; i < palette.blocks.size(); i++) blockNumbers.put(palette.blocks.get(i), i + 1);
		for (int i = 0; i < palette.biomes.size(); i++) biomeNumbers.put(palette.biomes.get(i), i + 1);
		paletteChanged = true;
	}

	void paint(String dimension, int chunkX, int chunkZ, ChunkColors.Info info) throws IOException {
		Region region = new Region(dimension, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
		BufferedImage image = unflushed.get(region);
		if (image == null) {
			image = read(file(region));
			unflushed.put(region, image);
		}
		int[] pixels = new int[512];
		for (int i = 0; i < 256; i++) {
			int x = i & 15, z = i >> 4;
			int first = 0, second = 0;
			if (info.tops()[i] != null) {
				int top = number(blockNumbers, palette.blocks, BuiltInRegistries.BLOCK.getKey(info.tops()[i]).toString());
				int biome = info.biomes()[i] == null ? 0 : info.biomes()[i].unwrapKey()
					.map(key -> number(biomeNumbers, palette.biomes, key.identifier().toString())).orElse(0);
				int ground = info.grounds()[i] == null ? 0 : number(blockNumbers, palette.blocks, BuiltInRegistries.BLOCK.getKey(info.grounds()[i]).toString());
				first = top << 12 | biome;
				second = ground << 12 | Math.clamp(info.heights()[i] + HEIGHT_OFFSET, 0, MOST);
			}
			pixels[z * 32 + x * 2] = first;
			pixels[z * 32 + x * 2 + 1] = second;
		}
		image.setRGB(Math.floorMod(chunkX, 32) * 32, Math.floorMod(chunkZ, 32) * 16, 32, 16, pixels, 0, 32);
	}

	/** Blanks one chunk where a picture of it was drawn. */
	void erase(String dimension, int chunkX, int chunkZ) throws IOException {
		Region region = new Region(dimension, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
		if (!unflushed.containsKey(region) && !Files.exists(file(region))) return;
		paint(dimension, chunkX, chunkZ, ChunkColors.Info.empty());
	}

	/** Writes every picture painted since the last flush, and the palette when it grew. Whatever happens, they are let go. */
	void flush() throws IOException {
		try {
			for (Map.Entry<Region, BufferedImage> entry : unflushed.entrySet()) write(entry.getValue(), file(entry.getKey()));
			if (paletteChanged) {
				String json = GSON.toJson(palette);
				writeText(kept, json);
				writeText(root.resolve("palette.json"), json);
				paletteChanged = false;
			}
		} finally {
			unflushed.clear();
		}
	}

	private int number(Map<String, Integer> numbers, List<String> names, String name) {
		Integer known = numbers.get(name);
		if (known != null) return known;
		if (names.size() >= MOST) return 0;
		names.add(name);
		numbers.put(name, names.size());
		paletteChanged = true;
		return names.size();
	}

	private Path file(Region region) {
		return root.resolve(region.dimension).resolve(region.x + "_" + region.y + ".png");
	}

	private static BufferedImage read(Path path) throws IOException {
		BufferedImage copy = new BufferedImage(SIZE * 2, SIZE, BufferedImage.TYPE_INT_RGB);
		if (Files.exists(path)) {
			BufferedImage existing = ImageIO.read(path.toFile());
			if (existing != null && existing.getWidth() == SIZE * 2 && existing.getHeight() == SIZE) {
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

	private static void writeText(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(temporary, text, StandardCharsets.UTF_8);
		Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
