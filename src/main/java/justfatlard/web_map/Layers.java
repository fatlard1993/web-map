package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import justfatlard.web_map.integration.PhotoLayer;
import justfatlard.web_map.integration.PortalLayer;
import justfatlard.web_map.integration.ShipLayer;
import justfatlard.web_map.integration.VillageLayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What the page shows over the map: players, homes, villages, spawn, portal links, ships and photos,
 * as {@code players.json} and {@code markers.json}, and what it offers, as {@code meta.json}. Only
 * dimensions the server draws are ever written, whatever a layer would otherwise show.
 *
 * <p>Built on the server thread, written on the writer's.
 */
final class Layers {
	private static final int PLAYERS_TICKS = 100;
	private static final int MARKERS_TICKS = 600;
	private static final int SAVED_HOMES_TICKS = 12000;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private static final boolean VILLAGE_QUESTS = FabricLoader.getInstance().isModLoaded("village-quests-justfatlard");
	private static final boolean PLAYER_PORTALS = FabricLoader.getInstance().isModLoaded("player-portals-justfatlard");
	private static final boolean BIG_BOATS = FabricLoader.getInstance().isModLoaded("big-boats-justfatlard");
	private static final boolean PHOTOGRAPHY = FabricLoader.getInstance().isModLoaded("redstone-photography");

	private final Output output;
	private final WebMapConfig config;
	private final Set<String> drawnFolders = new HashSet<>();
	private final Homes homes = new Homes();
	private final ShipLog ships;
	/** A layer whose mod threw or did not match this build: off until the server restarts. Both threads. */
	private final Set<String> broken = ConcurrentHashMap.newKeySet();
	private volatile JsonArray photos = new JsonArray();

	Layers(Output output, WebMapConfig config, MinecraftServer server) {
		this.output = output;
		this.config = config;
		config.dimensions.forEach(id -> drawnFolders.add(Dimensions.folder(id)));
		this.ships = new ShipLog(server.getWorldPath(LevelResource.ROOT).resolve("web-map").resolve("ships.json"));
	}

	void started(MinecraftServer server) {
		List<String> missing = new ArrayList<>(config.dimensions);
		for (ServerLevel level : server.getAllLevels()) missing.remove(level.dimension().identifier().toString());
		if (!missing.isEmpty()) Output.LOGGER.warn("No such dimension on this server, so not drawn: {}", missing);

		writeMeta(server);
		if (!config.players) output.writeLater("players.json", "[]");
		scanSavedHomes(server);
		refreshPhotos(server);
		writeMarkers(server);
	}

	void tick(MinecraftServer server, int ticks) {
		if (ticks % PLAYERS_TICKS == 0 && config.players) writePlayers(server);
		if (ticks % SAVED_HOMES_TICKS == 1) scanSavedHomes(server);
		if (ticks % MARKERS_TICKS == 2) {
			refreshPhotos(server);
			writeMarkers(server);
		}
	}

	void entityUnloaded(Entity entity) {
		if (!BIG_BOATS || broken.contains("ships")) return;
		Entity.RemovalReason reason = entity.getRemovalReason();
		if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;
		Boolean ship = guarded("ships", () -> ShipLayer.isShip(entity));
		if (ship != null && ship) ships.gone(entity.getUUID().toString());
	}

	/** On the calling thread, so the page shows nobody online once the server is down. Never throws. */
	void stop() {
		try {
			output.write("players.json", "[]");
		} catch (Exception e) {
			Output.LOGGER.warn("Could not clear players.json", e);
		}
		if (BIG_BOATS) {
			try {
				ships.write(ships.snapshot());
			} catch (Exception e) {
				Output.LOGGER.warn("Could not keep where ships were last seen", e);
			}
		}
	}

