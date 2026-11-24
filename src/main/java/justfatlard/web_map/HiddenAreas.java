package justfatlard.web_map;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Areas the map never shows: nothing in them is drawn and no marker in them is listed, so a secret
 * base stays secret. Each is hidden in a shape made to pass for map nobody has explored yet, which is
 * ragged and made of whole chunks, rather than a circle or a blur that says something is there: a
 * lumpy blob, off-centre from the spot it hides by a random amount, with a ragged chunk-by-chunk edge,
 * that always covers the asked radius around the spot. The shape is seeded by the world's seed and
 * the area, so it is the same every time and cannot be worked out from the area's name alone.
 *
 * <p>Immutable; swapped whole when the areas change, so any thread may ask.
 */
final class HiddenAreas {
	/** One area as the config keeps it. */
	static final class Area {
		String name;
		String dimension;
		int x;
		int z;
		int radius;

		Area() {}

		Area(String name, String dimension, int x, int z, int radius) {
			this.name = name;
			this.dimension = dimension;
			this.x = x;
			this.z = z;
			this.radius = radius;
		}
	}

	private static final int WAVES = 4;
	/** Coarse noise along the edge, in cells this many chunks across, for inlets and bulges. */
	private static final int NOISE_CELL = 4;

	/** A round lump with a wavy edge; an area's shape is a few of them run together. */
	private record Lump(double x, double z, double radius, double[] amplitudes, double[] phases) {
		double reach(double px, double pz) {
			double angle = Math.atan2(pz - z, px - x);
			double reach = 1;
			for (int k = 0; k < WAVES; k++) reach += amplitudes[k] * Math.sin((k + 1) * angle + phases[k]);
			return radius * reach - Math.hypot(px - x, pz - z);
		}

		static Lump random(SplittableRandom random, double x, double z, double radius, double wobble) {
			double[] amplitudes = new double[WAVES], phases = new double[WAVES];
			for (int k = 0; k < WAVES; k++) {
				amplitudes[k] = random.nextDouble() * wobble / (k + 1);
				phases[k] = random.nextDouble() * Math.PI * 2;
			}
			return new Lump(x, z, radius, amplitudes, phases);
		}
	}

	/** An area's shape, worked out once from its seed. */
	private record Shape(String folder, double centreX, double centreZ, double radius, List<Lump> lumps, double noise, long seed) {

		boolean covers(int chunkX, int chunkZ) {
			double x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
			// A ragged edge: each chunk's own nudge of up to a chunk and a half
			double nudge = hash(seed, chunkX, chunkZ) * 24;
			// The asked radius is measured to the chunk's nearest block, so every block within it is covered
			double nearX = Math.clamp(centreX, chunkX * 16, chunkX * 16 + 16), nearZ = Math.clamp(centreZ, chunkZ * 16, chunkZ * 16 + 16);
			if (Math.hypot(nearX - centreX, nearZ - centreZ) <= radius + nudge) return true;
			double edge = (smoothNoise(chunkX, chunkZ) - 0.5) * 2 * noise + nudge - 12;
			for (Lump lump : lumps) if (lump.reach(x, z) + edge > 0) return true;
			return false;
		}

		/** Value noise over cells of chunks, eased between the cells' corners, from 0 to 1. */
		private double smoothNoise(int chunkX, int chunkZ) {
			int cellX = Math.floorDiv(chunkX, NOISE_CELL), cellZ = Math.floorDiv(chunkZ, NOISE_CELL);
			double fx = ease(Math.floorMod(chunkX, NOISE_CELL) / (double) NOISE_CELL), fz = ease(Math.floorMod(chunkZ, NOISE_CELL) / (double) NOISE_CELL);
			long cells = seed * 31 + 7;
			double top = lerp(hash(cells, cellX, cellZ), hash(cells, cellX + 1, cellZ), fx);
			double bottom = lerp(hash(cells, cellX, cellZ + 1), hash(cells, cellX + 1, cellZ + 1), fx);
			return lerp(top, bottom, fz);
		}

