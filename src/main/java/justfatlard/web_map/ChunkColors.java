package justfatlard.web_map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.List;

/**
 * A chunk's 16x16 columns as the colours an in-game map gives them: the top block with a map colour,
 * lighter or darker by how its height compares with the column to the north, and water shaded by its
 * depth.
 *
 * <p>Under a roof, the Nether's, looking down from the top meets only the roof, so such a column is
 * read from a slice a little under the dimension's ceiling instead: from inside rock it steps down to
 * the first opening and draws the floor below it, and rock all the way down is drawn as rock.
 *
 * <p>Each column is also described for the page to tell a viewer what is there: its top block, the
 * ground under it when that differs (the sand under water, the grass block under a flower), its biome
 * where the top block is, and the top block's height.
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

		Holder<Biome> biome(int x, int y, int z);
	}

	/**
	 * What is in each of a chunk's columns, indexed z * 16 + x: the top block, the ground under it or
	 * null when that is the top block itself, the biome there, and the top block's height. A column with
	 * nothing in it has a null top.
	 */
	record Info(Block[] tops, Block[] grounds, Holder<Biome>[] biomes, int[] heights) {
		@SuppressWarnings("unchecked")
		static Info empty() {
			return new Info(new Block[256], new Block[256], new Holder[256], new int[256]);
		}
	}

	/**
	 * A chunk's colours and what is in it, the heights along its south edge, which the chunk south of
	 * it is shaded against, and the map signs in it.
	 */
	record Drawn(int[] colors, Info info, int[] southEdge, List<Signs.Sign> signs, int[] caves) {
		Drawn withSigns(List<Signs.Sign> found) {
			return new Drawn(colors, info, southEdge, found, caves);
		}

		/** @param drawn the cave view's colours, or null where caves are not drawn */
		Drawn withCaves(int[] drawn) {
			return new Drawn(colors, info, southEdge, signs, drawn);
		}
	}

	/** Solid blocks over an opening before it counts as a cave: more than a roof, so a house is not one. */
	private static final int CAVE_ROOF = 3;
	/** Rock with no cave in it, in the cave view: dark, and opaque, so the surface does not show through. */
	private static final int ROCK = 0xFF262626;

	/** How far under the top block the ground is looked for, past water, leaves and plants. */
	private static final int GROUND_DEPTH = 128;

	static Drawn of(ServerLevel level, LevelChunk chunk, boolean caves) {
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		int slice = sliceOf(level);
		LevelChunk north = level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z() - 1);
		int[] northEdge = north == null ? null : edge(loaded(north), level, slice, level.getMinY(), baseX, baseZ - 1);
		Blocks blocks = loaded(chunk);
		return draw(blocks, level, slice, level.getMinY(), baseX, baseZ, northEdge)
			.withSigns(Signs.of(Dimensions.folder(level.dimension()), chunk))
			.withCaves(caves ? caves(blocks, level, level.getMinY(), baseX, baseZ) : null);
	}

	/**
	 * The cave view: under each column, the floor of the first opening below the surface with at
	 * least {@link #CAVE_ROOF} solid blocks over it, darker the deeper it is, or rock where there is
	 * none. Leaves and plants are passed through as if open, so the space under a tree is not a cave.
	 */
	static int[] caves(Blocks blocks, BlockGetter getter, int minY, int baseX, int baseZ) {
		int[] colors = new int[256];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int wx = baseX + x, wz = baseZ + z;
				colors[z * 16 + x] = ROCK;
				int roof = 0;
				for (int y = blocks.top(wx, wz); y > minY; y--) {
					if (!isOpen(blocks.at(wx, y, wz))) {
						roof++;
						continue;
					}
					if (roof >= CAVE_ROOF) {
						colors[z * 16 + x] = caveFloor(blocks, getter, wx, y, wz, minY, cursor);
						break;
					}
					roof = 0;
				}
			}
		}
		return colors;
	}

	private static int caveFloor(Blocks blocks, BlockGetter getter, int x, int y, int z, int minY, BlockPos.MutableBlockPos cursor) {
		for (int floor = y - 1; floor > minY; floor--) {
			BlockState state = blocks.at(x, floor, z);
			if (isOpen(state)) continue;
			MapColor color = colorOf(state, getter, cursor.set(x, floor, z));
			if (color == MapColor.NONE) return ROCK;
			int argb = color.calculateARGBColor(MapColor.Brightness.NORMAL);
			// From full colour at y 64 to under half at the bottom of the world
			double dim = 1 - Math.clamp((64 - floor) / 128.0, 0, 1) * 0.55;
			int r = (int) (((argb >> 16) & 0xFF) * dim), g = (int) (((argb >> 8) & 0xFF) * dim), b = (int) ((argb & 0xFF) * dim);
			return 0xFF000000 | r << 16 | g << 8 | b;
		}
		return ROCK;
	}

	/** Air, or something a body passes through that is not a fluid: plants, snow layers, leaves. */
	private static boolean isOpen(BlockState state) {
		return state.isAir() || state.is(BlockTags.LEAVES) || (state.canBeReplaced() && state.getFluidState().isEmpty());
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
		Info info = Info.empty();
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
				describe(info, z * 16 + x, blocks, baseX + x, column.height, baseZ + z, minY);

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
		return new Drawn(colors, info, southEdge, List.of(), null);
	}

	private static void describe(Info info, int index, Blocks blocks, int x, int y, int z, int minY) {
		BlockState topState = blocks.at(x, y, z);
		Block top = topState.getBlock();
		info.tops[index] = top;
		info.heights[index] = y;
		info.biomes[index] = blocks.biome(x, y, z);
		if (!isCover(topState)) return;
		for (int below = y - 1; below >= Math.max(minY, y - GROUND_DEPTH); below--) {
			BlockState state = blocks.at(x, below, z);
			if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES) || state.canBeReplaced()) continue;
			if (state.getBlock() != top) info.grounds[index] = state.getBlock();
			return;
		}
	}

	/** Whether a top block lies over the ground rather than being it: water, leaves, plants, snow. */
	private static boolean isCover(BlockState top) {
		return !top.getFluidState().isEmpty() || top.is(BlockTags.LEAVES) || top.canBeReplaced();
	}

	/** The heights of one row of 16 columns, from x = baseX. */
	private static int[] edge(Blocks blocks, BlockGetter getter, int slice, int minY, int baseX, int z) {
		int[] heights = new int[16];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; x++) heights[x] = column(blocks, getter, baseX + x, z, minY, slice, cursor).height;
		return heights;
	}

	/** A copy of a loaded chunk's blocks, to read off the server thread while the chunk goes on changing. */
	static Blocks copyOf(LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		LevelChunkSection[] copies = new LevelChunkSection[sections.length];
		for (int i = 0; i < sections.length; i++) if (sections[i] != null) copies[i] = sections[i].copy();
		int[] tops = new int[256];
		for (int i = 0; i < 256; i++) tops[i] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, i & 15, i >> 4);
		int minSection = chunk.getMinSectionY();
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		return new Blocks() {
			public int top(int x, int z) {
				return tops[(z & 15) * 16 + (x & 15)];
			}

			public BlockState at(int x, int y, int z) {
				int index = (y >> 4) - minSection;
				if (index < 0 || index >= copies.length || copies[index] == null) return air;
				return copies[index].getBlockState(x & 15, y & 15, z & 15);
			}

			public Holder<Biome> biome(int x, int y, int z) {
				int index = Math.clamp((y >> 4) - minSection, 0, copies.length - 1);
				return copies[index].getNoiseBiome(QuartPos.fromBlock(x) & 3, QuartPos.fromBlock(y) & 3, QuartPos.fromBlock(z) & 3);
			}
		};
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

			public Holder<Biome> biome(int x, int y, int z) {
				return chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
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
