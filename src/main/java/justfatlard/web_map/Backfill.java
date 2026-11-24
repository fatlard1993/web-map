package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Draws the world as it was saved before the map was installed, once, from the region files, without
 * loading a chunk into the game: each saved chunk is read through the server's own chunk storage, which
 * knows about writes still pending, upgraded if an older version saved it, parsed, and drawn the way a
 * loaded one is.
 *
 * <p>On a thread of its own at the lowest priority, so the server's ticks never wait on it, and it runs
 * while an empty server is paused. Regions are drawn a row at a time from the north, so each chunk is
 * shaded against the one north of it. A chunk drawn live since the server started is newer than its
 * save, and is passed over. Which regions are done is kept in the world folder, so a restart picks up
 * where it stopped and a finished map is not drawn again.
 */
final class Backfill {
	private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final Gson GSON = new Gson();
	/** A pause after each chunk, so a full world takes longer rather than taking the machine. */
	private static final long PAUSE_MILLIS = 1;

	private final MinecraftServer server;
	private final Drawing drawing;
	private final List<ServerLevel> levels;
	private final Path progressFile;
	private volatile boolean stopping;
	private Thread thread;

	Backfill(MinecraftServer server, Drawing drawing, List<ServerLevel> levels) {
		this.server = server;
		this.drawing = drawing;
		this.levels = levels;
		this.progressFile = server.getWorldPath(LevelResource.ROOT).resolve("web-map").resolve("drawn-regions.json");
	}

