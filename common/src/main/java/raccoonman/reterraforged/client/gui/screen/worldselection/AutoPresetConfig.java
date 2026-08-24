package raccoonman.reterraforged.client.gui.screen.worldselection;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.GsonHelper;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ConfigUtil;

public record AutoPresetConfig(boolean enabled, String preset, String worldTypeName) {
	public static final Path PATH = ConfigUtil.rtf("auto_preset.json");
	private static final String DEFAULT_CONTENT = """
			{
			  "enabled": false,
			  "preset": "",
			  "worldTypeName": ""
			}
			""";

	public static AutoPresetConfig load() throws IOException {
		createDefaultIfMissing();
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			return new AutoPresetConfig(
					GsonHelper.getAsBoolean(json, "enabled", false),
					GsonHelper.getAsString(json, "preset", ""),
					GsonHelper.getAsString(json, "worldTypeName", "")
			);
		}
	}

	public static void ensureCreated() {
		try {
			createDefaultIfMissing();
		} catch (IOException e) {
			RTFCommon.LOGGER.error("Failed to create default auto_preset.json", e);
		}
	}

	private static void createDefaultIfMissing() throws IOException {
		if(Files.exists(PATH)) {
			return;
		}

		Files.createDirectories(PATH.getParent());
		Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
		Files.writeString(temporary, DEFAULT_CONTENT, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, PATH);
		}
	}
}
