package raccoonman.reterraforged.world.worldgen.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class SpawnConditionTest {
	@Test
	void nestedBooleanTreeUsesAndOrAndNot() {
		SpawnCondition condition = SpawnCondition.parse("""
				and(
				  or(block_tag(floor, minecraft:dirt), block_tag(floor, minecraft:sand)),
				  not(biome_tag(minecraft:is_ocean)),
				  dimension(minecraft:overworld)
				)
				""");
		FakeEvaluation evaluation = new FakeEvaluation();
		evaluation.matches.add("block_tag:floor:minecraft:dirt");
		evaluation.matches.add("dimension:minecraft:overworld");

		assertTrue(condition.test(evaluation));
		evaluation.matches.add("biome_tag:minecraft:is_ocean");
		assertFalse(condition.test(evaluation));
		assertEquals(
				"and(or(block_tag(floor,minecraft:dirt),block_tag(floor,minecraft:sand)),not(biome_tag(minecraft:is_ocean)),dimension(minecraft:overworld))",
				condition.canonical()
		);
	}

	@Test
	void everySupportedLeafCanParticipateInATree() {
		SpawnCondition condition = SpawnCondition.parse("""
				and(
				  block(feet,minecraft:air),
				  fluid_tag(floor,minecraft:water),
				  fluid(head,minecraft:empty),
				  biome(minecraft:plains),
				  dimension_type_tag(minecraft:natural),
				  dimension_type(minecraft:overworld)
				)
				""");
		FakeEvaluation evaluation = new FakeEvaluation();
		evaluation.matches.addAll(Set.of(
				"block:feet:minecraft:air",
				"fluid_tag:floor:minecraft:water",
				"fluid:head:minecraft:empty",
				"biome:minecraft:plains",
				"dimension_type_tag:minecraft:natural",
				"dimension_type:minecraft:overworld"
		));

		assertTrue(condition.test(evaluation));
	}

	@Test
	void malformedOrUnboundedTreesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("and()"));
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("not(true,false)"));
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("unknown(true)"));
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("block_tag(ground,minecraft:dirt)"));
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("biome_tag(not a resource id)"));
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse("true trailing"));

		String tooDeep = "not(".repeat(34) + "true" + ")".repeat(34);
		assertThrows(IllegalArgumentException.class, () -> SpawnCondition.parse(tooDeep));
	}

	private static final class FakeEvaluation implements SpawnCondition.Evaluation {
		private final Set<String> matches = new HashSet<>();

		@Override
		public boolean block(SpawnCondition.Position position, ResourceLocation id, boolean tag) {
			return this.matches.contains(key(tag ? "block_tag" : "block", position, id));
		}

		@Override
		public boolean fluid(SpawnCondition.Position position, ResourceLocation id, boolean tag) {
			return this.matches.contains(key(tag ? "fluid_tag" : "fluid", position, id));
		}

		@Override
		public boolean biome(ResourceLocation id, boolean tag) {
			return this.matches.contains((tag ? "biome_tag:" : "biome:") + id);
		}

		@Override
		public boolean dimension(ResourceLocation id) {
			return this.matches.contains("dimension:" + id);
		}

		@Override
		public boolean dimensionType(ResourceLocation id, boolean tag) {
			return this.matches.contains((tag ? "dimension_type_tag:" : "dimension_type:") + id);
		}

		private static String key(String type, SpawnCondition.Position position, ResourceLocation id) {
			return type + ":" + position.name().toLowerCase(java.util.Locale.ROOT) + ":" + id;
		}
	}
}
