package raccoonman.reterraforged.world.worldgen.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
				List.of("search_radius=32768", "minecraft:plains", "!minecraft:desert", "!removed:old_biome")
		);

		assertEquals(Set.of(plains), result.enabled());
		assertEquals(32768, result.searchRadius());
		assertEquals("true", result.condition().canonical());
		assertEquals(List.of(
				"# ReTerraForged spawn biome configuration",
				"# search_radius is measured in Minecraft blocks from the search origin in each dimension.",
				"# A larger radius can find rarer or more distant biomes, but makes initial world loading slower.",
				"# Valid range: 1024 - 65536; default: 16384",
				"search_radius=32768",
				"# spawn_condition is ANDed with the enabled biome and normal spawn safety checks.",
				"# Boolean grammar: true | false | and(expr,...) | or(expr,...) | not(expr)",
				"# Positions for block/fluid checks: floor (below the player), feet, or head.",
				"# Predicates: block[_tag](position,id), fluid[_tag](position,id), biome[_tag](id),",
				"#             dimension(id), dimension_type[_tag](id)",
				"# IDs and tags use namespace:path and may be supplied by Minecraft, data packs, or mods.",
				"# Soil example: or(block_tag(floor,minecraft:dirt),block_tag(floor,minecraft:sand))",
				"spawn_condition=true",
				"# Remove ! from a biome line to enable it; add ! to disable it.",
				"# If every biome has !, Minecraft's normal overworld spawn selection is used.",
				"",
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
		assertEquals(SpawnBiomeConfig.DEFAULT_SEARCH_RADIUS, result.searchRadius());
		assertEquals("search_radius=16384", result.lines().get(4));
		assertEquals("spawn_condition=true", result.lines().get(12));
		assertEquals("!minecraft:plains", result.lines().get(result.lines().size() - 1));
	}

	@Test
	void spawnConditionIsPreservedAndCanonicalized() {
		ResourceLocation plains = ResourceLocation.parse("minecraft:plains");
		SpawnBiomeConfig.MergeResult result = SpawnBiomeConfig.merge(
				List.of(plains),
				List.of(
						"spawn_condition = AND( block_tag( floor, minecraft:dirt ), NOT( biome_tag(minecraft:is_ocean) ) )",
						"minecraft:plains"
				)
		);

		assertEquals(
				"and(block_tag(floor,minecraft:dirt),not(biome_tag(minecraft:is_ocean)))",
				result.condition().canonical()
		);
		assertEquals("spawn_condition=" + result.condition().canonical(), result.lines().get(12));
		assertEquals(Set.of(plains), result.enabled());
	}

	@Test
	void invalidSpawnConditionCannotSilentlyRemoveTheRestriction() {
		assertThrows(IllegalArgumentException.class, () -> SpawnBiomeConfig.merge(
				List.of(ResourceLocation.parse("minecraft:plains")),
				List.of("spawn_condition=or(block_tag(floor,minecraft:dirt)")
		));
	}

	@Test
	void searchRadiusIsClampedAndInvalidValuesUseTheDefault() {
		SpawnBiomeConfig.MergeResult tooLarge = SpawnBiomeConfig.merge(List.of(), List.of("search_radius=999999"));
		SpawnBiomeConfig.MergeResult tooSmall = SpawnBiomeConfig.merge(List.of(), List.of("search_radius=1"));
		SpawnBiomeConfig.MergeResult invalid = SpawnBiomeConfig.merge(List.of(), List.of("search_radius=far"));

		assertEquals(SpawnBiomeConfig.MAX_SEARCH_RADIUS, tooLarge.searchRadius());
		assertEquals(SpawnBiomeConfig.MIN_SEARCH_RADIUS, tooSmall.searchRadius());
		assertEquals(SpawnBiomeConfig.DEFAULT_SEARCH_RADIUS, invalid.searchRadius());
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
