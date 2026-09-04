package raccoonman.reterraforged.world.worldgen.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * A small, deliberately bounded expression language for additional spawn
 * requirements. The enabled-biome list and the normal safety checks are always
 * evaluated separately and ANDed with this expression.
 */
final class SpawnCondition {
	private static final int MAX_SOURCE_LENGTH = 8_192;
	private static final int MAX_DEPTH = 32;
	private static final int MAX_NODES = 256;
	static final SpawnCondition TRUE = new SpawnCondition(Constant.TRUE);

	private final Node root;
	private final String canonical;

	private SpawnCondition(Node root) {
		this.root = root;
		this.canonical = root.canonical();
	}

	static SpawnCondition parse(String source) {
		if(source == null) {
			return TRUE;
		}
		String trimmed = source.trim();
		if(trimmed.isEmpty()) {
			throw new IllegalArgumentException("spawn_condition cannot be empty");
		}
		if(trimmed.length() > MAX_SOURCE_LENGTH) {
			throw new IllegalArgumentException("spawn_condition is longer than " + MAX_SOURCE_LENGTH + " characters");
		}
		return new SpawnCondition(new Parser(trimmed).parse());
	}

	boolean test(ServerLevel level, BlockPos position) {
		if(this.root == Constant.TRUE) {
			return true;
		}
		if(this.root == Constant.FALSE) {
			return false;
		}
		return this.root.test(new WorldEvaluation(level, position));
	}

	boolean test(Evaluation evaluation) {
		return this.root.test(evaluation);
	}

	String canonical() {
		return this.canonical;
	}

	interface Evaluation {
		boolean block(Position position, ResourceLocation id, boolean tag);

		boolean fluid(Position position, ResourceLocation id, boolean tag);

		boolean biome(ResourceLocation id, boolean tag);

		boolean dimension(ResourceLocation id);

		boolean dimensionType(ResourceLocation id, boolean tag);
	}

	enum Position {
		FLOOR,
		FEET,
		HEAD;

		BlockPos resolve(BlockPos spawn) {
			return switch(this) {
				case FLOOR -> spawn.below();
				case FEET -> spawn;
				case HEAD -> spawn.above();
			};
		}

		static Position parse(String value) {
			try {
				return valueOf(value.toUpperCase(Locale.ROOT));
			} catch(IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown block position '" + value + "'; expected floor, feet, or head");
			}
		}
	}

	private interface Node {
		boolean test(Evaluation evaluation);

		String canonical();
	}

	private enum Constant implements Node {
		TRUE(true, "true"),
		FALSE(false, "false");

		private final boolean value;
		private final String canonical;

		Constant(boolean value, String canonical) {
			this.value = value;
			this.canonical = canonical;
		}

		@Override
		public boolean test(Evaluation evaluation) {
			return this.value;
		}

		@Override
		public String canonical() {
			return this.canonical;
		}
	}

	private record And(List<Node> children) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			for(Node child : this.children) {
				if(!child.test(evaluation)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public String canonical() {
			return call("and", this.children);
		}
	}

	private record Or(List<Node> children) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			for(Node child : this.children) {
				if(child.test(evaluation)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String canonical() {
			return call("or", this.children);
		}
	}

	private record Not(Node child) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return !this.child.test(evaluation);
		}

		@Override
		public String canonical() {
			return "not(" + this.child.canonical() + ")";
		}
	}

