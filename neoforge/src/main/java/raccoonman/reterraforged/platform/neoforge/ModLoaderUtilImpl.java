package raccoonman.reterraforged.platform.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

public class ModLoaderUtilImpl {
	
	public static boolean isLoaded(String modId) {
		return LoadingModList.get().getModFileById(modId) != null;
	}

	public static Collection<Path> getModDataRoots() {
		Collection<Path> roots = new ArrayList<>();
		ModList.get().forEachModFile(modFile -> {
			Path data = modFile.findResource("data");
			if(Files.isDirectory(data)) {
				roots.add(data);
			}
		});
		return roots;
	}
}
