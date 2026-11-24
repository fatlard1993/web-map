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
 * as {@code players.json} and {@code markers.json}; the time of day and each dimension's world border,
 * as {@code world.json}; and what it offers, as {@code meta.json}. Only
 * dimensions the server draws are ever written, whatever a layer would otherwise show.
 *
 * <p>Built on the server thread, written on the writer's.
 */
final class Layers {
	private static final int PLAYERS_TICKS = 100;
	/** How far under the surface a player counts as underground: deeper than a house is tall. */
	private static final int UNDERGROUND_DEPTH = 8;
	private static final int MARKERS_TICKS = 600;
	private static final int SAVED_HOMES_TICKS = 12000;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private static final boolean VILLAGE_QUESTS = FabricLoader.getInstance().isModLoaded("village-quests-justfatlard");
	private static final boolean PLAYER_PORTALS = FabricLoader.getInstance().isModLoaded("player-portals-justfatlard");
	private static final boolean BIG_BOATS = FabricLoader.getInstance().isModLoaded("big-boats-justfatlard");
	private static final boolean PHOTOGRAPHY = FabricLoader.getInstance().isModLoaded("redstone-photography");
	private static final boolean PANDORICAL = FabricLoader.getInstance().isModLoaded("pandorical");
	private static final boolean OFFLINE_SKINS = FabricLoader.getInstance().isModLoaded("offline-skins-justfatlard");

	private final Output output;
	private final WebMapConfig config;
	private final Set<String> drawnFolders = new HashSet<>();
	private final Homes homes = new Homes();
	private final ShipLog ships;
	private final DeathLog deaths;
	private final Marks marks;
	private final Faces faces;
	private final Supplier<HiddenAreas> hidden;
	private final Sightings<List<Spot>> sightings = new Sightings<>();

	private record Spot(String name, String dimension, int x, int z, String wornName) {}
	/** A layer whose mod threw or did not match this build: off until the server restarts. Both threads. */
	private final Set<String> broken = ConcurrentHashMap.newKeySet();
	private volatile JsonArray photos = new JsonArray();

	/** @param hidden the areas the map never shows, as they are now */
	Layers(Output output, WebMapConfig config, MinecraftServer server, Marks marks, Supplier<HiddenAreas> hidden) {
		this.output = output;
		this.hidden = hidden;
		this.marks = marks;
		this.faces = new Faces(output.root.resolve("heads"));
		this.config = config;
		config.dimensions.forEach(id -> drawnFolders.add(Dimensions.folder(id)));
		this.ships = new ShipLog(server.getWorldPath(LevelResource.ROOT).resolve("web-map").resolve("ships.json"));
		this.deaths = new DeathLog(server.getWorldPath(LevelResource.ROOT).resolve("web-map").resolve("deaths.json"));
	}

	void started(MinecraftServer server) {
		List<String> missing = new ArrayList<>(config.dimensions);
		for (ServerLevel level : server.getAllLevels()) missing.remove(level.dimension().identifier().toString());
		if (!missing.isEmpty()) Output.LOGGER.warn("No such dimension on this server, so not drawn: {}", missing);

		writeMeta(server);
		if (!config.players) output.writeLater("players.json", "[]");
		writeWorld(server);
		scanSavedHomes(server);
		refreshPhotos(server);
		writeMarkers(server);
	}

	void tick(MinecraftServer server, int ticks) {
		if (ticks % PLAYERS_TICKS == 0 && config.players) writePlayers(server);
		if (ticks % PLAYERS_TICKS == 0 && (config.border || config.daylight)) writeWorld(server);
		if (ticks % SAVED_HOMES_TICKS == 1) scanSavedHomes(server);
		if (ticks % MARKERS_TICKS == 2) {
			refreshPhotos(server);
			writeMarkers(server);
		}
	}

