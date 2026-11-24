package justfatlard.web_map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.util.List;

/** Wiring: the server's events to {@link Drawing} and {@link Layers}, and the page's files into place. */
public class Main implements DedicatedServerModInitializer {
	public static final String MOD_ID = "web-map-justfatlard";

	private static final int FLUSH_TICKS = 100;
	/** The page and the files it loads, copied out of the jar at every start so an update reaches it. */
	private static final List<String> PAGE = List.of(
		"index.html", "map.js", "map.css", "leaflet/leaflet.js", "leaflet/leaflet.css", "leaflet/LICENSE",
		"leaflet/images/layers.png", "leaflet/images/layers-2x.png");

	private static Output output;
	private static Drawing drawing;
	private static Layers layers;
	private static Backfill backfill;
	private static WebMapConfig config;
	private static int ticks;
	private static long seed;

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTING.register(Main::start);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (layers == null) return;
			layers.started(server);
			if (config.backfill) {
				List<ServerLevel> drawn = new java.util.ArrayList<>();
				for (ServerLevel level : server.getAllLevels()) if (drawing.draws(level)) drawn.add(level);
				backfill = new Backfill(server, drawing, drawn);
				backfill.start();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (drawing != null) drawing.loaded(level, chunk);
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (drawing != null) drawing.unloaded(level, chunk);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (layers != null) layers.entityUnloaded(entity);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (layers != null && entity instanceof ServerPlayer player) layers.died(player, source);
		});
		ServerTickEvents.END_SERVER_TICK.register(Main::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(Commands.literal("webmap")
			.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
			.then(Commands.literal("hide")
				.then(Commands.argument("name", StringArgumentType.string())
					.then(Commands.argument("radius", IntegerArgumentType.integer(1, 4096))
						.executes(context -> hide(context.getSource(), StringArgumentType.getString(context, "name"), IntegerArgumentType.getInteger(context, "radius"))))))
			.then(Commands.literal("unhide")
				.then(Commands.argument("name", StringArgumentType.string())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						config == null ? List.of() : config.hiddenAreas.stream().map(area -> area.name).toList(), builder))
					.executes(context -> unhide(context.getSource(), StringArgumentType.getString(context, "name")))))
			.then(Commands.literal("hidden").executes(context -> listHidden(context.getSource())))));
	}

	/** Hides an area around where the command was run, in its dimension, from the map. */
	private static int hide(CommandSourceStack source, String name, int radius) {
		if (drawing == null || config.unreadable) {
			source.sendFailure(Component.literal(drawing == null ? "The map is not running"
				: "The map's config file could not be read, so nothing is changed until it is fixed"));
			return 0;
		}
		BlockPos at = BlockPos.containing(source.getPosition());
		config.hiddenAreas.removeIf(area -> area.name.equals(name));
		config.hiddenAreas.add(new HiddenAreas.Area(name, source.getLevel().dimension().identifier().toString(), at.getX(), at.getZ(), radius));
		config.save();
		drawing.hide(new HiddenAreas(config.hiddenAreas, seed));
		source.sendSuccess(() -> Component.literal("Hid '" + name + "' from the map: about " + radius + " blocks around here,"
			+ " in a shape that looks like map nobody has explored. Markers in it are gone at the next update."), true);
		return 1;
	}

	private static int unhide(CommandSourceStack source, String name) {
		if (drawing != null && config.unreadable) {
			source.sendFailure(Component.literal("The map's config file could not be read, so nothing is changed until it is fixed"));
			return 0;
		}
		if (drawing == null || !config.hiddenAreas.removeIf(area -> area.name.equals(name))) {
			source.sendFailure(Component.literal("No hidden area named '" + name + "'"));
			return 0;
		}
		config.save();
		drawing.hide(new HiddenAreas(config.hiddenAreas, seed));
		source.sendSuccess(() -> Component.literal("'" + name + "' is no longer hidden; it is drawn again as its chunks load or change"), true);
		return 1;
	}

	private static int listHidden(CommandSourceStack source) {
		if (config == null || config.hiddenAreas.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No areas are hidden from the map"), false);
			return 0;
		}
		for (HiddenAreas.Area area : config.hiddenAreas) {
			source.sendSuccess(() -> Component.literal(area.name + ": " + area.radius + " blocks around " + area.x + ", " + area.z + " in " + area.dimension), false);
		}
		return config.hiddenAreas.size();
	}

	/** Something in a chunk changed; called from the chunk itself, on the server thread. */
	public static void changed(ServerLevel level, ChunkPos pos) {
		if (drawing != null) drawing.changed(level, pos);
	}

	private static void start(MinecraftServer server) {
		config = WebMapConfig.load();
		output = new Output(FabricLoader.getInstance().getGameDir().resolve("web-map"));
		Output writing = output;
		drawing = new Drawing(output, config.drawnDimensions(), config.caveDimensions(),
			server.getWorldPath(LevelResource.ROOT).resolve("web-map"),
			extents -> writing.writeLater("version.json", "{\"tiles\":" + System.currentTimeMillis() + ",\"extents\":" + extents + "}"));
		layers = new Layers(output, config, server, drawing.marks(), drawing::hidden);
		seed = server.getWorldGenSettings().options().seed();
		drawing.hide(new HiddenAreas(config.hiddenAreas, seed));
		ticks = 0;
		for (String file : PAGE) {
			try {
				output.copyResource("web-map/" + file, file);
			} catch (Exception e) {
				Output.LOGGER.warn("Could not put {} in place", file, e);
			}
		}
		Output.LOGGER.info("Drawing {} into {}; parts of the world not loaded since the map was installed stay blank until they are",
			config.dimensions, output.root);
	}

	private static void tick(MinecraftServer server) {
		if (drawing == null) return;
		ticks++;
		drawing.tick();
		layers.tick(server, ticks);
		if (ticks % FLUSH_TICKS == 0) drawing.flush();
	}

	/** Never throws: an exception from a stop handler cuts off every handler after it. */
	private static void stop() {
		Output closing = output;
		Drawing finishing = drawing;
		Layers ending = layers;
		if (backfill != null) backfill.stop();
		backfill = null;
		output = null;
		drawing = null;
		layers = null;
		if (closing == null) return;
		try {
			ending.stop();
			finishing.flush();
			finishing.forget();
		} catch (Exception e) {
			Output.LOGGER.warn("Could not finish the map", e);
		}
		closing.close(10);
	}
}
