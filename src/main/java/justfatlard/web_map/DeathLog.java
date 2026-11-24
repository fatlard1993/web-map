package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Where players died: each one's last {@link #KEPT_EACH}, for {@link #FORGOTTEN_AFTER_DAYS} days, so
 * whoever died can find their things. Kept in the world's folder, not the served one, and whatever the
 * deaths layer is set to. Server thread only, apart from the file write.
 */
final class DeathLog {
	static final int KEPT_EACH = 3;
	static final int FORGOTTEN_AFTER_DAYS = 30;
	private static final int LONGEST_MESSAGE = 200;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	record Death(String player, String name, String dimension, int x, int y, int z, String message, long time) {}

	private final Path file;
	private final List<Death> deaths = new ArrayList<>();

	DeathLog(Path file) {
		this.file = file;
		try {
			if (!Files.exists(file)) return;
			List<Death> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Death>>() {}.getType());
			if (stored != null) for (Death death : stored) if (death != null && isPlayer(death.player()) && death.name() != null) deaths.add(clean(death));
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read where players died from {}", file, e);
		}
	}

	void died(Death death) {
		deaths.add(clean(death));
		List<Death> theirs = deaths.stream().filter(d -> d.player().equals(death.player())).toList();
		for (int i = 0; i < theirs.size() - KEPT_EACH; i++) deaths.remove(theirs.get(i));
	}

	/** The deaths still remembered, oldest first, forgetting those past their days. */
	List<Death> all(long now) {
		long cutoff = now - TimeUnit.DAYS.toMillis(FORGOTTEN_AFTER_DAYS);
		deaths.removeIf(death -> death.time() < cutoff);
		return new ArrayList<>(deaths);
	}

	String snapshot() {
		return GSON.toJson(deaths);
	}

	void write(String snapshot) throws Exception {
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static boolean isPlayer(String id) {
		try {
			java.util.UUID.fromString(id);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static Death clean(Death death) {
		String message = death.message() == null ? null
			: death.message().length() > LONGEST_MESSAGE ? death.message().substring(0, LONGEST_MESSAGE) : death.message();
		return new Death(death.player(), death.name(), death.dimension() == null ? "" : Dimensions.folder(death.dimension()),
			death.x(), death.y(), death.z(), message, death.time());
	}
}
