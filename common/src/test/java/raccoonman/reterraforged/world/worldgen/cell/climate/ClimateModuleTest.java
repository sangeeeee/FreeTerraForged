package raccoonman.reterraforged.world.worldgen.cell.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.ClimateSettings;

class ClimateModuleTest {
	private static final float EPSILON = 0.000001F;

	@Test
	void dryPresetMaximumIsTheFinalMoistureLimit() {
		ClimateSettings.RangeValue dry = range(0.0F, 0.25F, 0.0F);

		assertEquals(0.25F, ClimateModule.applyMoisturePreset(0.9F, 0.0F, dry), EPSILON);
		assertEquals(0.25F, ClimateModule.applyMoisturePreset(0.9F, 0.5F, dry), EPSILON);
		assertTrue(ClimateModule.applyMoisturePreset(0.9F, 1.0F, dry) <= 0.25F);
	}

	@Test
	void wetPresetMinimumIsTheFinalMoistureLimit() {
		ClimateSettings.RangeValue wet = range(0.75F, 1.0F, 0.0F);

		assertEquals(0.75F, ClimateModule.applyMoisturePreset(0.1F, 0.0F, wet), EPSILON);
		assertEquals(0.75F, ClimateModule.applyMoisturePreset(0.1F, 0.5F, wet), EPSILON);
		assertEquals(0.75F, ClimateModule.applyMoisturePreset(0.1F, 1.0F, wet), EPSILON);
	}

	@Test
	void presetBiasIsAppliedAfterContinentalityAdjustment() {
		ClimateSettings.RangeValue neutral = range(0.0F, 1.0F, 0.0F);
		ClimateSettings.RangeValue dry = range(0.0F, 1.0F, -0.4F);

		for (float continentEdge : new float[] { 0.0F, 0.5F, 0.75F, 1.0F }) {
			float neutralValue = ClimateModule.applyMoisturePreset(0.5F, continentEdge, neutral);
			float dryValue = ClimateModule.applyMoisturePreset(0.5F, continentEdge, dry);
			assertEquals(0.2F, neutralValue - dryValue, EPSILON);
		}
	}

	private static ClimateSettings.RangeValue range(float min, float max, float bias) {
		return new ClimateSettings.RangeValue(0, 1, 1, min, max, bias);
	}
}
