package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the server draws and which layers it offers the page, read once at start. A layer switched
 * off is never written, so no viewer can turn it on; one left on is still each viewer's to hide.
 */
final class WebMapConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE = "web-map-justfatlard.json";
	private static final List<String> DEFAULT_DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

	List<String> dimensions = DEFAULT_DIMENSIONS;
	boolean players = true;
	boolean homes = true;
	boolean villages = true;
	boolean spawn = true;
	boolean portals = true;
	boolean ships = true;
	boolean photos = true;
	boolean deaths = true;
	boolean signs = true;
	boolean border = true;
	boolean daylight = true;
	boolean backfill = true;

	Map<String, Boolean> layers() {
		Map<String, Boolean> layers = new LinkedHashMap<>();
		layers.put("players", players);
		layers.put("homes", homes);
		layers.put("villages", villages);
		layers.put("spawn", spawn);
		layers.put("portals", portals);
		layers.put("ships", ships);
		layers.put("photos", photos);
		layers.put("deaths", deaths);
		layers.put("signs", signs);
		layers.put("border", border);
		layers.put("daylight", daylight);
		return layers;
	}

	/** In the order listed, which is the order the page offers them. */
	Set<String> drawnDimensions() {
		return new LinkedHashSet<>(dimensions);
	}

	static WebMapConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
		WebMapConfig config = null;
		String read = null;
		try {
			if (Files.exists(path)) {
				read = Files.readString(path, StandardCharsets.UTF_8);
				config = GSON.fromJson(read, WebMapConfig.class);
				if (config != null) warnUnknown(read);
			}
		} catch (Exception e) {
			// Left as it is, and nothing offered: falling back to defaults would switch every layer
			// the admin turned off back on
			Output.LOGGER.error("Could not read {}; drawing nothing and offering no layers until it is fixed", path, e);
			return nothing();
		}
		if (config == null) config = new WebMapConfig();
		if (config.dimensions == null) config.dimensions = DEFAULT_DIMENSIONS;
		// Written back whenever it differs, so a setting added in an update shows up in the file
		String written = GSON.toJson(config);
		if (!written.equals(read)) {
			try {
				Files.writeString(path, written, StandardCharsets.UTF_8);
			} catch (Exception e) {
				Output.LOGGER.warn("Could not write {}", path, e);
			}
		}
		return config;
	}

	private static WebMapConfig nothing() {
		WebMapConfig none = new WebMapConfig();
		none.dimensions = List.of();
		none.players = none.homes = none.villages = none.spawn = none.portals = none.ships = none.photos = none.deaths = none.signs = none.border = none.daylight = none.backfill = false;
		return none;
	}

	private static void warnUnknown(String read) {
		Set<String> known = GSON.toJsonTree(new WebMapConfig()).getAsJsonObject().keySet();
		JsonObject given = JsonParser.parseString(read).getAsJsonObject();
		for (String key : given.keySet()) {
			if (!known.contains(key)) Output.LOGGER.warn("Unknown setting '{}' in {}: ignored, and dropped from the file", key, FILE);
		}
	}
}
