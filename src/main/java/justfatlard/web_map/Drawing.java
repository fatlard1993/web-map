package justfatlard.web_map;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Which chunks need drawing, and drawing them a few at a time.
 *
 * <p>A chunk is queued when it loads and whenever anything in it changes ({@code LevelChunk.markUnsaved},
 * hooked), and the queue is drawn a budget's worth each tick, so neither a burst of loads nor a busy
 * base lands on one tick. A drawn chunk's colours are remembered while it stays loaded, and it is
 * painted only when they differ, so a furnace ticking away changes nothing on the map.
 *
 * <p>Server thread only, apart from what {@link Backfill} calls, which says so.
 */
final class Drawing {
	private static final int DRAWS_PER_TICK = 24;

	private final Output output;
	private final TileStore tiles;
	private final InfoStore info;
	private final Signs signs;
	private volatile HiddenAreas hidden = HiddenAreas.NONE;
	private final Set<String> dimensions;
	private final Map<ServerLevel, LongLinkedOpenHashSet> queued = new HashMap<>();
	private final Map<ServerLevel, Long2IntMap> drawnColours = new HashMap<>();
	/** Chunks drawn from the live world since the server started, which a saved copy must not overwrite. */
	private final Map<ServerLevel, Set<Long>> drawnLive = new ConcurrentHashMap<>();
	private final Consumer<String> whenWritten;

	/**
	 * @param whenWritten run on the writer thread with the tiles' extents, once what is on disk has been
	 *                    read and after any flush that wrote tiles
	 */
	/** @param kept the world folder's web-map folder, where what is read back at start is kept */
	Drawing(Output output, Set<String> dimensions, Path kept, Consumer<String> whenWritten) {
		this.output = output;
		this.tiles = new TileStore(output.root.resolve("tiles"));
		this.info = new InfoStore(output.root.resolve("info"), kept.resolve("info-palette.json"));
		this.signs = new Signs(kept.resolve("signs.json"));
		this.dimensions = dimensions;
		this.whenWritten = whenWritten;
		output.later("read the map tiles", () -> {
			tiles.settle();
			whenWritten.accept(tiles.extentsJson());
		});
	}

	boolean draws(ServerLevel level) {
		return dimensions.contains(level.dimension().identifier().toString());
	}

	void loaded(ServerLevel level, LevelChunk chunk) {
		if (!draws(level)) return;
		ChunkPos pos = chunk.getPos();
		queue(level, pos.pack());
		// The row along a chunk's north edge is shaded against this one, which may not have been here
		long south = ChunkPos.pack(pos.x(), pos.z() + 1);
		if (level.getChunkSource().getChunkNow(pos.x(), pos.z() + 1) != null) queue(level, south);
	}

	void changed(ServerLevel level, ChunkPos pos) {
		if (draws(level)) queue(level, pos.pack());
	}

	void unloaded(ServerLevel level, LevelChunk chunk) {
		if (!draws(level)) return;
		long pos = chunk.getPos().pack();
		LongLinkedOpenHashSet waiting = queued.get(level);
		if (waiting != null && waiting.remove(pos)) draw(level, chunk);
		Long2IntMap colours = drawnColours.get(level);
		if (colours != null) colours.remove(pos);
	}

	void tick() {
		int budget = DRAWS_PER_TICK;
		for (Map.Entry<ServerLevel, LongLinkedOpenHashSet> entry : queued.entrySet()) {
			ServerLevel level = entry.getKey();
			LongLinkedOpenHashSet waiting = entry.getValue();
			while (budget > 0 && !waiting.isEmpty()) {
				long pos = waiting.removeFirstLong();
				LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
				if (chunk == null) continue;
				draw(level, chunk);
				budget--;
			}
		}
	}

	/** Writes what was painted since the last flush. */
	void flush() {
		output.later("write map tiles", () -> {
			info.flush();
			signs.flush();
			if (tiles.flush()) whenWritten.accept(tiles.extentsJson());
		});
	}

	/** Any thread. */
	boolean drawnLive(ServerLevel level, long pos) {
		Set<Long> drawn = drawnLive.get(level);
		return drawn != null && drawn.contains(pos);
	}

	/**
	 * Any thread: a saved chunk's colours, painted in turn with the live ones. Asked again on the writer
	 * thread whether the chunk was drawn live, since that may have happened after the saved copy was read.
	 */
	void paintSaved(ServerLevel level, String dimension, int x, int z, ChunkColors.Drawn drawn) {
		long pos = ChunkPos.pack(x, z);
		output.later("paint saved chunk " + x + ", " + z, () -> {
			if (!drawnLive(level, pos)) paint(dimension, x, z, drawn);
		});
	}

	/** Any thread: writes what was painted, completing once it is on disk, or with the error that stopped it. */
	java.util.concurrent.CompletableFuture<Boolean> flushNow() {
		return output.submit(() -> {
			info.flush();
			signs.flush();
			boolean wrote = tiles.flush();
			if (wrote) whenWritten.accept(tiles.extentsJson());
			return wrote;
		});
	}

	void forget() {
		queued.clear();
		drawnColours.clear();
	}

	private void queue(ServerLevel level, long pos) {
		queued.computeIfAbsent(level, l -> new LongLinkedOpenHashSet()).add(pos);
	}

	private void draw(ServerLevel level, LevelChunk chunk) {
		ChunkColors.Drawn drawn = ChunkColors.of(level, chunk);
		ChunkColors.Info about = drawn.info();
		int hash = Objects.hash(Arrays.hashCode(drawn.colors()), Arrays.hashCode(about.tops()), Arrays.hashCode(about.grounds()),
			Arrays.hashCode(about.biomes()), Arrays.hashCode(about.heights()), drawn.signs());
		long pos = chunk.getPos().pack();
		Long2IntMap colours = drawnColours.computeIfAbsent(level, l -> new Long2IntOpenHashMap());
		if (colours.containsKey(pos) && colours.get(pos) == hash) return;
		colours.put(pos, hash);
		drawnLive.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(pos);
		String dimension = Dimensions.folder(level.dimension());
		int x = chunk.getPos().x(), z = chunk.getPos().z();
		output.later("paint chunk " + x + ", " + z, () -> paint(dimension, x, z, drawn));
	}

	/** On the writer thread. A chunk in a hidden area is painted blank, as if never seen. */
	private void paint(String dimension, int x, int z, ChunkColors.Drawn drawn) throws java.io.IOException {
		if (hidden.covers(dimension, x, z)) {
			tiles.erase(dimension, x, z);
			info.erase(dimension, x, z);
			signs.drawn(dimension, x, z, java.util.List.of());
			return;
		}
		tiles.paint(dimension, x, z, drawn.colors());
		info.paint(dimension, x, z, drawn.info());
		signs.drawn(dimension, x, z, drawn.signs());
	}

	Signs signs() {
		return signs;
	}

	HiddenAreas hidden() {
		return hidden;
	}

	/** Any thread: hides these areas from now on, and blanks what was already drawn in them. */
	void hide(HiddenAreas areas) {
		hidden = areas;
		output.later("blank the hidden areas", () -> {
			areas.forEachChunk((dimension, x, z) -> {
				tiles.erase(dimension, x, z);
				info.erase(dimension, x, z);
				signs.drawn(dimension, x, z, java.util.List.of());
			});
		});
		flush();
	}
}
