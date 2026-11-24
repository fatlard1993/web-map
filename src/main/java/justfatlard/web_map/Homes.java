package justfatlard.web_map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each player respawns: their bed, or a respawn anchor. Online players are read live; everyone
 * else from their saved player data, named from the server's user cache.
 */
final class Homes {
	record Home(UUID player, String name, String dimension, int x, int z) {}

	/** The last scan of saved players, replaced whole by the next. */
	private volatile Map<UUID, Home> saved = Map.of();

	/** Reads every saved player's respawn point. Slow, so on the writer thread. */
	void scanSaved(Path userCache, Path playerData) {
		if (!Files.isDirectory(playerData)) return;
		Map<UUID, String> names = names(userCache);
		Map<UUID, Home> scanned = new HashMap<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(playerData, "*.dat")) {
			for (Path file : files) {
				String stem = file.getFileName().toString().replace(".dat", "");
				try {
					UUID id = UUID.fromString(stem);
					CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
					tag.getCompound("respawn")
						.flatMap(respawn -> ServerPlayer.RespawnConfig.CODEC.parse(NbtOps.INSTANCE, respawn).result())
						.ifPresent(config -> scanned.put(id, home(id, names.getOrDefault(id, stem.substring(0, 8)), config)));
				} catch (Exception skipped) {
					// Not a player's file, or one being written as we read it: the next scan reads it
				}
			}
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read saved homes", e);
			return;
		}
		saved = scanned;
	}

	/** Online players over saved ones, since a player online may have slept somewhere new. */
	Collection<Home> all(Iterable<ServerPlayer> online) {
		Map<UUID, Home> homes = new HashMap<>(saved);
		for (ServerPlayer player : online) {
			ServerPlayer.RespawnConfig config = player.getRespawnConfig();
			if (config == null) homes.remove(player.getUUID());
			else homes.put(player.getUUID(), home(player.getUUID(), player.getGameProfile().name(), config));
		}
		return homes.values();
	}

	private static Home home(UUID player, String name, ServerPlayer.RespawnConfig config) {
		var data = config.respawnData();
		return new Home(player, name, Dimensions.folder(data.dimension()), data.pos().getX(), data.pos().getZ());
	}

	private static Map<UUID, String> names(Path userCache) {
		Map<UUID, String> names = new HashMap<>();
		try {
			JsonElement root = JsonParser.parseString(Files.readString(userCache, StandardCharsets.UTF_8));
			if (root instanceof JsonArray entries) {
				for (JsonElement entry : entries) {
					JsonObject object = entry.getAsJsonObject();
					names.put(UUID.fromString(object.get("uuid").getAsString()), object.get("name").getAsString());
				}
			}
		} catch (Exception e) {
			Output.LOGGER.warn("Could not read player names from {}", userCache, e);
		}
		return names;
	}
}
