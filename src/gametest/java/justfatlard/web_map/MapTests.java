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
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()));

		drawing.loaded(level, chunk);
		drawing.tick();
		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		int seen = ChunkColors.of(level, chunk)[(at.getZ() & 15) * 16 + (at.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.GOLD_BLOCK, level, at, seen), Component.literal("the change, gold, read from the chunk, not " + Integer.toHexString(seen)));
		drawing.changed(level, chunk.getPos());
		drawing.tick();
		drawing.flush(() -> { });
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

	private static boolean isColourOf(Block block, ServerLevel level, BlockPos at, int pixel) {
		MapColor colour = block.defaultBlockState().getMapColor(level, at);
		for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
			if ((0xFF000000 | colour.calculateARGBColor(brightness)) == pixel) return true;
		}
		return false;
	}
}