	private void writeMeta(MinecraftServer server) {
		JsonObject meta = new JsonObject();
		var spawn = server.overworld().getRespawnData().pos();
		meta.add("spawn", spot(Dimensions.folder(Level.OVERWORLD), spawn.getX(), spawn.getZ()));
		JsonArray layers = new JsonArray();
		config.layers().forEach((layer, on) -> {
			if (on && installed(layer)) layers.add(layer);
		});
		meta.add("layers", layers);
		JsonArray folders = new JsonArray();
		for (String id : config.dimensions) {
			for (ServerLevel level : server.getAllLevels()) {
				if (level.dimension().identifier().toString().equals(id)) folders.add(Dimensions.folder(level.dimension()));
			}
		}
		meta.add("dimensions", folders);
		output.writeLater("meta.json", GSON.toJson(meta));
	}

	private void writePlayers(MinecraftServer server) {
		JsonArray players = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || player.isInvisible()) continue;
			String dimension = Dimensions.folder(player.level().dimension());
			if (!drawnFolders.contains(dimension)) continue;
			JsonObject entry = spot(dimension, (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
			entry.addProperty("name", player.getGameProfile().name());
			players.add(entry);
		}
		output.writeLater("players.json", GSON.toJson(players));
	}

	private void writeMarkers(MinecraftServer server) {
		JsonObject markers = new JsonObject();
		if (config.homes) {
			JsonArray list = new JsonArray();
			for (Homes.Home home : homes.all(server.getPlayerList().getPlayers())) {
				if (!drawnFolders.contains(home.dimension())) continue;
				JsonObject entry = spot(home.dimension(), home.x(), home.z());
				entry.addProperty("name", home.name());
				list.add(entry);
			}
			markers.add("homes", list);
		}
		if (config.villages && VILLAGE_QUESTS) {
			String overworld = Dimensions.folder(Level.OVERWORLD);
			List<VillageLayer.Spot> villages = guarded("villages", VillageLayer::villages);
			if (villages != null && drawnFolders.contains(overworld)) {
				JsonArray list = new JsonArray();
				for (VillageLayer.Spot village : villages) {
					JsonObject entry = spot(overworld, village.x(), village.z());
					entry.addProperty("name", village.name());
					entry.addProperty("radius", village.radius());
					list.add(entry);
				}
				markers.add("villages", list);
			}
		}
		if (config.spawn) {
			var spawn = server.overworld().getRespawnData().pos();
			markers.add("spawn", spot(Dimensions.folder(Level.OVERWORLD), spawn.getX(), spawn.getZ()));
		}
		if (config.portals && PLAYER_PORTALS) {
			List<PortalLayer.Link> links = guarded("portals", () -> PortalLayer.links(server));
			if (links != null) markers.add("portals", portals(links));
		}
		if (BIG_BOATS && !broken.contains("ships")) {
			sweepShips(server);
			if (config.ships && !broken.contains("ships")) markers.add("ships", shipMarkers());
		}
		if (config.photos && PHOTOGRAPHY) markers.add("photos", photos);
		output.writeLater("markers.json", GSON.toJson(markers));
	}

	private JsonArray portals(List<PortalLayer.Link> links) {
		JsonArray list = new JsonArray();
		for (PortalLayer.Link link : links) {
			JsonObject from = end(link.from()), to = end(link.to());
			if (from == null && to == null) continue;
			JsonObject entry = new JsonObject();
			if (link.name() != null) entry.addProperty("name", link.name());
			if (link.argb() != null) entry.addProperty("color", String.format("#%06x", link.argb() & 0xFFFFFF));
			entry.addProperty("bothWays", link.bothWays());
			// An end in a dimension the server does not draw is only said to be elsewhere
			entry.add("from", from != null ? from : elsewhere());
			entry.add("to", to != null ? to : elsewhere());
			list.add(entry);
		}
		return list;
	}

	private JsonObject end(PortalLayer.End end) {
		String dimension = Dimensions.folder(end.dimension());
		return drawnFolders.contains(dimension) ? spot(dimension, end.x(), end.z()) : null;
	}

