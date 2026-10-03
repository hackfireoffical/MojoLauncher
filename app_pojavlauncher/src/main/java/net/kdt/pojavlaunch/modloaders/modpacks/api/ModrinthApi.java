package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.Lwjgl3ifyUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.FabriclikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.ForgelikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.Lwjgl3ifyLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        String projectType = searchFilters.isModpack ? Constants.CONTENT_MODPACK : searchFilters.contentType;
        facetString.append(String.format("[\"project_type:%s\"]", projectType));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        params.put("index", searchFilters.sort == null ? "relevance" : searchFilters.sort);
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hit.get("project_type").getAsString().equals("modpack"),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            );
            items[i].contentType = hit.get("project_type").getAsString();
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items;
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {

        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;

        // Versions without a downloadable file are skipped instead of crashing the whole list
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> mcNames = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> hashes = new ArrayList<>();
        ArrayList<String> loaders = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();

        for (int i=0; i<response.size(); ++i) {
            if (!response.get(i).isJsonObject()) continue;
            JsonObject version = response.get(i).getAsJsonObject();
            JsonObject file = pickPrimaryFile(version);
            if (file == null) continue;
            JsonElement urlElement = file.get("url");
            if (urlElement == null || urlElement.isJsonNull()) continue;

            String name = getString(version, "name");
            if (name == null || name.isEmpty()) name = getString(version, "version_number");
            if (name == null || name.isEmpty()) name = "Unnamed version";
            names.add(name);

            StringBuilder mcVersionBuilder = new StringBuilder();
            JsonArray gameVersions = version.has("game_versions") && version.get("game_versions").isJsonArray()
                    ? version.getAsJsonArray("game_versions") : null;
            if (gameVersions != null) {
                for (JsonElement gameVersion : gameVersions) {
                    if (mcVersionBuilder.length() > 0) mcVersionBuilder.append(", ");
                    mcVersionBuilder.append(gameVersion.getAsString());
                }
            }
            mcNames.add(mcVersionBuilder.toString());

            urls.add(urlElement.getAsString());

            // Keep every loader the version supports (e.g. "forge, neoforge") so that the UI can
            // match the active instance's loader against any of them, not just the first one.
            StringBuilder loaderBuilder = new StringBuilder();
            JsonArray loaderArray = version.has("loaders") && version.get("loaders").isJsonArray()
                    ? version.getAsJsonArray("loaders") : null;
            if (loaderArray != null) {
                for (JsonElement loader : loaderArray) {
                    if (loaderBuilder.length() > 0) loaderBuilder.append(", ");
                    loaderBuilder.append(loader.getAsString());
                }
            }
            loaders.add(loaderBuilder.length() > 0 ? loaderBuilder.toString() : "Unknown");

            JsonElement size = file.get("size");
            sizes.add(size != null && !size.isJsonNull() ? size.getAsLong() : -1L);

            // Assume there may not be hashes, in case the API changes
            String sha1 = null;
            JsonElement hashesElement = file.get("hashes");
            if (hashesElement != null && hashesElement.isJsonObject()) {
                JsonElement sha1Element = hashesElement.getAsJsonObject().get("sha1");
                if (sha1Element != null && !sha1Element.isJsonNull()) sha1 = sha1Element.getAsString();
            }
            hashes.add(sha1);
        }

        long[] sizeArray = new long[sizes.size()];
        for (int i = 0; i < sizeArray.length; i++) sizeArray[i] = sizes.get(i);

        return new ModDetail(item,
                names.toArray(new String[0]),
                mcNames.toArray(new String[0]),
                urls.toArray(new String[0]),
                hashes.toArray(new String[0]),
                loaders.toArray(new String[0]),
                sizeArray);
    }

    /** Picks the file marked primary, or the first file if none is marked. Null if there are no files. */
    private static JsonObject pickPrimaryFile(JsonObject version) {
        JsonElement filesElement = version.get("files");
        if (filesElement == null || !filesElement.isJsonArray()) return null;
        JsonArray files = filesElement.getAsJsonArray();
        JsonObject first = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (first == null) first = file;
            JsonElement primary = file.get("primary");
            if (primary != null && !primary.isJsonNull() && primary.getAsBoolean()) return file;
        }
        return first;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : null;
    }

    @Override
    public void installMod(ModDetail modDetail, int selectedVersion, File instanceDirectory) throws IOException {
        String urlString = modDetail.versionUrls[selectedVersion];
        String fileName = getContentFileName(urlString, modDetail.title, modDetail.contentType);
        File contentDirectory = new File(instanceDirectory, getTargetDirectory(modDetail.contentType));
        FileUtils.ensureDirectory(contentDirectory);
        ArrayList<TaskMetadata> downloads = new ArrayList<>(1);
        downloads.add(new TaskMetadata(
                new File(contentDirectory, fileName),
                new URL(urlString),
                modDetail.versionSizes[selectedVersion],
                modDetail.versionHashes[selectedVersion],
                DownloadMirror.DOWNLOAD_CLASS_NONE));
        try {
            new SingleModDownloader().start(downloads);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private String getContentFileName(String urlString, String title, String contentType) {
        try {
            String path = new URL(urlString).getPath();
            String name = new File(URLDecoder.decode(path, StandardCharsets.UTF_8.name())).getName();
            if (name != null && name.toLowerCase().endsWith(".jar")) return name;
        } catch (Exception ignored) {}
        return title.replaceAll("[^A-Za-z0-9._-]", "_") + (Constants.CONTENT_MOD.equals(contentType) ? ".jar" : ".zip");
    }

    private String getTargetDirectory(String contentType) {
        if (Constants.CONTENT_SHADER.equals(contentType)) return "shaderpacks";
        if (Constants.CONTENT_RESOURCEPACK.equals(contentType)) return "resourcepacks";
        return "mods";
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installMrpack);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installMrpack);
    }

    private static LoaderInstaller createInfo(ModrinthIndex modrinthIndex, File installDestination) throws IOException {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.QUILT_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, mcVersion, modLoaderVersion);
        } else if(dependencies.size() == 1) {
            // "Vanilla" pack. Possibly GT:NH, let's try to detect lwjgl3ify
            File lwjgl3ifyJar = Lwjgl3ifyUtils.detectLwjgl3ifyJar(installDestination);
            if(lwjgl3ifyJar != null) return new Lwjgl3ifyLoaderInstaller(lwjgl3ifyJar);
        }

        return null;
    }

    private LoaderInstaller installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);
            try {
                new ModrinthDownloader().startDownloads(modrinthIndex.files, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex, instanceDestination);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }

    static class SingleModDownloader extends Downloader {
        SingleModDownloader() { super(ProgressLayout.INSTALL_MODPACK); }
        void start(ArrayList<TaskMetadata> tasks) throws IOException, InterruptedException {
            runDownloads(tasks);
        }
    }

    static class ModrinthDownloader extends Downloader {
        public ModrinthDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        protected void startDownloads(ModrinthIndex.ModrinthIndexFile[] indexFiles, File instanceDestination) throws IOException, InterruptedException {
            String absoluteInstancePath = instanceDestination.getAbsolutePath();
            ArrayList<TaskMetadata> taskMetadatas = new ArrayList<>(indexFiles.length);
            for(ModrinthIndex.ModrinthIndexFile file : indexFiles) {
                File targetPath = new File(instanceDestination, file.path);
                if(!targetPath.getAbsolutePath().startsWith(absoluteInstancePath)) throw new IOException("Bad path!");
                FileUtils.ensureParentDirectory(targetPath);
                taskMetadatas.add(new TaskMetadata(
                        targetPath, new URL(file.downloads[0]), // TODO source selection
                        file.fileSize, file.hashes.sha1,
                        DownloadMirror.DOWNLOAD_CLASS_NONE
                ));
            }
            runDownloads(taskMetadatas);
        }
    }
}