		/** The chunks that could be covered, as least x, least z, greatest x, greatest z. */
		int[] chunkBounds() {
			double minX = centreX - radius, minZ = centreZ - radius, maxX = centreX + radius, maxZ = centreZ + radius;
			for (Lump lump : lumps) {
				double reach = lump.radius * 2 + noise;
				minX = Math.min(minX, lump.x - reach);
				minZ = Math.min(minZ, lump.z - reach);
				maxX = Math.max(maxX, lump.x + reach);
				maxZ = Math.max(maxZ, lump.z + reach);
			}
			return new int[] {(int) Math.floor(minX / 16) - 2, (int) Math.floor(minZ / 16) - 2, (int) Math.ceil(maxX / 16) + 2, (int) Math.ceil(maxZ / 16) + 2};
		}
	}

	private static double hash(long seed, int x, int z) {
		return new SplittableRandom(seed ^ (x * 341873128712L + z * 132897987541L)).nextDouble();
	}

	private static double ease(double t) {
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	static final HiddenAreas NONE = new HiddenAreas(List.of(), 0);

	private final List<Shape> shapes = new ArrayList<>();

	HiddenAreas(List<Area> areas, long worldSeed) {
		for (Area area : areas) {
			if (area == null || area.name == null || area.dimension == null || area.radius <= 0) continue;
			long seed = worldSeed ^ area.name.hashCode() * 0x9E3779B97F4A7C15L ^ ((long) area.x << 32 | area.z & 0xFFFFFFFFL);
			SplittableRandom random = new SplittableRandom(seed);
			// The main lump sits off the spot by up to half the radius, so the hole's middle is not the base
			double offset = random.nextDouble() * area.radius * 0.5, direction = random.nextDouble() * Math.PI * 2;
			double mainX = area.x + Math.cos(direction) * offset, mainZ = area.z + Math.sin(direction) * offset;
			double mainRadius = area.radius * (1.2 + random.nextDouble() * 0.5);
			List<Lump> lumps = new ArrayList<>();
			lumps.add(Lump.random(random, mainX, mainZ, mainRadius, 0.45));
			// One to three lobes off its edge, as where someone wandered a little way out
			int lobes = 1 + random.nextInt(3);
			for (int i = 0; i < lobes; i++) {
				double angle = random.nextDouble() * Math.PI * 2, out = mainRadius * (0.7 + random.nextDouble() * 0.4);
				lumps.add(Lump.random(random, mainX + Math.cos(angle) * out, mainZ + Math.sin(angle) * out,
					mainRadius * (0.25 + random.nextDouble() * 0.35), 0.3));
			}
			shapes.add(new Shape(Dimensions.folder(area.dimension), area.x + 0.5, area.z + 0.5, area.radius, lumps, mainRadius * 0.3, seed));
		}
	}

	boolean covers(String folder, int chunkX, int chunkZ) {
		for (Shape shape : shapes) if (shape.folder.equals(folder) && shape.covers(chunkX, chunkZ)) return true;
		return false;
	}

	boolean coversBlock(String folder, int x, int z) {
		return covers(folder, x >> 4, z >> 4);
	}

	/**
	 * Each covered chunk, as dimension folder and chunk x and z, a region at a time and only in regions
	 * the filter lets through, so a large area over mostly unexplored land costs little; after each
	 * region it visited, {@code afterRegion} runs.
	 */
	void forEachChunk(RegionFilter regions, ChunkVisitor visitor, Runnable afterRegion) throws Exception {
		for (Shape shape : shapes) {
			int[] bounds = shape.chunkBounds();
			for (int rx = Math.floorDiv(bounds[0], 32); rx <= Math.floorDiv(bounds[2], 32); rx++) {
				for (int rz = Math.floorDiv(bounds[1], 32); rz <= Math.floorDiv(bounds[3], 32); rz++) {
					if (!regions.has(shape.folder, rx, rz)) continue;
					for (int x = Math.max(bounds[0], rx * 32); x <= Math.min(bounds[2], rx * 32 + 31); x++) {
						for (int z = Math.max(bounds[1], rz * 32); z <= Math.min(bounds[3], rz * 32 + 31); z++) {
							if (shape.covers(x, z)) visitor.visit(shape.folder, x, z);
						}
					}
					afterRegion.run();
				}
			}
		}
	}

	interface RegionFilter {
		boolean has(String folder, int regionX, int regionZ) throws Exception;
	}

	interface ChunkVisitor {
		void visit(String folder, int chunkX, int chunkZ) throws Exception;
	}

	/** Whether nothing is hidden. */
	boolean isEmpty() {
		return shapes.isEmpty();
	}
}
