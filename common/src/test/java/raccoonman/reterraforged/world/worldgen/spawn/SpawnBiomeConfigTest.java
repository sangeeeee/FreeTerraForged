package raccoonman.reterraforged.world.worldgen.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.ResourceLocation;

class SpawnBiomeConfigTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void newBiomesAreDisabledAndExistingChoicesArePreserved() {
		ResourceLocation desert = ResourceLocation.parse("minecraft:desert");
		ResourceLocation plains = ResourceLocation.parse("minecraft:plains");
		ResourceLocation modded = ResourceLocation.parse("example:crystal_fields");

		SpawnBiomeConfig.MergeResult result = SpawnBiomeConfig.merge(
				List.of(plains, modded, desert),
				List.of("minecraft:plains", "!minecraft:desert", "!removed:old_biome")
		);

		assertEquals(Set.of(plains), result.enabled());
		assertEquals(List.of(
				"!example:crystal_fields",
				"!minecraft:desert",
				"minecraft:plains"
		), result.lines());
	}

	@Test
	void invalidAndDuplicateLinesAreHandledDeterministically() {
		ResourceLocation plains = ResourceLocation.parse("minecraft:plains");
		SpawnBiomeConfig.MergeResult result = SpawnBiomeConfig.merge(
				List.of(plains),
				List.of("not a resource id", "minecraft:plains", "!minecraft:plains")
		);

		assertEquals(Set.of(), result.enabled());
		assertEquals(List.of("!minecraft:plains"), result.lines());
	}

	@Test
	void installedBiomeResourcesAreDiscoveredBeforeAWorldExists() throws IOException {
		Path data = this.temporaryDirectory.resolve("data");
		Path plains = data.resolve("minecraft/worldgen/biome/plains.json");
		Path nested = data.resolve("example/worldgen/biome/caves/crystal.json");
		Path tag = data.resolve("example/tags/worldgen/biome/not_a_biome.json");
		Files.createDirectories(plains.getParent());
		Files.createDirectories(nested.getParent());
		Files.createDirectories(tag.getParent());
		Files.writeString(plains, "{}");
		Files.writeString(nested, "{}");
		Files.writeString(tag, "{}");

		assertEquals(Set.of(
				ResourceLocation.parse("minecraft:plains"),
				ResourceLocation.parse("example:caves/crystal")
		), SpawnBiomeConfig.discoverInstalledBiomes(List.of(data)));
	}
}