	void start() {
		thread = new Thread(this::run, "web-map backfill");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	void stop() {
		stopping = true;
		if (thread != null) thread.interrupt();
	}

	private void run() {
		Map<String, Set<String>> done = readProgress();
		for (ServerLevel level : levels) {
			if (stopping) return;
			String dimension = Dimensions.folder(level.dimension());
			Set<String> doneHere = done.computeIfAbsent(dimension, d -> new HashSet<>());
			List<int[]> regions = regions(level).stream()
				.filter(region -> !doneHere.contains(region[0] + "," + region[1]))
				.sorted(Comparator.<int[]>comparingInt(region -> region[1]).thenComparingInt(region -> region[0]))
				.toList();
			if (regions.isEmpty()) continue;
			Output.LOGGER.info("Drawing the saved {}: {} regions to go", dimension, regions.size());

			Long2ObjectMap<int[]> southEdges = new Long2ObjectOpenHashMap<>();
			int drawn = 0;
			for (int[] region : regions) {
				if (stopping) return;
				try {
					drawRegion(level, dimension, region[0], region[1], southEdges);
				} catch (InterruptedException interrupted) {
					return;
				} catch (RegionUnreadable e) {
					// A damaged region file does not mend itself: logged, and not tried again
					Output.LOGGER.warn("Could not read saved region {}, {} of {}; it stays blank until its chunks load", region[0], region[1], dimension, e.getCause());
				} catch (Exception e) {
					if (stopping) return;
					// The tiles did not reach the disk, a full one say: nothing more is drawn until the next start
					Output.LOGGER.error("Could not write the saved {}; drawing it stops until the server next starts", dimension, e);
					return;
				}
				if (stopping) return;
				// Edges more than a region row north are no longer needed by anything still to draw
				int keepFromZ = region[1] * 32 - 1;
				southEdges.long2ObjectEntrySet().removeIf(entry -> ChunkPos.getZ(entry.getLongKey()) < keepFromZ - 32);
				doneHere.add(region[0] + "," + region[1]);
				writeProgress(done);
				drawn++;
				if (drawn % 50 == 0 || drawn == regions.size()) {
					Output.LOGGER.info("Drawing the saved {}: {} of {} regions", dimension, drawn, regions.size());
				}
			}
		}
	}

	private void drawRegion(ServerLevel level, String dimension, int regionX, int regionZ, Long2ObjectMap<int[]> southEdges)
			throws Exception {
		boolean[] present;
		try {
			present = present(regionFile(level, regionX, regionZ));
		} catch (Exception e) {
			throw new RegionUnreadable(e);
		}
		int failed = 0;
		Exception first = null;

		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				if (stopping) return;
				if (!present[z * 32 + x]) continue;
				ChunkPos pos = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);
				if (drawing.drawnLive(level, pos.pack())) continue;
				ChunkColors.Drawn colours;
				try {
					colours = drawSaved(level, pos, southEdges.get(ChunkPos.pack(pos.x(), pos.z() - 1)));
				} catch (Exception e) {
					// One damaged chunk leaves only itself blank
					if (stopping) return;
					failed++;
					if (first == null) first = e;
					continue;
				}
				if (colours == null) continue;
				southEdges.put(pos.pack(), colours.southEdge());
				drawing.paintSaved(level, dimension, pos.x(), pos.z(), colours);
				Thread.sleep(PAUSE_MILLIS);
			}
		}
		if (failed > 0) Output.LOGGER.warn("{} saved chunks in region {}, {} of {} could not be read; they stay blank until they load", failed, regionX, regionZ, dimension, first);
		// Waits for the tiles to be on disk before the region counts as drawn; an error here is thrown on
		drawing.flushNow().join();
	}

	private static final class RegionUnreadable extends Exception {
		RegionUnreadable(Exception cause) {
			super(cause);
		}
	}

	/** A saved chunk's colours as it was saved, or null when it was never fully generated or saved. */
	static ChunkColors.Drawn drawSaved(ServerLevel level, ChunkPos pos, int[] northEdge) {
		CompoundTag tag = level.getChunkSource().chunkMap.read(pos).join().orElse(null);
		if (tag == null) return null;
		if (NbtUtils.getDataVersion(tag, -1) < SharedConstants.getCurrentVersion().dataVersion().version()) {
			tag = level.getChunkSource().chunkMap.upgradeChunkTag(tag, -1);
		}
		SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
		if (data.chunkStatus() != ChunkStatus.FULL) return null;
		int minY = level.getMinY();
		return ChunkColors.draw(saved(data, level, minY, Mth.ceillog2(level.getHeight() + 1)), EmptyBlockGetter.INSTANCE,
			ChunkColors.sliceOf(level), minY, pos.getMinBlockX(), pos.getMinBlockZ(), northEdge)
			.withSigns(Signs.saved(Dimensions.folder(level.dimension()), data.blockEntities(), level.registryAccess()));
	}

	/** A parsed saved chunk's blocks; a column without a stored height is found by looking down from the top. */
	private static ChunkColors.Blocks saved(SerializableChunkData data, ServerLevel level, int minY, int bits) {
		LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			int index = section.y() - level.getMinSectionY();
			if (index >= 0 && index < sections.length && section.chunkSection() != null) sections[index] = section.chunkSection();
		}
		long[] packed = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
		SimpleBitStorage heights = packed == null ? null : new SimpleBitStorage(bits, 256, packed);
		int maxY = level.getMaxY();

		return new ChunkColors.Blocks() {
			public BlockState at(int x, int y, int z) {
				int index = (y >> 4) - level.getMinSectionY();
				if (index < 0 || index >= sections.length || sections[index] == null) return Blocks.AIR.defaultBlockState();
				return sections[index].getBlockState(x & 15, y & 15, z & 15);
			}

			public Holder<Biome> biome(int x, int y, int z) {
				int index = (y >> 4) - level.getMinSectionY();
				if (index < 0 || index >= sections.length || sections[index] == null) return level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
				return sections[index].getNoiseBiome(QuartPos.fromBlock(x) & 3, QuartPos.fromBlock(y) & 3, QuartPos.fromBlock(z) & 3);
			}

			public int top(int x, int z) {
				if (heights != null) return heights.get((x & 15) + (z & 15) * 16) + minY - 1;
				for (int y = maxY; y >= minY; y--) if (!at(x, y, z).isAir()) return y;
				return minY - 1;
			}
		};
	}

	private List<int[]> regions(ServerLevel level) {
		List<int[]> regions = new ArrayList<>();
		Path folder = regionFolder(level);
		if (!Files.isDirectory(folder)) return regions;
		try (Stream<Path> files = Files.list(folder)) {
			files.forEach(file -> {
				Matcher name = REGION.matcher(file.getFileName().toString());
				if (name.matches()) regions.add(new int[] {Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2))});
			});
		} catch (Exception e) {
			Output.LOGGER.warn("Could not list the saved regions in {}", folder, e);
		}
		return regions;
	}

	private Path regionFolder(ServerLevel level) {
		return DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
	}

	private Path regionFile(ServerLevel level, int x, int z) {
		return regionFolder(level).resolve("r." + x + "." + z + ".mca");
	}

	/** Which of a region's 1024 chunks were ever saved, from the offsets at the head of its file. */
	static boolean[] present(Path file) throws Exception {
		boolean[] present = new boolean[1024];
		try (InputStream in = Files.newInputStream(file); DataInputStream offsets = new DataInputStream(in)) {
			for (int i = 0; i < 1024; i++) present[i] = offsets.readInt() != 0;
		}
		return present;
	}

	private Map<String, Set<String>> readProgress() {
		try {
			if (Files.exists(progressFile)) {
				Map<String, Set<String>> read = GSON.fromJson(Files.readString(progressFile, StandardCharsets.UTF_8),
					new TypeToken<Map<String, Set<String>>>() {}.getType());
				if (read != null) return new HashMap<>(read);
			}
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read which saved regions are drawn; drawing them all", e);
		}
		return new HashMap<>();
	}

	private void writeProgress(Map<String, Set<String>> done) {
		try {
			Files.createDirectories(progressFile.getParent());
			Path temporary = progressFile.resolveSibling(progressFile.getFileName() + ".tmp");
			Files.writeString(temporary, GSON.toJson(done), StandardCharsets.UTF_8);
			Files.move(temporary, progressFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Output.LOGGER.warn("Could not keep which saved regions are drawn", e);
		}
	}
}