	/** A player died: kept, and on the map at the next markers. */
	void died(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
		String message;
		try {
			message = source.getLocalizedDeathMessage(player).getString();
		} catch (RuntimeException e) {
			message = null;
		}
		deaths.died(new DeathLog.Death(player.getUUID().toString(), player.getGameProfile().name(),
			player.level().dimension().identifier().toString(), (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
			(int) Math.floor(player.getZ()), message, System.currentTimeMillis()));
		String snapshot = deaths.snapshot();
		output.later("keep where players died", () -> deaths.write(snapshot));
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
		String overworld = Dimensions.folder(Level.OVERWORLD);
		// Where the page opens, which says where spawn is as plainly as its marker would
		if (config.spawn && shown(overworld, spawn.getX(), spawn.getZ())) meta.add("spawn", spot(overworld, spawn.getX(), spawn.getZ()));
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
		JsonArray caves = new JsonArray();
		for (String id : config.caveDimensions()) caves.add(Dimensions.folder(id));
		meta.add("caves", caves);
		// Every drawn dimension without a roof over it may have sky builds, whose ground is drawn beside it
		JsonArray sky = new JsonArray();
		for (ServerLevel level : server.getAllLevels()) {
			String folder = Dimensions.folder(level.dimension());
			if (drawnFolders.contains(folder) && !level.dimensionType().hasCeiling()) sky.add(folder);
		}
		meta.add("sky", sky);
		output.writeLater("meta.json", GSON.toJson(meta));
	}

	/**
	 * Who is where, as it was the delay ago, so the map cannot be used to chase anyone: a player is shown
	 * where they were then, and only if they may be seen now. Spectators, the invisible, those sneaking,
	 * underground or in a hidden area, and those who asked to be left off are not seen at all.
	 */
	private void writePlayers(MinecraftServer server) {
		List<Spot> now = new ArrayList<>();
		// Who may be seen at all right now: a sighting from the delay ago is shown only for them, so going
		// invisible, sneaking, underground or off the map hides someone at once rather than the delay later
		Set<String> seeable = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String name = player.getGameProfile().name();
			if (player.isSpectator() || player.isInvisible() || !shownOnMaps(server, player.getUUID())) continue;
			if (config.hideSneaking && player.isShiftKeyDown()) continue;
			if (config.hideUnderground && underground(player)) continue;
			String dimension = Dimensions.folder(player.level().dimension());
			if (!shown(dimension, player.getBlockX(), player.getBlockZ())) continue;
			seeable.add(name);
			String wornName = OFFLINE_SKINS ? guarded("faces", () -> justfatlard.web_map.integration.SkinLayer.wornName(player.getUUID(), name)) : null;
			now.add(new Spot(name, dimension, (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()), wornName));
		}
		List<Spot> shown = sightings.see(System.currentTimeMillis(), now, config.playerDelaySeconds * 1000L);
		if (shown == null) shown = List.of();

		JsonArray players = new JsonArray();
		List<String> worn = new ArrayList<>();
		for (Spot spot : shown) {
			// A hidden area made since is honoured for where they were, too
			if (!seeable.contains(spot.name()) || !shown(spot.dimension(), spot.x(), spot.z())) continue;
			JsonObject entry = spot(spot.dimension(), spot.x(), spot.z());
			entry.addProperty("name", spot.name());
			players.add(entry);
			worn.add(spot.wornName());
		}
		// Faces are read from disk on the writer thread, and the list written once they are in place
		output.later("draw faces and write players.json", () -> {
			for (int i = 0; i < players.size(); i++) {
				String wornName = worn.get(i);
				if (wornName == null) continue;
				JsonObject entry = players.get(i).getAsJsonObject();
				byte[] skin = guarded("faces", () -> justfatlard.web_map.integration.SkinLayer.png(wornName));
				String face = faces.face(entry.get("name").getAsString(), skin);
				if (face != null) entry.addProperty("face", face);
			}
			output.write("players.json", GSON.toJson(players));
		});
	}

