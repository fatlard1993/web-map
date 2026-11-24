package justfatlard.web_map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A dimension's name on disk and in the page's files: {@code minecraft:the_nether} is
 * {@code minecraft_the_nether}. It names a folder under {@code tiles/}, so anything but letters,
 * digits, dots, dashes and underscores becomes an underscore, a datapack dimension's slashes
 * included.
 */
public final class Dimensions {
	private Dimensions() {}

	public static String folder(ResourceKey<Level> dimension) {
		return folder(dimension.identifier().toString());
	}

	public static String folder(String id) {
		String folder = id.replaceAll("[^a-z0-9_.-]", "_");
		return folder.isEmpty() || folder.chars().allMatch(c -> c == '.') ? "_" + folder : folder;
	}
}
