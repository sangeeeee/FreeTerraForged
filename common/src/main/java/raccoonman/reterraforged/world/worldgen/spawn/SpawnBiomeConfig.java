package raccoonman.reterraforged.world.worldgen.spawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ConfigUtil;
import raccoonman.reterraforged.platform.ModLoaderUtil;

public final class SpawnBiomeConfig {
	static final int DEFAULT_SEARCH_RADIUS = 16_384;
	static final int MIN_SEARCH_RADIUS = 1_024;
	static final int MAX_SEARCH_RADIUS = 65_536;
	private static final String SEARCH_RADIUS_KEY = "search_radius";
	private static final String SPAWN_CONDITION_KEY = "spawn_condition";
	private static volatile Set<ResourceLocation> installedBiomes = Set.of();

	private SpawnBiomeConfig() {
	}

	public static Settings synchronize(RegistryAccess registries) {
		Set<ResourceLocation> available = new HashSet<>(installedBiomes);
		available.addAll(registries.registryOrThrow(Registries.BIOME).keySet());
		return synchronize(available);
	}

	/**
	 * Runs after the mod loader has discovered and initialized all mod files. At
	 * this point a world-scoped dynamic biome registry does not exist yet, so the
	 * installed data-pack resources are used to create the editable list early.
	 */
	public static void synchronizeInstalledBiomes() {
		Set<ResourceLocation> discovered = discoverInstalledBiomes(ModLoaderUtil.getModDataRoots());
		installedBiomes = Set.copyOf(discovered);
		synchronize(discovered);
		RTFCommon.LOGGER.info("Synchronized {} installed biomes to {}", discovered.size(), path());
	}

	private static Settings synchronize(Collection<ResourceLocation> available) {
		Path path = path();
		try {
			List<String> existing = Files.isRegularFile(path)
					? Files.readAllLines(path, StandardCharsets.UTF_8)
					: List.of();
			MergeResult result = merge(available, existing);
			if(!existing.equals(result.lines())) {
				writeAtomically(path, result.lines());
			}
			return new Settings(result.enabled(), result.searchRadius(), result.condition());
		} catch (Exception e) {
			RTFCommon.LOGGER.error("Failed to synchronize spawn biome configuration at {}", path, e);
			return new Settings(Set.of(), DEFAULT_SEARCH_RADIUS, SpawnCondition.TRUE);
		}
	}

	static Set<ResourceLocation> discoverInstalledBiomes(Collection<Path> dataRoots) {
		Set<ResourceLocation> result = new HashSet<>();
		for(Path dataRoot : dataRoots) {
			try(Stream<Path> paths = Files.walk(dataRoot)) {
				paths.filter(Files::isRegularFile)
						.map(dataRoot::relativize)
						.map(SpawnBiomeConfig::biomeIdFromResource)
						.filter(java.util.Objects::nonNull)
						.forEach(result::add);
			} catch(IOException e) {
				RTFCommon.LOGGER.warn("Failed to scan biome resources under {}", dataRoot, e);
			}
		}
		return result;
	}

	private static ResourceLocation biomeIdFromResource(Path relative) {
		if(relative.getNameCount() < 4
				|| !"worldgen".equals(relative.getName(1).toString())
				|| !"biome".equals(relative.getName(2).toString())) {
			return null;
		}
		String filename = relative.getFileName().toString();
		if(!filename.endsWith(".json")) {
			return null;
		}
		String namespace = relative.getName(0).toString();
		String biomePath = relative.subpath(3, relative.getNameCount()).toString().replace('\\', '/');
		biomePath = biomePath.substring(0, biomePath.length() - ".json".length());
		return ResourceLocation.tryBuild(namespace, biomePath);
	}

	public static Path path() {
		return ConfigUtil.rtf("spawn_biomes.txt");
	}