	/** The overworld's time of day, 0 at sunrise as the game counts it, and each drawn dimension's border. */
	private void writeWorld(MinecraftServer server) {
		JsonObject world = new JsonObject();
		if (config.daylight) world.addProperty("time", Math.floorMod(server.overworld().getOverworldClockTime(), 24000L));
		if (config.border) {
			JsonObject borders = new JsonObject();
			for (ServerLevel level : server.getAllLevels()) {
				String dimension = Dimensions.folder(level.dimension());
				if (!drawnFolders.contains(dimension)) continue;
				var border = level.getWorldBorder();
				JsonObject edges = new JsonObject();
				edges.addProperty("x0", Math.floor(border.getMinX()));
				edges.addProperty("z0", Math.floor(border.getMinZ()));
				edges.addProperty("x1", Math.ceil(border.getMaxX()));
				edges.addProperty("z1", Math.ceil(border.getMaxZ()));
				borders.add(dimension, edges);
			}
			world.add("borders", borders);
		}
		output.writeLater("world.json", GSON.toJson(world));
	}

	private void writeMarkers(MinecraftServer server) {
		JsonObject markers = new JsonObject();
		if (config.homes) {
			JsonArray list = new JsonArray();
			for (Homes.Home home : homes.all(server.getPlayerList().getPlayers())) {
				if (!shown(home.dimension(), home.x(), home.z()) || !shownOnMaps(server, home.player())) continue;
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
					if (!shown(overworld, village.x(), village.z())) continue;
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
			String overworld = Dimensions.folder(Level.OVERWORLD);
			if (shown(overworld, spawn.getX(), spawn.getZ())) markers.add("spawn", spot(overworld, spawn.getX(), spawn.getZ()));
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
		if (config.signs || config.beacons) {
			JsonArray signList = new JsonArray(), beaconList = new JsonArray();
			for (Marks.Mark mark : marks.all()) {
				if (!shown(mark.dimension(), mark.x(), mark.z())) continue;
				JsonObject entry = spot(mark.dimension(), mark.x(), mark.z());
				entry.addProperty("y", mark.y());
				if (Marks.SIGN.equals(mark.kind())) {
					entry.addProperty("text", mark.name());
					signList.add(entry);
				} else {
					if (mark.name() != null) entry.addProperty("name", mark.name());
					JsonArray effects = new JsonArray();
					mark.effects().forEach(effects::add);
					entry.add("effects", effects);
					entry.addProperty("levels", mark.levels());
					beaconList.add(entry);
				}
			}
			if (config.signs) markers.add("signs", signList);
			if (config.beacons) markers.add("beacons", beaconList);
		}
		if (config.deaths) {
			JsonArray list = new JsonArray();
			for (DeathLog.Death death : deaths.all(System.currentTimeMillis())) {
				if (!shown(death.dimension(), death.x(), death.z()) || !shownOnMaps(server, java.util.UUID.fromString(death.player()))) continue;
				JsonObject entry = spot(death.dimension(), death.x(), death.z());
				entry.addProperty("name", death.name());
				entry.addProperty("y", death.y());
				if (death.message() != null) entry.addProperty("message", death.message());
				entry.addProperty("time", death.time());
				list.add(entry);
			}
			markers.add("deaths", list);
		}
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
		return shown(dimension, end.x(), end.z()) ? spot(dimension, end.x(), end.z()) : null;
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
			if (!shown(ship.dimension(), ship.x(), ship.z())) continue;
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
				if (!shown(dimension, pin.x(), pin.z()) || !Files.exists(pin.picture())) continue;
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

	/** Whether a spot may be on the map: in a drawn dimension, and in no hidden area. */
	private boolean shown(String dimension, int x, int z) {
		return drawnFolders.contains(dimension) && !hidden.get().coversBlock(dimension, x, z);
	}

	/** Deeper under the surface than a house is tall, in a dimension without a roof over everything. */
	static boolean underground(ServerPlayer player) {
		if (player.level().dimensionType().hasCeiling()) return false;
		int surface = player.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			player.getBlockX(), player.getBlockZ());
		return player.getY() < surface - UNDERGROUND_DEPTH;
	}

	/** Whether the player asked to be on maps, where Pandorical is there to ask; everyone is, without it. */
	private boolean shownOnMaps(MinecraftServer server, java.util.UUID player) {
		if (!PANDORICAL || broken.contains("visibility")) return true;
		try {
			return justfatlard.web_map.integration.MapVisibility.shown(server, player);
		} catch (LinkageError older) {
			broken.add("visibility");
			Output.LOGGER.warn("Pandorical is older than 15.37.0, so players cannot choose to be left off the map");
			return true;
		}
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
