package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs players put on the map: one whose first line, on either side, is {@code [map]} is a marker
 * named by its other lines. Read with each chunk as it is drawn, live or saved, so a sign placed,
 * edited or broken reaches the map as its chunk is next drawn, and kept in the world folder so the
 * saved world need not be read again to know them.
 *
 * <p>Read on whichever thread drew the chunk; changed on the writer thread; listed on the server's.
 */
final class Signs {
	static final String MARK = "[map]";
	private static final int LONGEST = 64;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	record Sign(String dimension, int x, int y, int z, String text) {}

	private final Path file;
	private final Map<String, Map<Long, List<Sign>>> byChunk = new ConcurrentHashMap<>();
	private volatile boolean changed;

	Signs(Path file) {
		this.file = file;
		try {
			if (!Files.exists(file)) return;
			List<Sign> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Sign>>() {}.getType());
			if (stored == null) return;
			for (Sign sign : stored) {
				if (sign == null || sign.dimension() == null || sign.text() == null) continue;
				Sign clean = new Sign(Dimensions.folder(sign.dimension()), sign.x(), sign.y(), sign.z(), trimmed(sign.text()));
				chunk(clean.dimension(), ChunkPos.pack(clean.x() >> 4, clean.z() >> 4)).add(clean);
			}
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read the map's signs from {}; they come back as their chunks are drawn", file, e);
		}
	}

	/** On the writer thread: the signs now in a chunk, in place of those there before. */
	void drawn(String dimension, int chunkX, int chunkZ, List<Sign> signs) {
		Map<Long, List<Sign>> here = byChunk.computeIfAbsent(dimension, d -> new ConcurrentHashMap<>());
		long pos = ChunkPos.pack(chunkX, chunkZ);
		List<Sign> before = here.get(pos);
		if (signs.isEmpty() ? before == null : signs.equals(before)) return;
		if (signs.isEmpty()) here.remove(pos);
		else here.put(pos, List.copyOf(signs));
		changed = true;
	}

	List<Sign> all() {
		List<Sign> all = new ArrayList<>();
		for (Map<Long, List<Sign>> here : byChunk.values()) for (List<Sign> signs : here.values()) all.addAll(signs);
		return all;
	}

	/** On the writer thread: keeps the signs, when they changed. */
	void flush() throws Exception {
		if (!changed) return;
		changed = false;
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, GSON.toJson(all()), StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private List<Sign> chunk(String dimension, long pos) {
		return byChunk.computeIfAbsent(dimension, d -> new ConcurrentHashMap<>()).computeIfAbsent(pos, p -> new ArrayList<>());
	}

	/** The map signs in a loaded chunk; on the server thread. */
	static List<Sign> of(String dimension, LevelChunk chunk) {
		List<Sign> found = new ArrayList<>();
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			if (!(entity instanceof SignBlockEntity sign)) continue;
			for (SignTextSlot slot : SignTextSlot.values()) {
				Sign read = read(dimension, sign.getBlockPos().getX(), sign.getBlockPos().getY(), sign.getBlockPos().getZ(),
					sign.getText(slot).getMessages(false));
				if (read != null) {
					found.add(read);
					break;
				}
			}
		}
		return found;
	}

	/** The map signs among a saved chunk's block entities. */
	static List<Sign> saved(String dimension, List<CompoundTag> entities, HolderLookup.Provider registries) {
		List<Sign> found = new ArrayList<>();
		var ops = registries.createSerializationContext(NbtOps.INSTANCE);
		for (CompoundTag entity : entities) {
			if (!entity.getStringOr("id", "").endsWith("sign")) continue;
			for (String side : new String[] {"front_text", "back_text"}) {
				Sign read = entity.getCompound(side)
					.flatMap(text -> SignText.CODEC.parse(ops, text).result())
					.map(text -> read(dimension, entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0), text.getMessages(false)))
					.orElse(null);
				if (read != null) {
					found.add(read);
					break;
				}
			}
		}
		return found;
	}

	private static Sign read(String dimension, int x, int y, int z, List<Component> lines) {
		if (lines.isEmpty() || !lines.get(0).getString().trim().equalsIgnoreCase(MARK)) return null;
		StringBuilder text = new StringBuilder();
		for (Component line : lines.subList(1, lines.size())) {
			String words = line.getString().trim();
			if (words.isEmpty()) continue;
			if (!text.isEmpty()) text.append(' ');
			text.append(words);
		}
		return new Sign(dimension, x, y, z, trimmed(text.isEmpty() ? "Sign" : text.toString()));
	}

	private static String trimmed(String text) {
		return text.length() > LONGEST ? text.substring(0, LONGEST) : text;
	}
}
