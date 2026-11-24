package justfatlard.web_map.integration;

import justfatlard.village_quests.Village;
import justfatlard.village_quests.VillageQuests;
import justfatlard.village_quests.manager.VillageManager;

import java.util.ArrayList;
import java.util.List;

/** The villages Village Quests knows, which are all in the overworld, with their names. */
public final class VillageLayer {
	private VillageLayer() {}

	public record Spot(String name, int x, int z, int radius) {}

	public static List<Spot> villages() {
		List<Spot> out = new ArrayList<>();
		VillageManager manager = VillageQuests.getVillageManager();
		if (manager == null) return out;
		for (Village village : manager.getAllVillages()) {
			if (village.isDepopulated()) continue;
			out.add(new Spot(village.getName(), village.getCenter().getX(), village.getCenter().getZ(), village.getRadius()));
		}
		return out;
	}
}
