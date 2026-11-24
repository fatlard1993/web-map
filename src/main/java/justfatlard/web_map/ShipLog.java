package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Where each ship was last seen. A ship is only in memory while somebody is near it, so one moored
 * far from everyone keeps its last place rather than vanishing. A ship broken up or sunk is let go
 * when its entity is, and one unseen for {@link #FORGOTTEN_AFTER_DAYS} days is forgotten.
 *
 * <p>Kept in the world's folder, not the served one, so nothing a viewer can fetch is read back in,
 * and kept whatever the ships layer is set to, so turning it off loses nothing. Server thread only,
 * apart from the file write.
 */
final class ShipLog {
	static final int FORGOTTEN_AFTER_DAYS = 30;
	private static final int LONGEST_NAME = 64;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	record Ship(String id, String name, String dimension, int x, int z, int blocks, long lastSeen) {}

	private final Path file;
	private final Map<String, Ship> seen = new LinkedHashMap<>();
	private final Map<String, Boolean> live = new LinkedHashMap<>();

	ShipLog(Path file) {
		this.file = file;
		try {
			if (!Files.exists(file)) return;
			List<Ship> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Ship>>() {}.getType());
			if (stored != null) for (Ship ship : stored) if (ship != null && ship.id() != null) seen.put(ship.id(), clean(ship));
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read where ships were last seen from {}", file, e);
		}
	}

	/** Records the ships seen now, marks the rest as not seen now, and forgets the long gone. */
	void sweep(List<Ship> now, long time) {
		live.clear();
		for (Ship ship : now) {
			seen.put(ship.id(), clean(ship));
			live.put(ship.id(), true);
		}
		long cutoff = time - TimeUnit.DAYS.toMillis(FORGOTTEN_AFTER_DAYS);
		seen.values().removeIf(ship -> ship.lastSeen() < cutoff);
	}

	void gone(String id) {
		seen.remove(id);
		live.remove(id);
	}

	boolean isLive(String id) {
		return live.getOrDefault(id, false);
	}

	List<Ship> all() {
		return new ArrayList<>(seen.values());
	}

	/** The current log as text, to write on the writer thread. */
	String snapshot() {
		return GSON.toJson(all());
	}

	void write(String snapshot) throws Exception {
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static Ship clean(Ship ship) {
		String name = ship.name() == null ? null
			: ship.name().length() > LONGEST_NAME ? ship.name().substring(0, LONGEST_NAME) : ship.name();
		return new Ship(ship.id(), name, ship.dimension() == null ? "" : Dimensions.folder(ship.dimension()),
			ship.x(), ship.z(), ship.blocks(), ship.lastSeen());
	}
}