	private void sweepShips(MinecraftServer server) {
		long now = System.currentTimeMillis();
		List<ShipLog.Ship> seen = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			String dimension = Dimensions.folder(level.dimension());
			if (!drawnFolders.contains(dimension)) continue;
			List<ShipLayer.Ship> loaded = guarded("ships", () -> ShipLayer.loaded(level));
			if (loaded == null) return;
			for (ShipLayer.Ship ship : loaded) {
				seen.add(new ShipLog.Ship(ship.id().toString(), ship.name(), dimension, ship.x(), ship.z(), ship.blocks(), now));
			}
		}
		ships.sweep(seen, now);
		String snapshot = ships.snapshot();
		output.later("keep where ships were last seen", () -> ships.write(snapshot));
	}

	private JsonArray shipMarkers() {
		JsonArray list = new JsonArray();
		for (ShipLog.Ship ship : ships.all()) {
			if (!drawnFolders.contains(ship.dimension())) continue;
			JsonObject entry = spot(ship.dimension(), ship.x(), ship.z());
			if (ship.name() != null) entry.addProperty("name", ship.name());
			entry.addProperty("blocks", ship.blocks());
			entry.addProperty("live", ships.isLive(ship.id()));
			entry.addProperty("lastSeen", ship.lastSeen());
			list.add(entry);
		}
		return list;
	}

	private void scanSavedHomes(MinecraftServer server) {
		if (!config.homes) return;
		Path userCache = FabricLoader.getInstance().getGameDir().resolve("usercache.json");
		Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
		output.later("read saved homes", () -> homes.scanSaved(userCache, playerData));
	}

	/** Photo files are read and copied for the page on the writer thread; the list is kept for the next markers. */
	private void refreshPhotos(MinecraftServer server) {
		if (!config.photos || !PHOTOGRAPHY || broken.contains("photos")) return;
		output.later("gather photos", () -> {
			List<PhotoLayer.Pin> pins = guarded("photos", () -> PhotoLayer.pins(server));
			if (pins == null) return;
			JsonArray list = new JsonArray();
			for (PhotoLayer.Pin pin : pins) {
				String dimension = Dimensions.folder(pin.dimension());
				if (!drawnFolders.contains(dimension) || !Files.exists(pin.picture())) continue;
				Path copy = output.root.resolve("photos").resolve(pin.id() + ".png");
				if (!Files.exists(copy) || Files.size(copy) != Files.size(pin.picture())) {
					Files.createDirectories(copy.getParent());
					Files.copy(pin.picture(), copy, StandardCopyOption.REPLACE_EXISTING);
				}
				JsonObject entry = spot(dimension, pin.x(), pin.z());
				entry.addProperty("id", pin.id().toString());
				entry.addProperty("yaw", pin.yaw());
				if (pin.by() != null) entry.addProperty("by", pin.by());
				entry.addProperty("taken", pin.taken());
				list.add(entry);
			}
			photos = list;
		});
	}

	/** A layer whose data comes from another mod is offered only where that mod is installed. */
	private boolean installed(String layer) {
		return switch (layer) {
			case "villages" -> VILLAGE_QUESTS;
			case "portals" -> PLAYER_PORTALS;
			case "ships" -> BIG_BOATS;
			case "photos" -> PHOTOGRAPHY;
			default -> true;
		};
	}

	/** Calls into another mod; if it throws or does not match this build, its layer is turned off. */
	private <T> T guarded(String layer, Supplier<T> call) {
		if (broken.contains(layer)) return null;
		try {
			return call.get();
		} catch (LinkageError | RuntimeException e) {
			broken.add(layer);
			Output.LOGGER.error("The {} layer failed and is off until the server restarts; is its mod a version this build knows?", layer, e);
			return null;
		}
	}

	private static JsonObject spot(String dimension, int x, int z) {
		JsonObject spot = new JsonObject();
		spot.addProperty("dimension", dimension);
		spot.addProperty("x", x);
		spot.addProperty("z", z);
		return spot;
	}

	private static JsonObject elsewhere() {
		JsonObject end = new JsonObject();
		end.addProperty("elsewhere", true);
		return end;
	}
}