	static MergeResult merge(Collection<ResourceLocation> available, List<String> existingLines) {
		Map<ResourceLocation, Boolean> previous = new HashMap<>();
		int searchRadius = parseSearchRadius(existingLines);
		SpawnCondition condition = parseCondition(existingLines);
		for(String rawLine : existingLines) {
			String line = rawLine.trim();
			if(line.isEmpty() || line.startsWith("#") || isSettingLine(line)) {
				continue;
			}

			boolean disabled = line.charAt(0) == '!';
			String id = disabled ? line.substring(1).trim() : line;
			ResourceLocation location = ResourceLocation.tryParse(id);
			if(location != null) {
				previous.put(location, !disabled);
			}
		}

		List<ResourceLocation> sorted = new ArrayList<>(available);
		sorted.sort(ResourceLocation::compareTo);
		List<String> lines = new ArrayList<>(sorted.size() + 18);
		lines.add("# ReTerraForged spawn biome configuration");
		lines.add("# search_radius is measured in Minecraft blocks from the search origin in each dimension.");
		lines.add("# A larger radius can find rarer or more distant biomes, but makes initial world loading slower.");
		lines.add("# Valid range: " + MIN_SEARCH_RADIUS + " - " + MAX_SEARCH_RADIUS + "; default: " + DEFAULT_SEARCH_RADIUS);
		lines.add(SEARCH_RADIUS_KEY + "=" + searchRadius);
		lines.add("# spawn_condition is ANDed with the enabled biome and normal spawn safety checks.");
		lines.add("# Boolean grammar: true | false | and(expr,...) | or(expr,...) | not(expr)");
		lines.add("# Positions for block/fluid checks: floor (below the player), feet, or head.");
		lines.add("# Predicates: block[_tag](position,id), fluid[_tag](position,id), biome[_tag](id),");
		lines.add("#             dimension(id), dimension_type[_tag](id)");
		lines.add("# IDs and tags use namespace:path and may be supplied by Minecraft, data packs, or mods.");
		lines.add("# Soil example: or(block_tag(floor,minecraft:dirt),block_tag(floor,minecraft:sand))");
		lines.add(SPAWN_CONDITION_KEY + "=" + condition.canonical());
		lines.add("# Remove ! from a biome line to enable it; add ! to disable it.");
		lines.add("# If every biome has !, Minecraft's normal overworld spawn selection is used.");
		lines.add("");
		Set<ResourceLocation> enabled = new HashSet<>();
		for(ResourceLocation location : sorted) {
			boolean selected = previous.getOrDefault(location, false);
			if(selected) {
				enabled.add(location);
				lines.add(location.toString());
			} else {
				lines.add("!" + location);
			}
		}
		return new MergeResult(Set.copyOf(enabled), searchRadius, condition, List.copyOf(lines));
	}

	private static SpawnCondition parseCondition(List<String> lines) {
		SpawnCondition condition = SpawnCondition.TRUE;
		for(String rawLine : lines) {
			String line = rawLine.trim();
			if(!isConditionLine(line)) {
				continue;
			}
			String value = line.substring(line.indexOf('=') + 1).trim();
			condition = SpawnCondition.parse(value);
		}
		return condition;
	}

	private static int parseSearchRadius(List<String> lines) {
		int radius = DEFAULT_SEARCH_RADIUS;
		for(String rawLine : lines) {
			String line = rawLine.trim();
			if(!isSearchRadiusLine(line)) {
				continue;
			}
			String value = line.substring(line.indexOf('=') + 1).trim();
			try {
				radius = Integer.parseInt(value);
			} catch(NumberFormatException ignored) {
				radius = DEFAULT_SEARCH_RADIUS;
			}
		}
		return Math.max(MIN_SEARCH_RADIUS, Math.min(MAX_SEARCH_RADIUS, radius));
	}

	private static boolean isSearchRadiusLine(String line) {
		int separator = line.indexOf('=');
		return separator >= 0 && SEARCH_RADIUS_KEY.equalsIgnoreCase(line.substring(0, separator).trim());
	}

	private static boolean isConditionLine(String line) {
		int separator = line.indexOf('=');
		return separator >= 0 && SPAWN_CONDITION_KEY.equalsIgnoreCase(line.substring(0, separator).trim());
	}

	private static boolean isSettingLine(String line) {
		return isSearchRadiusLine(line) || isConditionLine(line);
	}

	private static void writeAtomically(Path path, List<String> lines) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		Files.write(temporary, lines, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public record Settings(Set<ResourceLocation> enabledBiomes, int searchRadius, SpawnCondition condition) {
	}

	static record MergeResult(Set<ResourceLocation> enabled, int searchRadius, SpawnCondition condition, List<String> lines) {
	}
}
