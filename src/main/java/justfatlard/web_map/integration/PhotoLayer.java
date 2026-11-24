package justfatlard.web_map.integration;

import justfatlard.redstone_photography.Photos;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Redstone Photography's photos that have a recorded place: where each was taken, and its picture. */
public final class PhotoLayer {
	private PhotoLayer() {}

	public record Pin(UUID id, String dimension, int x, int z, float yaw, String by, long taken, Path picture) {}

	public static List<Pin> pins(MinecraftServer server) {
		List<Pin> pins = new ArrayList<>();
		for (Photos.Shot shot : Photos.shots(server)) {
			pins.add(new Pin(shot.photo(), shot.dimension(), shot.x(), shot.z(), shot.yaw(), shot.by(), shot.taken(),
				Photos.file(server, shot.photo())));
		}
		return pins;
	}
}
