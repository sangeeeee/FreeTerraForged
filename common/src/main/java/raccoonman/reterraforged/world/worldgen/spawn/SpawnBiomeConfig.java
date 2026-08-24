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
	private static volatile Set<ResourceLocation> installedBiomes = Set.of();

	private SpawnBiomeConfig() {
	}

	public static Set<ResourceLocation> synchronize(RegistryAccess registries) {
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

	private static Set<ResourceLocation> synchronize(Collection<ResourceLocation> available) {
		Path path = path();
		try {
			List<String> existing = Files.isRegularFile(path)
					? Files.readAllLines(path, StandardCharsets.UTF_8)
					: List.of();
			MergeResult result = merge(available, existing);
			if(!existing.equals(result.lines())) {
				writeAtomically(path, result.lines());
			}
			return result.enabled();
		} catch (Exception e) {
			RTFCommon.LOGGER.error("Failed to synchronize spawn biome configuration at {}", path, e);
			return Set.of();
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
		for(String rawLine : existingLines) {
			String line = rawLine.trim();
			if(line.isEmpty()) {
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
		List<String> lines = new ArrayList<>(sorted.size());
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
		return new MergeResult(Set.copyOf(enabled), List.copyOf(lines));
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

	static record MergeResult(Set<ResourceLocation> enabled, List<String> lines) {
	}
}
