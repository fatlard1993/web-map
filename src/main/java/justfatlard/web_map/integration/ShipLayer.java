package justfatlard.web_map.integration;

import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Big Boats' ships in loaded chunks: one far from everyone is not in memory. */
public final class ShipLayer {
	private ShipLayer() {}

	public record Ship(UUID id, String name, int x, int z, int blocks) {}

	public static List<Ship> loaded(ServerLevel level) {
		List<Ship> ships = new ArrayList<>();
		for (MultiBlockShipEntity ship : level.getEntities(BigBoats.MULTI_BLOCK_SHIP_ENTITY_TYPE, ship -> !ship.isRemoved())) {
			ships.add(new Ship(ship.getUUID(), ship.hasShipName() ? ship.getShipName() : null,
				(int) Math.floor(ship.getX()), (int) Math.floor(ship.getZ()), ship.getBlockCount()));
		}
		return ships;
	}

	public static boolean isShip(Entity entity) {
		return entity instanceof MultiBlockShipEntity;
	}
}
