package justfatlard.web_map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** In the mod's own package, so the tests reach the drawing code without widening it. */
public class MapTests {
	private static final BlockPos HIGH_UP = new BlockPos(1, 4, 1);

	@GameTest(skyAccess = true)
	public void drawsTheTopBlock(GameTestHelper helper) {
		helper.setBlock(HIGH_UP, Blocks.LAPIS_BLOCK);
		BlockPos at = helper.absolutePos(HIGH_UP);
		ServerLevel level = helper.getLevel();
		int pixel = ChunkColors.of(level, level.getChunkAt(at))[(at.getZ() & 15) * 16 + (at.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.LAPIS_BLOCK, level, at, pixel),
			Component.literal("the top block's colour, lapis, not " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aChangeIsDrawnIntoItsTile(GameTestHelper helper) throws Exception {
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(HIGH_UP);
		LevelChunk chunk = level.getChunkAt(at);
		Path root = Files.createTempDirectory("web-map-test");
		Output output = new Output(root);
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), extents -> { });

		drawing.loaded(level, chunk);
		drawing.tick();
		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		int seen = ChunkColors.of(level, chunk)[(at.getZ() & 15) * 16 + (at.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.GOLD_BLOCK, level, at, seen), Component.literal("the change, gold, read from the chunk, not " + Integer.toHexString(seen)));
		drawing.changed(level, chunk.getPos());
		drawing.tick();
		drawing.flush();
		// Waits, in wall-clock time, for the writer: a test server ticks as fast as it can, so a wait
		// counted in ticks can end before a cold writer has written its first image
		output.close(30);

		Path tile = root.resolve("tiles").resolve(Dimensions.folder(level.dimension())).resolve("4")
			.resolve(Math.floorDiv(at.getX(), 512) + "_" + Math.floorDiv(at.getZ(), 512) + ".png");
		helper.assertTrue(Files.exists(tile), Component.literal("the tile was written"));
		BufferedImage image = ImageIO.read(tile.toFile());
		int pixel = image.getRGB(Math.floorMod(at.getX(), 512), Math.floorMod(at.getZ(), 512));
		helper.assertTrue(isColourOf(Blocks.GOLD_BLOCK, level, at, pixel),
			Component.literal("the tile shows the gold placed, not " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest
	public void theTilesReachDownFarEnoughToShowEverythingDrawn(GameTestHelper helper) throws Exception {
		Path root = Files.createTempDirectory("web-map-test");
		TileStore tiles = new TileStore(root);
		int[] colours = new int[256];
		java.util.Arrays.fill(colours, 0xff00ff00);
		// A whole region: at the floor it is an eighth of a pixel, faint but there; a chunk would round away
		for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) tiles.paint("d", x, z, colours);
		tiles.flush();
		helper.assertTrue(tiles.floor("d") == 0, Component.literal("a map around the origin stops at zoom 0, not " + tiles.floor("d")));

		// A region two million blocks east: 512 pixels at zoom 4, a tile of zoom -8 is 2 097 152 blocks across
		tiles.paint("d", 2_000_000 / 16, 0, colours);
		tiles.flush();
		int floor = tiles.floor("d");
		helper.assertTrue(floor == -8, Component.literal("the floor is the first zoom whose tiles around the origin hold both, -8, not " + floor));
		BufferedImage deepest = ImageIO.read(root.resolve("d").resolve(Integer.toString(floor)).resolve("0_0.png").toFile());
		helper.assertTrue((deepest.getRGB(0, 0) >>> 24) > 0, Component.literal("the floor's tile shows the origin, drawn before the floor deepened"));
		helper.assertTrue(tiles.extentsJson().equals("{\"d\":{\"x0\":0,\"z0\":0,\"x1\":2000384,\"z1\":512,\"floor\":-8}}"),
			Component.literal("the extents the page fits to, not " + tiles.extentsJson()));

		// Read back from disk alone, as after a restart
		TileStore again = new TileStore(root);
		again.settle();
		helper.assertTrue(again.floor("d") == -8, Component.literal("the floor is found again from the tiles on disk, not " + again.floor("d")));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aSavedChunkIsDrawnAsItWasSaved(GameTestHelper helper) {
		helper.setBlock(HIGH_UP, Blocks.EMERALD_BLOCK);
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(HIGH_UP);
		level.getChunkSource().save(true);
		ChunkColors.Drawn saved = Backfill.drawSaved(level, net.minecraft.world.level.ChunkPos.containing(at), null);
		helper.assertTrue(saved != null, Component.literal("the saved chunk was read"));
		int pixel = saved.colors()[(at.getZ() & 15) * 16 + (at.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.EMERALD_BLOCK, level, at, pixel),
			Component.literal("the saved chunk's top block, emerald, not " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aSavedCopyNeverPaintsOverALiveDrawing(GameTestHelper helper) throws Exception {
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(HIGH_UP);
		LevelChunk chunk = level.getChunkAt(at);
		Path root = Files.createTempDirectory("web-map-test");
		Output output = new Output(root);
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), extents -> { });

		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		drawing.loaded(level, chunk);
		drawing.tick();
		int[] stale = new int[256];
		java.util.Arrays.fill(stale, 0xFFFF0000);
		drawing.paintSaved(level, Dimensions.folder(level.dimension()), chunk.getPos().x(), chunk.getPos().z(), stale);
		drawing.flushNow().get(30, java.util.concurrent.TimeUnit.SECONDS);
		output.close(30);

		BufferedImage image = ImageIO.read(tile(root, level, at).toFile());
		int pixel = image.getRGB(Math.floorMod(at.getX(), 512), Math.floorMod(at.getZ(), 512));
		helper.assertTrue(isColourOf(Blocks.GOLD_BLOCK, level, at, pixel),
			Component.literal("the live gold, not the saved copy's " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aFlushThatCannotWriteSaysSo(GameTestHelper helper) throws Exception {
		ServerLevel level = helper.getLevel();
		Path root = Files.createTempDirectory("web-map-test");
		// A file where the tiles folder goes: nothing can be written under it, as on a full disk
		Files.writeString(root.resolve("tiles"), "");
		Output output = new Output(root);
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), extents -> { });
		drawing.paintSaved(level, Dimensions.folder(level.dimension()), 1000000, 1000000, new int[256]);

		boolean failed = false;
		try {
			drawing.flushNow().get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException expected) {
			failed = true;
		}
		output.close(30);
		helper.assertTrue(failed, Component.literal("a flush that could not write reported it"));
		helper.succeed();
	}

	private static Path tile(Path root, ServerLevel level, BlockPos at) {
		return root.resolve("tiles").resolve(Dimensions.folder(level.dimension())).resolve("4")
			.resolve(Math.floorDiv(at.getX(), 512) + "_" + Math.floorDiv(at.getZ(), 512) + ".png");
	}

	private static boolean isColourOf(Block block, ServerLevel level, BlockPos at, int pixel) {
		MapColor colour = block.defaultBlockState().getMapColor(level, at);
		for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
			if ((0xFF000000 | colour.calculateARGBColor(brightness)) == pixel) return true;
		}
		return false;
	}
}
