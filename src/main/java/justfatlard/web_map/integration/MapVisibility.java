package justfatlard.web_map.integration;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** Whether a player asked to be shown on maps, their position and their home: Pandorical's per-player choice. */
public final class MapVisibility {
	private MapVisibility() {}

	public static boolean shown(MinecraftServer server, UUID player) {
		return justfatlard.pandorical.api.PandoricalApi.maps().shown(server, player);
	}
}
