package net.kdt.pojavlaunch.modloaders.modpacks.api;


import android.content.Context;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.InstalledModManager;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 *
 */
public interface ModpackApi {

    /**
     * @param searchFilters Filters
     * @param previousPageResult The result from the previous page
     * @return the list of mod items from specified offset
     */
    SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult);

    /**
     * @param searchFilters Filters
     * @return A list of mod items
     */
    default SearchResult searchMod(SearchFilters searchFilters) {
        return searchMod(searchFilters, null);
    }

    /**
     * Fetch the mod details
     * @param item The moditem that was selected
     * @return Detailed data about a mod(pack)
     */
    ModDetail getModDetails(ModItem item);

    /**
     * Download and install the modpack
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    default void handleModpackInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        // Doing this here since when starting installation, the progress does not start immediately
        // which may lead to two concurrent installations (very bad)
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                installModpack(modDetail, selectedVersion);
            }catch (IOException e) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    /**
     * Download a single mod into the selected instance's mods directory.
     */
    void installMod(ModDetail modDetail, int selectedVersion, File instanceDirectory) throws IOException;

    default void handleModInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IOException("No instance selected");
                installMod(modDetail, selectedVersion, instance.getGameDirectory());
            } catch (IOException e) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    /**
     * Installs a mod and, when requested, its required dependencies for the
     * same Minecraft version and loader.
     */
    default void installModWithDependencies(ModDetail modDetail, int selectedVersion,
                                             File instanceDirectory) throws IOException {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) throw new IOException("No instance selected");
        String mcVersion = modDetail.mcVersionNames[selectedVersion];
        String loader = modDetail.loaderNames != null && selectedVersion < modDetail.loaderNames.length
                ? modDetail.loaderNames[selectedVersion] : "Unknown";
        installModWithDependenciesRecursive(modDetail, selectedVersion, instanceDirectory,
                mcVersion, loader, new HashSet<String>());
    }

    default void installModWithDependenciesRecursive(ModDetail detail, int version,
                                                       File instanceDirectory, String mcVersion,
                                                       String loader, Set<String> visited) throws IOException {
        String visitKey = detail.apiSource + ":" + detail.id;
        if (!visited.add(visitKey)) return;

        String[] dependencyIds = detail.versionDependencyIds != null && version < detail.versionDependencyIds.length
                ? detail.versionDependencyIds[version] : null;
        if (dependencyIds != null) {
            for (String dependencyId : dependencyIds) {
                if (dependencyId == null || dependencyId.isEmpty()) continue;

                if (isDependencyInstalled(instanceDirectory, dependencyId)) continue;

                ModItem dependencyItem = new ModItem(detail.apiSource, false, dependencyId,
                        dependencyId, "", "");
                dependencyItem.contentType = Constants.CONTENT_MOD;
                ModDetail dependencyDetail = getModDetails(dependencyItem);
                if (dependencyDetail == null) {
                    throw new IOException("Could not resolve required dependency: " + dependencyId);
                }

                int dependencyVersion = findCompatibleVersion(dependencyDetail, mcVersion, loader);
                if (dependencyVersion < 0) {
                    throw new IOException("No compatible version found for required dependency: "
                            + dependencyDetail.title);
                }

                installModWithDependenciesRecursive(dependencyDetail, dependencyVersion,
                        instanceDirectory, mcVersion, loader, visited);
            }
        }

        installMod(detail, version, instanceDirectory);
    }

    default int findCompatibleVersion(ModDetail detail, String mcVersion, String loader) {
        for (int i = 0; i < detail.versionNames.length; i++) {
            if (!containsMinecraftVersion(detail.mcVersionNames[i], mcVersion)) continue;
            if (loader == null || "Unknown".equalsIgnoreCase(loader)) return i;
            if (detail.loaderNames != null && i < detail.loaderNames.length
                    && loader.equalsIgnoreCase(detail.loaderNames[i])) return i;
        }
        return -1;
    }

    default boolean containsMinecraftVersion(String versions, String requested) {
        if (versions == null || requested == null) return false;
        for (String version : versions.split(",\\s*")) {
            if (requested.equals(version.trim())) return true;
        }
        return false;
    }

    default boolean isDependencyInstalled(File instanceDirectory, String dependencyId) {
        File modsDirectory = new File(instanceDirectory, "mods");
        com.google.gson.JsonArray entries = InstalledModManager.read(modsDirectory);
        for (int i = 0; i < entries.size(); i++) {
            com.google.gson.JsonObject entry = entries.get(i).getAsJsonObject();
            if (entry.has("id") && dependencyId.equals(entry.get("id").getAsString())) return true;
        }
        return false;
    }

    LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException;

    /**
     * Install the mod(pack).
     * May require the download of additional files.
     * May requires launching the installation of a modloader
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException;
}
