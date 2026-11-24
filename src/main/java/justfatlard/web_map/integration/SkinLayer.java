package justfatlard.web_map.integration;

import justfatlard.offline_skins.SkinChoices;
import justfatlard.offline_skins.SkinSource;

import java.util.UUID;

/** A player's skin as Offline Skins dresses them: the one they picked, or their own, from its folders. */
public final class SkinLayer {
	private SkinLayer() {}

	/** Whose skin the player wears; on the server thread. */
	public static String wornName(UUID player, String name) {
		String chosen = SkinChoices.of(player);
		return chosen != null ? chosen : name;
	}

	/** The skin's PNG, from the admin's folder or the kept Mojang copies, or null; any thread. */
	public static byte[] png(String wornName) {
		SkinSource.Skin skin = SkinSource.fromFile(wornName);
		if (skin == null) skin = SkinSource.fromCache(wornName);
		return skin == null ? null : skin.png();
	}
}
