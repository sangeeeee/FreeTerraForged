package raccoonman.reterraforged.platform;

import java.nio.file.Path;
import java.util.Collection;

import dev.architectury.injectables.annotations.ExpectPlatform;

public class ModLoaderUtil {
	
	@ExpectPlatform
	public static boolean isLoaded(String modId) {
		throw new IllegalStateException();
	}

	@ExpectPlatform
	public static Collection<Path> getModDataRoots() {
		throw new IllegalStateException();
	}
}
