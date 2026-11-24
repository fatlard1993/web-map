package justfatlard.web_map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A chunk's 16x16 columns as the colours an in-game map gives them: the top block with a map colour,
 * lighter or darker by how its height compares with the column to the north, and water shaded by its
 * depth.
 *
 * <p>Under a roof, the Nether's, looking down from the top meets only the roof, so such a column is
 * read from a slice a little under the dimension's ceiling instead: from inside rock it steps down to
 * the first opening and draws the floor below it, and rock all the way down is drawn as rock.
 *
 * <p>The same reading serves a loaded chunk, on the server thread, and a saved one parsed off it.
 */
final class ChunkColors {
	private ChunkColors() {}

	/** How far under a roofed dimension's ceiling its slice is taken. */
	private static final int CEILING_GAP = 24;

	/** The blocks of one chunk, by world position. */
	interface Blocks {
		/** The y of the column's highest block. */
		int top(int x, int z);

		BlockState at(int x, int y, int z);
	}

	/** A chunk's colours, and the heights along its south edge, which the chunk south of it is shaded against. */
	record Drawn(int[] colors, int[] southEdge) {}

	static int[] of(ServerLevel level, LevelChunk chunk) {
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		int slice = sliceOf(level);
		LevelChunk north = level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z() - 1);
		int[] northEdge = north == null ? null : edge(loaded(north), level, slice, level.getMinY(), baseX, baseZ - 1);
		return draw(loaded(chunk), level, slice, level.getMinY(), baseX, baseZ, northEdge).colors();
	}

	static int sliceOf(ServerLevel level) {
		return level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() - CEILING_GAP : Integer.MAX_VALUE;
	}

	/**
	 * @param getter    what a block's map colour is asked of; a saved chunk has no world to ask
	 * @param northEdge heights along the row just north, or null to shade the first row flat
	 */
	static Drawn draw(Blocks blocks, BlockGetter getter, int slice, int minY, int baseX, int baseZ, int[] northEdge) {
		int[] colors = new int[256];
		int[] southEdge = new int[16];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int x = 0; x < 16; x++) {
			int previous = Integer.MIN_VALUE;
			if (northEdge != null) previous = northEdge[x];
			for (int z = 0; z < 16; z++) {
				Column column = column(blocks, getter, baseX + x, baseZ + z, minY, slice, cursor);
				if (previous == Integer.MIN_VALUE) previous = column.height;
				if (z == 15) southEdge[x] = column.height;
				if (column.color == MapColor.NONE) {
					previous = column.height;
					continue;
				}

				MapColor.Brightness brightness;
				if (column.waterDepth > 0) {
					double shade = column.waterDepth * 0.1 + ((x + z) & 1) * 0.2;
					brightness = shade < 0.5 ? MapColor.Brightness.HIGH
						: shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				} else {
					double slope = (column.height - previous) * 0.8 + (((x + z) & 1) - 0.5) * 0.4;
					brightness = slope > 0.6 ? MapColor.Brightness.HIGH
						: slope < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				}
				colors[z * 16 + x] = 0xFF000000 | column.color.calculateARGBColor(brightness);
				previous = column.height;
			}
		}
		return new Drawn(colors, southEdge);
	}

	/** The heights of one row of 16 columns, from x = baseX. */
	private static int[] edge(Blocks blocks, BlockGetter getter, int slice, int minY, int baseX, int z) {
		int[] heights = new int[16];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; x++) heights[x] = column(blocks, getter, baseX + x, z, minY, slice, cursor).height;
		return heights;
	}

	private static Blocks loaded(LevelChunk chunk) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		return new Blocks() {
			public int top(int x, int z) {
				return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
			}

			public BlockState at(int x, int y, int z) {
				return chunk.getBlockState(cursor.set(x, y, z));
			}
		};
	}

	private record Column(MapColor color, int height, int waterDepth) {}

	private static Column column(Blocks blocks, BlockGetter getter, int x, int z, int minY, int slice,
			BlockPos.MutableBlockPos cursor) {
		// The walk below starts one above the top block and steps down onto it
		int y = Math.min(blocks.top(x, z) + 1, slice);
		if (y == slice) {
			// Inside rock: down to the first opening, or the rock itself when there is none.
			int top = y - 1;
			while (y > minY && colorOf(blocks.at(x, y - 1, z), getter, cursor.set(x, y - 1, z)) != MapColor.NONE) y--;
			if (y <= minY) return new Column(colorOf(blocks.at(x, top, z), getter, cursor.set(x, top, z)), top, 0);
		}
		MapColor color = MapColor.NONE;
		while (y > minY) {
			y--;
			color = colorOf(blocks.at(x, y, z), getter, cursor.set(x, y, z));
			if (color != MapColor.NONE) break;
		}
		if (color == MapColor.NONE) return new Column(MapColor.NONE, y, 0);

		int depth = 0;
		if (!blocks.at(x, y, z).getFluidState().isEmpty()) {
			int below = y;
			while (below > minY && !blocks.at(x, below, z).getFluidState().isEmpty()) {
				below--;
				depth++;
			}
			if (depth > 0 && below > minY && colorOf(blocks.at(x, below, z), getter, cursor.set(x, below, z)) == MapColor.NONE) depth = 0;
		}
		return new Column(color, y, depth);
	}

	private static MapColor colorOf(BlockState state, BlockGetter getter, BlockPos pos) {
		return state.getMapColor(getter, pos);
	}
}
