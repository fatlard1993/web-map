package justfatlard.web_map.integration;

import justfatlard.tp_me_plz.SharedPlaces;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/** The places TP Me Plz players have shared with everyone, with who shared them and their picture. */
public final class SharedPlaceLayer {
	private SharedPlaceLayer() {}

	public record Place(String name, String owner, String icon, String dimension, int x, int z) {}

	public static List<Place> places(MinecraftServer server) {
		List<Place> out = new ArrayList<>();
		for (SharedPlaces.Shared shared : SharedPlaces.listed(server)) {
			var place = shared.place();
			out.add(new Place(place.name(), shared.ownerName(), place.icon(), place.dimension().identifier().toString(),
				(int) Math.floor(place.x()), (int) Math.floor(place.z())));
		}
		return out;
	}
}
