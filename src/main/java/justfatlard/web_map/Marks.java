package justfatlard.web_map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
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
 * Markers that come from blocks in the world: a sign whose first line, on either side, is
 * {@code [map]}, named by its other lines, and every beacon, with its name, its effects and how many
 * pyramid levels it stands on. Read with each chunk as it is drawn, live or saved, so placing,
 * editing or breaking one reaches the map as its chunk is next drawn, and kept in the world folder so
 * the saved world need not be read again to know them.
 *
 * <p>Read on whichever thread drew the chunk; changed on the writer thread; listed on the server's.
 */
final class Marks {
	static final String SIGN = "sign", BEACON = "beacon";
	static final String MAP_SIGN = "[map]";
	private static final int LONGEST = 64;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/**
	 * @param name    a sign's text, or a beacon's custom name, or null for a beacon without one
	 * @param effects a beacon's effects by id, primary first; empty for a sign
	 * @param levels  a beacon's pyramid levels, 0 for one that is not lit; 0 for a sign
	 */
	record Mark(String kind, String dimension, int x, int y, int z, String name, List<String> effects, int levels) {}

	private final Path file;
	private final Map<String, Map<Long, List<Mark>>> byChunk = new ConcurrentHashMap<>();
	private volatile boolean changed;

	Marks(Path file) {
		this.file = file;
		try {
			if (!Files.exists(file)) return;
			List<Mark> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Mark>>() {}.getType());
			if (stored == null) return;
			for (Mark mark : stored) {
				if (mark == null || mark.dimension() == null || !(SIGN.equals(mark.kind()) || BEACON.equals(mark.kind()))) continue;
				Mark clean = new Mark(mark.kind(), Dimensions.folder(mark.dimension()), mark.x(), mark.y(), mark.z(),
					mark.name() == null ? null : trimmed(mark.name()), mark.effects() == null ? List.of() : List.copyOf(mark.effects()), mark.levels());
				byChunk.computeIfAbsent(clean.dimension(), d -> new ConcurrentHashMap<>())
					.computeIfAbsent(ChunkPos.pack(clean.x() >> 4, clean.z() >> 4), p -> new ArrayList<>()).add(clean);
			}
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read the map's signs and beacons from {}; they come back as their chunks are drawn", file, e);
		}
	}

	/** On the writer thread: the marks now in a chunk, in place of those there before. */
	void drawn(String dimension, int chunkX, int chunkZ, List<Mark> marks) {
		Map<Long, List<Mark>> here = byChunk.computeIfAbsent(dimension, d -> new ConcurrentHashMap<>());
		long pos = ChunkPos.pack(chunkX, chunkZ);
		List<Mark> before = here.get(pos);
		if (marks.isEmpty() ? before == null : marks.equals(before)) return;
		if (marks.isEmpty()) here.remove(pos);
		else here.put(pos, List.copyOf(marks));
		changed = true;
	}

	List<Mark> all() {
		List<Mark> all = new ArrayList<>();
		for (Map<Long, List<Mark>> here : byChunk.values()) for (List<Mark> marks : here.values()) all.addAll(marks);
		return all;
	}

	/** On the writer thread: keeps the marks, when they changed. */
	void flush() throws Exception {
		if (!changed) return;
		changed = false;
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, GSON.toJson(all()), StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** The marks in a loaded chunk; on the server thread. */
	static List<Mark> of(String dimension, LevelChunk chunk) {
		List<Mark> found = new ArrayList<>();
		HolderLookup.Provider registries = chunk.getLevel().registryAccess();
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			int x = entity.getBlockPos().getX(), y = entity.getBlockPos().getY(), z = entity.getBlockPos().getZ();
			if (entity instanceof SignBlockEntity sign) {
				for (SignTextSlot slot : SignTextSlot.values()) {
					Mark read = sign(dimension, x, y, z, sign.getText(slot).getMessages(false));
					if (read != null) {
						found.add(read);
						break;
					}
				}
			} else if (entity instanceof BeaconBlockEntity) {
				found.add(beacon(dimension, x, y, z, entity.saveWithoutMetadata(registries), registries));
			}
		}
		return found;
	}

	/** The marks among a saved chunk's block entities. */
	static List<Mark> saved(String dimension, List<CompoundTag> entities, HolderLookup.Provider registries) {
		List<Mark> found = new ArrayList<>();
		var ops = registries.createSerializationContext(NbtOps.INSTANCE);
		for (CompoundTag entity : entities) {
			String id = entity.getStringOr("id", "");
			int x = entity.getIntOr("x", 0), y = entity.getIntOr("y", 0), z = entity.getIntOr("z", 0);
			if (id.equals("minecraft:beacon")) {
				found.add(beacon(dimension, x, y, z, entity, registries));
				continue;
			}
			if (!id.endsWith("sign")) continue;
			for (String side : new String[] {"front_text", "back_text"}) {
				Mark read = entity.getCompound(side)
					.flatMap(text -> SignText.CODEC.parse(ops, text).result())
					.map(text -> sign(dimension, x, y, z, text.getMessages(false)))
					.orElse(null);
				if (read != null) {
					found.add(read);
					break;
				}
			}
		}
		return found;
	}

	private static Mark sign(String dimension, int x, int y, int z, List<Component> lines) {
		if (lines.isEmpty() || !lines.get(0).getString().trim().equalsIgnoreCase(MAP_SIGN)) return null;
		StringBuilder text = new StringBuilder();
		for (Component line : lines.subList(1, lines.size())) {
			String words = line.getString().trim();
			if (words.isEmpty()) continue;
			if (!text.isEmpty()) text.append(' ');
			text.append(words);
		}
		return new Mark(SIGN, dimension, x, y, z, trimmed(text.isEmpty() ? "Sign" : text.toString()), List.of(), 0);
	}

	/** A beacon from its NBT, as saved or as a live one saves itself. */
	private static Mark beacon(String dimension, int x, int y, int z, CompoundTag tag, HolderLookup.Provider registries) {
		List<String> effects = new ArrayList<>();
		for (String key : new String[] {"primary_effect", "secondary_effect"}) tag.getString(key).ifPresent(effects::add);
		String name = tag.get("CustomName") == null ? null : ComponentSerialization.CODEC
			.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("CustomName")).result()
			.map(component -> trimmed(component.getString())).orElse(null);
		return new Mark(BEACON, dimension, x, y, z, name, effects, Math.clamp(tag.getIntOr("Levels", 0), 0, 4));
	}

	private static String trimmed(String text) {
		return text.length() > LONGEST ? text.substring(0, LONGEST) : text;
	}
}
