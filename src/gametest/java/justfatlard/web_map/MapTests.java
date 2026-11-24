package justfatlard.web_map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
		int pixel = ChunkColors.of(level, level.getChunkAt(at), false).colors()[(at.getZ() & 15) * 16 + (at.getX() & 15)];
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
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), Set.of(level.dimension().identifier().toString()), root, extents -> { });

		drawing.loaded(level, chunk);
		drawing.tick();
		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		int seen = ChunkColors.of(level, chunk, false).colors()[(at.getZ() & 15) * 16 + (at.getX() & 15)];
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

		// The info picture names the gold, its biome and its height, through the palette
		Path info = root.resolve("info").resolve(Dimensions.folder(level.dimension()))
			.resolve(Math.floorDiv(at.getX(), 512) + "_" + Math.floorDiv(at.getZ(), 512) + ".png");
		BufferedImage infoImage = ImageIO.read(info.toFile());
		int first = infoImage.getRGB(Math.floorMod(at.getX(), 512) * 2, Math.floorMod(at.getZ(), 512)) & 0xFFFFFF;
		int second = infoImage.getRGB(Math.floorMod(at.getX(), 512) * 2 + 1, Math.floorMod(at.getZ(), 512)) & 0xFFFFFF;
		var palette = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("info").resolve("palette.json"))).getAsJsonObject();
		String top = palette.getAsJsonArray("blocks").get((first >> 12) - 1).getAsString();
		String biome = palette.getAsJsonArray("biomes").get((first & 0xFFF) - 1).getAsString();
		String expectedBiome = level.getBiome(at).unwrapKey().orElseThrow().identifier().toString();
		helper.assertTrue(top.equals("minecraft:gold_block"), Component.literal("the info's top block is the gold, not " + top));
		helper.assertTrue(biome.equals(expectedBiome), Component.literal("the info's biome is " + expectedBiome + ", not " + biome));
		helper.assertTrue((second & 0xFFF) - 2048 == at.getY(), Component.literal("the info's height is " + at.getY() + ", not " + ((second & 0xFFF) - 2048)));
		helper.assertTrue(second >> 12 == 0, Component.literal("gold is its own ground, so none is named"));
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

	@GameTest
	public void eachPlayersLastThreeDeathsAreKeptAcrossARestart(GameTestHelper helper) throws Exception {
		Path file = Files.createTempDirectory("web-map-test").resolve("deaths.json");
		DeathLog log = new DeathLog(file);
		String steve = java.util.UUID.randomUUID().toString(), alex = java.util.UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		for (int i = 0; i < 4; i++) log.died(new DeathLog.Death(steve, "Steve", "minecraft:overworld", i, 64, 0, null, now + i));
		log.died(new DeathLog.Death(alex, "Alex", "minecraft:the_nether", 9, 64, 9, "Alex burned", now));
		// One past its days is forgotten
		log.died(new DeathLog.Death(alex, "Alex", "minecraft:overworld", 1, 1, 1, null, now - java.util.concurrent.TimeUnit.DAYS.toMillis(31)));
		log.write(log.snapshot());

		var kept = new DeathLog(file).all(now);
		var steves = kept.stream().filter(d -> d.name().equals("Steve")).map(DeathLog.Death::x).toList();
		helper.assertTrue(steves.equals(java.util.List.of(1, 2, 3)), Component.literal("Steve's last three, not " + steves));
		var alexes = kept.stream().filter(d -> d.name().equals("Alex")).toList();
		helper.assertTrue(alexes.size() == 1 && alexes.get(0).dimension().equals("minecraft_the_nether"),
			Component.literal("Alex's recent death alone, in the Nether's folder, not " + alexes));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aSavedChunkIsDrawnAsItWasSaved(GameTestHelper helper) {
		helper.setBlock(HIGH_UP, Blocks.EMERALD_BLOCK);
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(HIGH_UP);
		level.getChunkSource().save(true);
		ChunkColors.Drawn saved = Backfill.drawSaved(level, net.minecraft.world.level.ChunkPos.containing(at), null, true);
		helper.assertTrue(saved != null, Component.literal("the saved chunk was read"));
		int pixel = saved.colors()[(at.getZ() & 15) * 16 + (at.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.EMERALD_BLOCK, level, at, pixel),
			Component.literal("the saved chunk's top block, emerald, not " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aHiddenAreaIsNeverDrawnAndCoversItsSpot(GameTestHelper helper) throws Exception {
		ServerLevel level = helper.getLevel();
		String id = level.dimension().identifier().toString(), folder = Dimensions.folder(level.dimension());
		BlockPos at = helper.absolutePos(HIGH_UP);
		var area = new HiddenAreas.Area("base", id, at.getX(), at.getZ(), 40);
		HiddenAreas areas = new HiddenAreas(java.util.List.of(area), 42L);
		for (int dx = -40; dx <= 40; dx += 2) {
			for (int dz = -40; dz <= 40; dz += 2) {
				if (dx * dx + dz * dz > 40 * 40) continue;
				helper.assertTrue(areas.coversBlock(folder, at.getX() + dx, at.getZ() + dz),
					Component.literal("everything within the radius is hidden, but " + dx + ", " + dz + " from the spot is not"));
			}
		}
		helper.assertFalse(areas.coversBlock(folder, at.getX() + 2000, at.getZ()), Component.literal("far away is not hidden"));
		helper.assertFalse(areas.coversBlock("elsewhere", at.getX(), at.getZ()), Component.literal("another dimension is not hidden"));
		HiddenAreas again = new HiddenAreas(java.util.List.of(area), 42L);
		for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
			int cx = (at.getX() >> 4) + x, cz = (at.getZ() >> 4) + z;
			helper.assertTrue(areas.covers(folder, cx, cz) == again.covers(folder, cx, cz), Component.literal("the shape is the same every time"));
		}

		// A chunk already drawn is blanked when hidden, and drawing it again keeps it blank
		LevelChunk chunk = level.getChunkAt(at);
		Path root = Files.createTempDirectory("web-map-test");
		Output output = new Output(root);
		Drawing drawing = new Drawing(output, Set.of(id), Set.of(id), root, extents -> { });
		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		drawing.loaded(level, chunk);
		drawing.tick();
		drawing.flushNow().get(30, java.util.concurrent.TimeUnit.SECONDS);
		drawing.hide(areas);
		helper.setBlock(HIGH_UP, Blocks.DIAMOND_BLOCK);
		drawing.changed(level, chunk.getPos());
		drawing.tick();
		drawing.flushNow().get(30, java.util.concurrent.TimeUnit.SECONDS);
		output.close(30);
		BufferedImage image = ImageIO.read(tile(root, level, at).toFile());
		int pixel = image.getRGB(Math.floorMod(at.getX(), 512), Math.floorMod(at.getZ(), 512));
		helper.assertTrue(pixel >>> 24 == 0, Component.literal("the hidden chunk is blank, as if never seen, not " + Integer.toHexString(pixel)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void theCaveViewShowsACavesFloorButNotAHouses(GameTestHelper helper) throws Exception {
		// A cave: five solid blocks over a two-high opening, floored with gold
		for (int y = 5; y <= 9; y++) helper.setBlock(new BlockPos(3, y, 3), Blocks.STONE);
		helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);
		helper.setBlock(new BlockPos(3, 3, 3), Blocks.AIR);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.GOLD_BLOCK);
		// A house: a one-block roof over a room floored with diamond
		helper.setBlock(new BlockPos(5, 9, 5), Blocks.STONE);
		for (int y = 4; y <= 8; y++) helper.setBlock(new BlockPos(5, y, 5), Blocks.AIR);
		helper.setBlock(new BlockPos(5, 3, 5), Blocks.DIAMOND_BLOCK);
		ServerLevel level = helper.getLevel();
		BlockPos cave = helper.absolutePos(new BlockPos(3, 2, 3)), house = helper.absolutePos(new BlockPos(5, 3, 5));
		int caveColour = ChunkColors.of(level, level.getChunkAt(cave), true).caves()[(cave.getZ() & 15) * 16 + (cave.getX() & 15)];
		int houseColour = ChunkColors.of(level, level.getChunkAt(house), true).caves()[(house.getZ() & 15) * 16 + (house.getX() & 15)];
		int gold = Blocks.GOLD_BLOCK.defaultBlockState().getMapColor(level, cave).calculateARGBColor(MapColor.Brightness.NORMAL);
		int diamond = Blocks.DIAMOND_BLOCK.defaultBlockState().getMapColor(level, house).calculateARGBColor(MapColor.Brightness.NORMAL);
		helper.assertTrue(sameHue(caveColour, gold), Component.literal("the cave's gold floor, dimmed, not " + Integer.toHexString(caveColour)));
		helper.assertFalse(sameHue(houseColour, diamond), Component.literal("a house is not a cave, so its diamond floor is not shown"));

		// Drawn live, the caves are read off the server thread from a copy, and reach their own tiles
		Path root = Files.createTempDirectory("web-map-test");
		Output output = new Output(root);
		String id = level.dimension().identifier().toString();
		Drawing drawing = new Drawing(output, Set.of(id), Set.of(id), root, extents -> { });
		drawing.loaded(level, level.getChunkAt(cave));
		drawing.tick();
		drawing.flushNow().get(30, java.util.concurrent.TimeUnit.SECONDS);
		output.close(30);
		Path caveTile = root.resolve("tiles").resolve(Dimensions.folder(level.dimension()) + ".caves").resolve("4")
			.resolve(Math.floorDiv(cave.getX(), 512) + "_" + Math.floorDiv(cave.getZ(), 512) + ".png");
		helper.assertTrue(Files.exists(caveTile), Component.literal("the cave tile was written"));
		int tiled = ImageIO.read(caveTile.toFile()).getRGB(Math.floorMod(cave.getX(), 512), Math.floorMod(cave.getZ(), 512));
		helper.assertTrue(sameHue(tiled, gold), Component.literal("the cave tile shows the gold floor, not " + Integer.toHexString(tiled)));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void theGroundUnderASkyBuildIsDrawnButNotUnderTreesOrCaveRoofs(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// A platform of quartz far up, over a gold floor
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.GOLD_BLOCK);
		helper.setBlock(new BlockPos(2, 30, 2), Blocks.QUARTZ_BLOCK);
		helper.setBlock(new BlockPos(2, 31, 2), Blocks.QUARTZ_BLOCK);
		// Leaves as high, which are a canopy, and stone, which is a cave's roof
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.GOLD_BLOCK);
		helper.setBlock(new BlockPos(4, 30, 4), Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.GOLD_BLOCK);
		helper.setBlock(new BlockPos(6, 30, 6), Blocks.STONE);
		BlockPos build = helper.absolutePos(new BlockPos(2, 1, 2)), leaves = helper.absolutePos(new BlockPos(4, 1, 4)), roof = helper.absolutePos(new BlockPos(6, 1, 6));
		int gold = Blocks.GOLD_BLOCK.defaultBlockState().getMapColor(level, build).calculateARGBColor(MapColor.Brightness.NORMAL);

		ChunkColors.Drawn drawn = ChunkColors.of(level, level.getChunkAt(build), false);
		int under = drawn.under() == null ? 0 : drawn.under()[(build.getZ() & 15) * 16 + (build.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.GOLD_BLOCK, level, build, under), Component.literal("under the quartz, the gold floor, not " + Integer.toHexString(under)));
		int surface = drawn.colors()[(build.getZ() & 15) * 16 + (build.getX() & 15)];
		helper.assertTrue(isColourOf(Blocks.QUARTZ_BLOCK, level, build, surface), Component.literal("the surface still shows the quartz"));
		for (BlockPos at : new BlockPos[] {leaves, roof}) {
			ChunkColors.Drawn here = ChunkColors.of(level, level.getChunkAt(at), false);
			int seen = here.under() == null ? 0 : here.under()[(at.getZ() & 15) * 16 + (at.getX() & 15)];
			helper.assertTrue(seen == 0, Component.literal("leaves and stone overhead are not sky builds, but " + at + " has " + Integer.toHexString(seen)));
		}
		helper.succeed();
	}

	/** Whether one colour is the other dimmed: their channels in the same proportions, near enough. */
	private static boolean sameHue(int seen, int full) {
		int r = (seen >> 16) & 0xFF, g = (seen >> 8) & 0xFF, b = seen & 0xFF;
		int fr = (full >> 16) & 0xFF, fg = (full >> 8) & 0xFF, fb = full & 0xFF;
		if (r == 0 && g == 0 && b == 0) return false;
		double scale = (double) (r + g + b) / (fr + fg + fb);
		return scale > 0.3 && scale <= 1.01 && Math.abs(r - fr * scale) < 6 && Math.abs(g - fg * scale) < 6 && Math.abs(b - fb * scale) < 6;
	}

	@GameTest
	public void positionsAreShownTheDelayLate(GameTestHelper helper) {
		Sightings<String> sightings = new Sightings<>();
		helper.assertTrue(sightings.see(0, "a", 30_000) == null, Component.literal("nothing is shown until something is the delay old"));
		helper.assertTrue(sightings.see(5_000, "b", 30_000) == null, Component.literal("still nothing at 5 s"));
		for (long t = 10_000; t < 30_000; t += 5_000) sightings.see(t, "x" + t, 30_000);
		String at30 = sightings.see(30_000, "now", 30_000);
		helper.assertTrue("a".equals(at30), Component.literal("at 30 s, what was seen at 0, not " + at30));
		String at35 = sightings.see(35_000, "later", 30_000);
		helper.assertTrue("b".equals(at35), Component.literal("at 35 s, what was seen at 5, not " + at35));
		String undelayed = new Sightings<String>().see(1, "here", 0);
		helper.assertTrue("here".equals(undelayed), Component.literal("with no delay, what is seen now"));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aPlayerDeepUnderTheSurfaceIsUnderground(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos column = helper.absolutePos(new BlockPos(1, 0, 1));
		int surface = helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
		player.setPos(column.getX() + 0.5, surface, column.getZ() + 0.5);
		helper.assertFalse(Layers.underground(player), Component.literal("standing on the surface is not underground"));
		player.setPos(column.getX() + 0.5, surface - 5, column.getZ() + 0.5);
		helper.assertFalse(Layers.underground(player), Component.literal("a house's height under its roof is not underground"));
		player.setPos(column.getX() + 0.5, surface - 20, column.getZ() + 0.5);
		helper.assertTrue(Layers.underground(player), Component.literal("20 blocks under the surface is underground"));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void aMapSignIsAMarkerLiveAndSaved(GameTestHelper helper) {
		BlockPos signAt = new BlockPos(2, 2, 2);
		helper.setBlock(signAt, Blocks.OAK_SIGN);
		var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) helper.getBlockEntity(signAt, net.minecraft.world.level.block.entity.SignBlockEntity.class);
		sign.setText(lines(" [MAP] ", "Ben's", "mine", ""), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
		BlockPos plainAt = new BlockPos(3, 2, 2);
		helper.setBlock(plainAt, Blocks.OAK_SIGN);
		((net.minecraft.world.level.block.entity.SignBlockEntity) helper.getBlockEntity(plainAt, net.minecraft.world.level.block.entity.SignBlockEntity.class))
			.setText(lines("just a sign", "", "", ""), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(signAt);
		String dimension = Dimensions.folder(level.dimension());

		var live = Marks.of(dimension, level.getChunkAt(at)).stream().filter(s -> s.x() == at.getX() && s.z() == at.getZ()).toList();
		helper.assertTrue(live.size() == 1 && live.get(0).name().equals("Ben's mine") && live.get(0).y() == at.getY(),
			Component.literal("the live chunk's map sign, named by its other lines, not " + live));
		helper.assertTrue(Marks.of(dimension, level.getChunkAt(helper.absolutePos(plainAt))).stream().noneMatch(s -> s.x() == helper.absolutePos(plainAt).getX()),
			Component.literal("a sign without [map] first is not a marker"));

		level.getChunkSource().save(true);
		ChunkColors.Drawn saved = Backfill.drawSaved(level, net.minecraft.world.level.ChunkPos.containing(at), null, true);
		var fromSave = saved == null ? java.util.List.<Marks.Mark>of() : saved.marks().stream().filter(s -> s.x() == at.getX() && s.z() == at.getZ()).toList();
		helper.assertTrue(fromSave.size() == 1 && fromSave.get(0).name().equals("Ben's mine"),
			Component.literal("the saved chunk's map sign, read from its block entity's NBT, not " + fromSave));
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void beaconsAreMarkersWithTheirNameEffectsAndLevels(GameTestHelper helper) {
		BlockPos beaconAt = new BlockPos(2, 2, 2);
		helper.setBlock(beaconAt, Blocks.BEACON);
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(beaconAt);
		String dimension = Dimensions.folder(level.dimension());
		var live = Marks.of(dimension, level.getChunkAt(at)).stream().filter(m -> m.x() == at.getX() && m.z() == at.getZ()).toList();
		helper.assertTrue(live.size() == 1 && live.get(0).kind().equals(Marks.BEACON) && live.get(0).levels() == 0 && live.get(0).name() == null,
			Component.literal("a beacon placed is a mark, unlit and unnamed, not " + live));

		var tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("id", "minecraft:beacon");
		tag.putInt("x", 10);
		tag.putInt("y", 70);
		tag.putInt("z", -20);
		tag.putString("primary_effect", "minecraft:speed");
		tag.putString("secondary_effect", "minecraft:regeneration");
		tag.putInt("Levels", 4);
		tag.putString("CustomName", "Spawn light");
		var saved = Marks.saved(dimension, java.util.List.of(tag), level.registryAccess());
		helper.assertTrue(saved.size() == 1, Component.literal("the saved beacon is a mark"));
		Marks.Mark beacon = saved.get(0);
		helper.assertTrue(beacon.levels() == 4 && beacon.effects().equals(java.util.List.of("minecraft:speed", "minecraft:regeneration"))
				&& "Spawn light".equals(beacon.name()) && beacon.x() == 10 && beacon.z() == -20,
			Component.literal("the saved beacon's name, effects, levels and place, not " + beacon));
		helper.succeed();
	}

	private static net.minecraft.world.level.block.entity.SignText lines(String... text) {
		java.util.List<Component> lines = java.util.Arrays.stream(text).map(line -> (Component) Component.literal(line)).toList();
		return new net.minecraft.world.level.block.entity.SignText(lines, lines, net.minecraft.world.item.DyeColor.BLACK, false);
	}

	@GameTest(skyAccess = true)
	public void aSavedCopyNeverPaintsOverALiveDrawing(GameTestHelper helper) throws Exception {
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(HIGH_UP);
		LevelChunk chunk = level.getChunkAt(at);
		Path root = Files.createTempDirectory("web-map-test");
		Output output = new Output(root);
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), Set.of(level.dimension().identifier().toString()), root, extents -> { });

		helper.setBlock(HIGH_UP, Blocks.GOLD_BLOCK);
		drawing.loaded(level, chunk);
		drawing.tick();
		int[] stale = new int[256];
		java.util.Arrays.fill(stale, 0xFFFF0000);
		drawing.paintSaved(level, Dimensions.folder(level.dimension()), chunk.getPos().x(), chunk.getPos().z(),
			new ChunkColors.Drawn(stale, ChunkColors.Info.empty(), new int[16], java.util.List.of(), null, null));
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
		Drawing drawing = new Drawing(output, Set.of(level.dimension().identifier().toString()), Set.of(level.dimension().identifier().toString()), root, extents -> { });
		drawing.paintSaved(level, Dimensions.folder(level.dimension()), 1000000, 1000000,
			new ChunkColors.Drawn(new int[256], ChunkColors.Info.empty(), new int[16], java.util.List.of(), null, null));

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
