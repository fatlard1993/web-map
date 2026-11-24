package justfatlard.web_map;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which chunks need drawing, and drawing them a few at a time.
 *
 * <p>A chunk is queued when it loads and whenever anything in it changes ({@code LevelChunk.markUnsaved},
 * hooked), and the queue is drawn a budget's worth each tick, so neither a burst of loads nor a busy
 * base lands on one tick. A drawn chunk's colours are remembered while it stays loaded, and it is
 * painted only when they differ, so a furnace ticking away changes nothing on the map.
 *
 * <p>Server thread only.
 */
final class Drawing {
	private static final int DRAWS_PER_TICK = 24;

	private final Output output;
	private final TileStore tiles;
	private final Set<String> dimensions;
	private final Map<ServerLevel, LongLinkedOpenHashSet> queued = new HashMap<>();
	private final Map<ServerLevel, Long2IntMap> drawnColours = new HashMap<>();

	Drawing(Output output, Set<String> dimensions) {
		this.output = output;
		this.tiles = new TileStore(output.root.resolve("tiles"));
		this.dimensions = dimensions;
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

	/** Writes what was painted since the last flush; answers, on the writer thread, whether anything was. */
	void flush(Runnable whenWritten) {
		output.later("write map tiles", () -> {
			if (tiles.flush()) whenWritten.run();
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
		int[] colors = ChunkColors.of(level, chunk);
		int hash = Arrays.hashCode(colors);
		long pos = chunk.getPos().pack();
		Long2IntMap colours = drawnColours.computeIfAbsent(level, l -> new Long2IntOpenHashMap());
		if (colours.containsKey(pos) && colours.get(pos) == hash) return;
		colours.put(pos, hash);
		String dimension = Dimensions.folder(level.dimension());
		int x = chunk.getPos().x(), z = chunk.getPos().z();
		output.later("paint chunk " + x + ", " + z, () -> tiles.paint(dimension, x, z, colors));
	}
}
