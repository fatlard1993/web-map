package justfatlard.web_map.integration;

import justfatlard.player_portals.PortalAnchor;
import justfatlard.player_portals.PortalRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player Portals' links: a tie, which leads both ways, once, and a hub's spoke, which leads one way.
 * A portal never struck has no colour, so a link may come without one.
 */
public final class PortalLayer {
	private PortalLayer() {}

	public record End(ResourceKey<Level> dimension, int x, int z) {}

	public record Link(String name, Integer argb, End from, End to, boolean bothWays) {}

	public static List<Link> links(MinecraftServer server) {
		List<Link> out = new ArrayList<>();
		PortalRegistry registry = PortalRegistry.get(server);
		Map<PortalAnchor, PortalAnchor> links = registry.everyLink();
		Set<PortalAnchor> tiedAlready = new HashSet<>();
		for (Map.Entry<PortalAnchor, PortalAnchor> link : links.entrySet()) {
			PortalAnchor from = link.getKey(), to = link.getValue();
			boolean bothWays = from.equals(links.get(to));
			if (bothWays && !tiedAlready.add(to)) continue;
			if (bothWays) tiedAlready.add(from);
			Integer colour = registry.colourOf(from);
			if (colour == null) colour = registry.colourOf(to);
			out.add(new Link(registry.nameOf(from), colour, end(from), end(to), bothWays));
		}
		return out;
	}

	private static End end(PortalAnchor anchor) {
		return new End(anchor.dimension(), anchor.pos().getX(), anchor.pos().getZ());
	}
}
