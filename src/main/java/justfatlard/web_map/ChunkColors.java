package justfatlard.web_map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A chunk's 16x16 columns as the colours an in-game map gives them: the top block with a map colour,
 * lighter or darker by how its height compares with the column to the north, and water shaded by its
 * depth. Read on the server thread, so the chunk cannot change under it.
 *
 * <p>Under a roof, the Nether's, looking down from the top meets only the roof, so such a column is
 * read from a slice a little under the dimension's ceiling instead: from inside rock it steps down to
 * the first opening and draws the floor below it, and rock all the way down is drawn as rock.
 */
final class ChunkColors {
	private ChunkColors() {}

	/** How far under a roofed dimension's ceiling its slice is taken. */
	private static final int CEILING_GAP = 24;

	/** ARGB, row-major by z then x; 0 for a column with nothing to draw. */
	static int[] of(ServerLevel level, LevelChunk chunk) {
		int[] colors = new int[256];
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = level.getMinY();
		int slice = level.dimensionType().hasCeiling() ? minY + level.dimensionType().logicalHeight() - CEILING_GAP : Integer.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		int[] northHeights = northEdge(level, chunk, baseX, baseZ, minY, slice, cursor);

		for (int x = 0; x < 16; x++) {
			int previous = northHeights[x];
			for (int z = 0; z < 16; z++) {
				Column column = column(level, chunk, baseX + x, baseZ + z, minY, slice, cursor);
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
		return colors;
	}

	private record Column(MapColor color, int height, int waterDepth) {}

	private static Column column(ServerLevel level, LevelChunk chunk, int x, int z, int minY, int slice,
			BlockPos.MutableBlockPos cursor) {
		// getHeight is the top block's own y; the walk below starts one above it and steps down onto it
		int y = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1, slice);
		if (y == slice) {
			// Inside rock: down to the first opening, or the rock itself when there is none.
			int top = y - 1;
			while (y > minY && chunk.getBlockState(cursor.set(x, y - 1, z)).getMapColor(level, cursor) != MapColor.NONE) y--;
			if (y <= minY) {
				MapColor rock = chunk.getBlockState(cursor.set(x, top, z)).getMapColor(level, cursor);
				return new Column(rock, top, 0);
			}
		}
		BlockState state;
		MapColor color = MapColor.NONE;
		while (y > minY) {
			y--;
			state = chunk.getBlockState(cursor.set(x, y, z));
			color = state.getMapColor(level, cursor);
			if (color != MapColor.NONE) break;
		}
		if (color == MapColor.NONE) return new Column(MapColor.NONE, y, 0);

		int depth = 0;
		if (!chunk.getFluidState(cursor.set(x, y, z)).isEmpty()) {
			int below = y;
			while (below > minY && !chunk.getFluidState(cursor.set(x, below, z)).isEmpty()) {
				below--;
				depth++;
			}
			if (depth > 0 && below > minY) {
				MapColor floor = chunk.getBlockState(cursor.set(x, below, z)).getMapColor(level, cursor);
				if (floor == MapColor.NONE) depth = 0;
			}
		}
		return new Column(color, y, depth);
	}

	/** Heights along the row just north of this chunk, from its neighbour when that is loaded. */
	private static int[] northEdge(ServerLevel level, LevelChunk chunk, int baseX, int baseZ, int minY, int slice,
			BlockPos.MutableBlockPos cursor) {
		int[] heights = new int[16];
		LevelChunk north = level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z() - 1);
		for (int x = 0; x < 16; x++) {
			heights[x] = north != null
				? column(level, north, baseX + x, baseZ - 1, minY, slice, cursor).height
				: column(level, chunk, baseX + x, baseZ, minY, slice, cursor).height;
		}
		return heights;
	}
}
