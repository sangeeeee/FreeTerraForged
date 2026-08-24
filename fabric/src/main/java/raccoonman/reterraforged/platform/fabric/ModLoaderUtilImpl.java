package raccoonman.reterraforged.platform.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import net.fabricmc.loader.api.FabricLoader;

public final class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static Collection<Path> getModDataRoots() {
		return FabricLoader.getInstance().getAllMods().stream()
				.flatMap(container -> container.getRootPaths().stream())
				.map(root -> root.resolve("data"))
				.filter(Files::isDirectory)
				.toList();
	}
}