	private record BlockMatch(Position position, ResourceLocation id, boolean tag) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return evaluation.block(this.position, this.id, this.tag);
		}

		@Override
		public String canonical() {
			return (this.tag ? "block_tag" : "block") + "(" + positionName(this.position) + "," + this.id + ")";
		}
	}

	private record FluidMatch(Position position, ResourceLocation id, boolean tag) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return evaluation.fluid(this.position, this.id, this.tag);
		}

		@Override
		public String canonical() {
			return (this.tag ? "fluid_tag" : "fluid") + "(" + positionName(this.position) + "," + this.id + ")";
		}
	}

	private record BiomeMatch(ResourceLocation id, boolean tag) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return evaluation.biome(this.id, this.tag);
		}

		@Override
		public String canonical() {
			return (this.tag ? "biome_tag" : "biome") + "(" + this.id + ")";
		}
	}

	private record DimensionMatch(ResourceLocation id) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return evaluation.dimension(this.id);
		}

		@Override
		public String canonical() {
			return "dimension(" + this.id + ")";
		}
	}

	private record DimensionTypeMatch(ResourceLocation id, boolean tag) implements Node {
		@Override
		public boolean test(Evaluation evaluation) {
			return evaluation.dimensionType(this.id, this.tag);
		}

		@Override
		public String canonical() {
			return (this.tag ? "dimension_type_tag" : "dimension_type") + "(" + this.id + ")";
		}
	}

	private static String call(String name, List<Node> children) {
		StringJoiner joiner = new StringJoiner(",", name + "(", ")");
		children.stream().map(Node::canonical).forEach(joiner::add);
		return joiner.toString();
	}

	private static String positionName(Position position) {
		return position.name().toLowerCase(Locale.ROOT);
	}

	private static final class WorldEvaluation implements Evaluation {
		private final ServerLevel level;
		private final BlockPos spawn;

		private WorldEvaluation(ServerLevel level, BlockPos spawn) {
			this.level = level;
			this.spawn = spawn;
		}

		@Override
		public boolean block(Position position, ResourceLocation id, boolean tag) {
			BlockState state = this.level.getBlockState(position.resolve(this.spawn));
			if(tag) {
				return state.is(TagKey.create(Registries.BLOCK, id));
			}
			ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
			return state.is(key);
		}

		@Override
		public boolean fluid(Position position, ResourceLocation id, boolean tag) {
			FluidState state = this.level.getFluidState(position.resolve(this.spawn));
			if(tag) {
				return state.is(TagKey.create(Registries.FLUID, id));
			}
			ResourceKey<Fluid> key = ResourceKey.create(Registries.FLUID, id);
			return state.getType().builtInRegistryHolder().is(key);
		}

		@Override
		public boolean biome(ResourceLocation id, boolean tag) {
			if(tag) {
				return this.level.getBiome(this.spawn).is(TagKey.create(Registries.BIOME, id));
			}
			ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
			return this.level.getBiome(this.spawn).is(key);
		}

		@Override
		public boolean dimension(ResourceLocation id) {
			return this.level.dimension().location().equals(id);
		}

		@Override
		public boolean dimensionType(ResourceLocation id, boolean tag) {
			if(tag) {
				return this.level.dimensionTypeRegistration().is(TagKey.create(Registries.DIMENSION_TYPE, id));
			}
			ResourceKey<DimensionType> key = ResourceKey.create(Registries.DIMENSION_TYPE, id);
			return this.level.dimensionTypeRegistration().is(key);
		}
	}

	private static final class Parser {
		private final String source;
		private int cursor;
		private int nodes;

		private Parser(String source) {
			this.source = source;
		}

		private Node parse() {
			Node result = this.expression(0);
			this.skipWhitespace();
			if(this.cursor != this.source.length()) {
				throw this.error("Unexpected trailing input");
			}
			return result;
		}

		private Node expression(int depth) {
			if(depth > MAX_DEPTH) {
				throw this.error("Expression nesting exceeds " + MAX_DEPTH);
			}
			if(++this.nodes > MAX_NODES) {
				throw this.error("Expression contains more than " + MAX_NODES + " nodes");
			}

			String name = this.name().toLowerCase(Locale.ROOT);
			if(name.equals("true")) {
				return Constant.TRUE;
			}
			if(name.equals("false")) {
				return Constant.FALSE;
			}
			this.expect('(');
			return switch(name) {
				case "and" -> new And(this.expressions(depth, "and"));
				case "or" -> new Or(this.expressions(depth, "or"));
				case "not" -> this.not(depth);
				case "block" -> this.block(false);
				case "block_tag" -> this.block(true);
				case "fluid" -> this.fluid(false);
				case "fluid_tag" -> this.fluid(true);
				case "biome" -> this.biome(false);
				case "biome_tag" -> this.biome(true);
				case "dimension" -> this.dimension();
				case "dimension_type" -> this.dimensionType(false);
				case "dimension_type_tag" -> this.dimensionType(true);
				default -> throw this.error("Unknown condition function '" + name + "'");
			};
		}

		private List<Node> expressions(int depth, String function) {
			List<Node> children = new ArrayList<>();
			this.skipWhitespace();
			if(this.peek(')')) {
				throw this.error(function + " requires at least one child expression");
			}
			while(true) {
				children.add(this.expression(depth + 1));
				this.skipWhitespace();
				if(this.take(')')) {
					return List.copyOf(children);
				}
				this.expect(',');
			}
		}

		private Node not(int depth) {
			Node child = this.expression(depth + 1);
			this.expect(')');
			return new Not(child);
		}

		private Node block(boolean tag) {
			Position position = Position.parse(this.argument());
			this.expect(',');
			ResourceLocation id = this.resourceLocation();
			this.expect(')');
			return new BlockMatch(position, id, tag);
		}

		private Node fluid(boolean tag) {
			Position position = Position.parse(this.argument());
			this.expect(',');
			ResourceLocation id = this.resourceLocation();
			this.expect(')');
			return new FluidMatch(position, id, tag);
		}

		private Node biome(boolean tag) {
			ResourceLocation id = this.resourceLocation();
			this.expect(')');
			return new BiomeMatch(id, tag);
		}

		private Node dimension() {
			ResourceLocation id = this.resourceLocation();
			this.expect(')');
			return new DimensionMatch(id);
		}

		private Node dimensionType(boolean tag) {
			ResourceLocation id = this.resourceLocation();
			this.expect(')');
			return new DimensionTypeMatch(id, tag);
		}

		private ResourceLocation resourceLocation() {
			String value = this.argument();
			ResourceLocation id = ResourceLocation.tryParse(value);
			if(id == null) {
				throw this.error("Invalid resource location '" + value + "'");
			}
			return id;
		}

		private String name() {
			this.skipWhitespace();
			int start = this.cursor;
			while(this.cursor < this.source.length()) {
				char c = this.source.charAt(this.cursor);
				if(!Character.isLetterOrDigit(c) && c != '_') {
					break;
				}
				this.cursor++;
			}
			if(start == this.cursor) {
				throw this.error("Expected a condition name");
			}
			return this.source.substring(start, this.cursor);
		}

		private String argument() {
			this.skipWhitespace();
			int start = this.cursor;
			while(this.cursor < this.source.length()) {
				char c = this.source.charAt(this.cursor);
				if(Character.isWhitespace(c) || c == ',' || c == ')') {
					break;
				}
				this.cursor++;
			}
			if(start == this.cursor) {
				throw this.error("Expected an argument");
			}
			return this.source.substring(start, this.cursor);
		}

		private void expect(char expected) {
			this.skipWhitespace();
			if(!this.take(expected)) {
				throw this.error("Expected '" + expected + "'");
			}
		}

		private boolean peek(char expected) {
			return this.cursor < this.source.length() && this.source.charAt(this.cursor) == expected;
		}

		private boolean take(char expected) {
			if(this.peek(expected)) {
				this.cursor++;
				return true;
			}
			return false;
		}

		private void skipWhitespace() {
			while(this.cursor < this.source.length() && Character.isWhitespace(this.source.charAt(this.cursor))) {
				this.cursor++;
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(message + " at character " + this.cursor + " in spawn_condition");
		}
	}
}
