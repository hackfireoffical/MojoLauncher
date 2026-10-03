package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.AcquireableTaskMetadata;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.FabriclikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.ForgelikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.InstalledModManager;
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class CurseforgeApi implements ModpackApi{
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final int ALGO_SHA_1 = 1;
    // Stolen from
    // https://github.com/AnzhiZhang/CurseForgeModpackDownloader/blob/6cb3f428459f0cc8f444d16e54aea4cd1186fd7b/utils/requester.py#L93
    private static final int CURSEFORGE_MC_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    // https://api.curseforge.com/v1/categories?gameId=432 and search for "Mods" (case-sensitive)
    private static final int CURSEFORGE_MOD_CLASS_ID = 6;
    private static final int CURSEFORGE_RESOURCE_PACK_CLASS_ID = 12;
    private static final int CURSEFORGE_SHADER_CLASS_ID = 6552;
    private static final int CURSEFORGE_SORT_RELEVANCY = 1;
    private static final int CURSEFORGE_SORT_POPULARITY = 2;
    private static final int CURSEFORGE_SORT_LAST_UPDATED = 3;
    private static final int CURSEFORGE_SORT_NAME = 4;
    private static final int CURSEFORGE_SORT_DOWNLOADS = 6;
    private static final int CURSEFORGE_SORT_RELEASED_DATE = 11;
    private static final int CURSEFORGE_SORT_RATING = 12;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    private final ApiHandler mApiHandler;
    public CurseforgeApi(String apiKey) {
        mApiHandler = new ApiHandler("https://api.curseforge.com/v1", apiKey);
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MC_GAME_ID);
        params.put("classId", getClassId(searchFilters));
        params.put("searchFilter", searchFilters.name);
        params.put("sortField", getSortField(searchFilters.sort));
        params.put("sortOrder", "desc");
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            params.put("gameVersion", searchFilters.mcVersion);
        if(previousPageResult != null)
            params.put("index", curseforgeSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("mods/search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray dataArray = response.getAsJsonArray("data");
        if(dataArray == null) return null;
        JsonObject paginationInfo = response.getAsJsonObject("pagination");
        ArrayList<ModItem> modItemList = new ArrayList<>(dataArray.size());
        for(int i = 0; i < dataArray.size(); i++) {
            JsonObject dataElement = dataArray.get(i).getAsJsonObject();
            JsonElement allowModDistribution = dataElement.get("allowModDistribution");
            // Gson automatically casts null to false, which leans to issues
            // So, only check the distribution flag if it is non-null
            if(!allowModDistribution.isJsonNull() && !allowModDistribution.getAsBoolean()) {
                Log.i("CurseforgeApi", "Skipping modpack "+dataElement.get("name").getAsString() + " because curseforge sucks");
                continue;
            }
            ModItem modItem = new ModItem(Constants.SOURCE_CURSEFORGE,
                    searchFilters.isModpack,
                    dataElement.get("id").getAsString(),
                    dataElement.get("name").getAsString(),
                    dataElement.get("summary").getAsString(),
                    dataElement.getAsJsonObject("logo").get("thumbnailUrl").getAsString());
            modItem.contentType = searchFilters.isModpack ? Constants.CONTENT_MODPACK : searchFilters.contentType;
            modItemList.add(modItem);
        }
        if(curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = modItemList.toArray(new ModItem[0]);
        curseforgeSearchResult.totalResultCount = paginationInfo.get("totalCount").getAsInt();
        curseforgeSearchResult.previousOffset += dataArray.size();
        return curseforgeSearchResult;

    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while(index != CURSEFORGE_PAGINATION_END_REACHED &&
                index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id);
        }
        if(index == CURSEFORGE_PAGINATION_ERROR) return null;
        int length = allModDetails.size();
        String[] versionNames = new String[length];
        String[] mcVersionNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        String[] loaders = new String[length];
        long[] sizes = new long[length];
        String[][] dependencies = new String[length][];
        for(int i = 0; i < allModDetails.size(); i++) {
            JsonObject modDetail = allModDetails.get(i);
            versionNames[i] = modDetail.get("displayName").getAsString();

            JsonElement downloadUrl = modDetail.get("downloadUrl");
            versionUrls[i] = downloadUrl.getAsString();

            JsonArray gameVersions = modDetail.getAsJsonArray("gameVersions");
            StringBuilder mcVersionBuilder = new StringBuilder();
            for(JsonElement jsonElement : gameVersions) {
                String gameVersion = jsonElement.getAsString();
                if(!sMcVersionPattern.matcher(gameVersion).matches()) continue;
                if(mcVersionBuilder.length() > 0) mcVersionBuilder.append(", ");
                mcVersionBuilder.append(gameVersion);
            }
            mcVersionNames[i] = mcVersionBuilder.toString();

            hashes[i] = getSha1FromModData(modDetail);
            loaders[i] = getCurseforgeLoaderName(modDetail);
            if ("Unknown".equals(loaders[i]) && (Constants.CONTENT_SHADER.equals(item.contentType) || Constants.CONTENT_RESOURCEPACK.equals(item.contentType))) loaders[i] = "Minecraft";
            JsonArray dependencyArray = modDetail.getAsJsonArray("dependencies");
            ArrayList<String> requiredDependencies = new ArrayList<>();
            if (dependencyArray != null) {
                for (JsonElement dependencyElement : dependencyArray) {
                    JsonObject dependency = dependencyElement.getAsJsonObject();
                    JsonElement relationType = dependency.get("relationType");
                    if (relationType == null || relationType.isJsonNull() || relationType.getAsInt() != 4) continue;
                    JsonElement dependencyId = dependency.get("modId");
                    if (dependencyId == null || dependencyId.isJsonNull()) continue;
                    String dependencyName = dependencyId.getAsString();
                    try {
                        JsonObject dependencyMod = mApiHandler.get("mods/" + dependencyId.getAsString(), null, JsonObject.class);
                        if (dependencyMod != null && dependencyMod.has("data")) {
                            JsonObject data = dependencyMod.getAsJsonObject("data");
                            if (data.has("name")) dependencyName = data.get("name").getAsString();
                        }
                    } catch (Exception ignored) {}
                    requiredDependencies.add(dependencyName);
                }
            }
            dependencies[i] = requiredDependencies.toArray(new String[0]);
            JsonElement fileLength = modDetail.get("fileLength");
            sizes[i] = fileLength != null && !fileLength.isJsonNull() ? fileLength.getAsLong() : -1;
        }
        return new ModDetail(item, versionNames, mcVersionNames, versionUrls, hashes, loaders, sizes, dependencies);
    }

    @Override
    public void installMod(ModDetail modDetail, int selectedVersion, File instanceDirectory) throws IOException {
        String urlString = modDetail.versionUrls[selectedVersion];
        if (urlString == null || urlString.isEmpty()) {
            throw new IOException("This CurseForge file has no downloadable URL");
        }
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
            if (Constants.CONTENT_MOD.equals(modDetail.contentType)) {
                InstalledModManager.record(instanceDirectory,
                        modDetail, selectedVersion,
                        new File(contentDirectory, fileName));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private String getCurseforgeLoaderName(JsonObject file) {
        JsonElement loader = file.get("modLoaderType");
        if (loader != null && !loader.isJsonNull()) {
            switch (loader.getAsInt()) {
                case 1: return "Forge";
                case 4: return "Fabric";
                case 5: return "Quilt";
                case 6: return "NeoForge";
                default: break;
            }
        }
        return "Unknown";
    }

    private String getContentFileName(String urlString, String title, String contentType) {
        try {
            String path = new URL(urlString).getPath();
            String name = new File(URLDecoder.decode(path, "UTF-8")).getName();
            if (name != null && name.toLowerCase().endsWith(".jar")) return name;
        } catch (Exception ignored) {}
        return title.replaceAll("[^A-Za-z0-9._-]", "_") + (Constants.CONTENT_MOD.equals(contentType) ? ".jar" : ".zip");
    }

    private String getTargetDirectory(String contentType) {
        if (Constants.CONTENT_SHADER.equals(contentType)) return "shaderpacks";
        if (Constants.CONTENT_RESOURCEPACK.equals(contentType)) return "resourcepacks";
        return "mods";
    }

    private int getClassId(SearchFilters filters) {
        if (filters.isModpack) return CURSEFORGE_MODPACK_CLASS_ID;
        if (Constants.CONTENT_SHADER.equals(filters.contentType)) return CURSEFORGE_SHADER_CLASS_ID;
        if (Constants.CONTENT_RESOURCEPACK.equals(filters.contentType)) return CURSEFORGE_RESOURCE_PACK_CLASS_ID;
        return CURSEFORGE_MOD_CLASS_ID;
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installCurseforgeZip);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installCurseforgeZip);
    }

    private int getSortField(String sort) {
        if ("downloads".equals(sort)) return CURSEFORGE_SORT_DOWNLOADS;
        if ("updated".equals(sort)) return CURSEFORGE_SORT_LAST_UPDATED;
        if ("newest".equals(sort)) return CURSEFORGE_SORT_RELEASED_DATE;
        if ("name".equals(sort)) return CURSEFORGE_SORT_NAME;
        if ("rating".equals(sort)) return CURSEFORGE_SORT_RATING;
        if ("popularity".equals(sort)) return CURSEFORGE_SORT_POPULARITY;
        return CURSEFORGE_SORT_RELEVANCY;
    }

    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response = mApiHandler.get("mods/"+modId+"/files", params, JsonObject.class);
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        Log.i("CurseforgeApi", "data...");
        if(data == null) return CURSEFORGE_PAGINATION_ERROR;
        Log.i("CurseforgeApi", "filtering...");
        for(int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = data.get(i).getAsJsonObject();
            if(fileInfo.get("isServerPack").getAsBoolean()) continue;
            objectList.add(fileInfo);
        }
        Log.i("CurseforgeApi", "pag_end");
        if(data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED; // we read the remainder! yay!
        }
        return index + data.size();
    }

    private LoaderInstaller installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)){
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest.class);
            if(!verifyManifest(curseManifest)) {
                Log.i("CurseforgeApi","manifest verification failed");
                return null;
            }
            try {
                new CurseDownloader().start(curseManifest, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            String overridesDir = "overrides";
            if(curseManifest.overrides != null) overridesDir = curseManifest.overrides;
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);
            return createInfo(curseManifest.minecraft);
        }
    }

    private LoaderInstaller createInfo(CurseManifest.CurseMinecraft minecraft) {
        CurseManifest.CurseModLoader primaryModLoader = null;
        for(CurseManifest.CurseModLoader modLoader : minecraft.modLoaders) {
            if(modLoader.primary) {
                primaryModLoader = modLoader;
                break;
            }
        }
        if(primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0];
        String modLoaderId = primaryModLoader.id;
        int dashIndex = modLoaderId.indexOf('-');
        String modLoaderName = modLoaderId.substring(0, dashIndex);
        String modLoaderVersion = modLoaderId.substring(dashIndex+1);
        Log.i("CurseforgeApi", modLoaderId + " " + modLoaderName + " "+modLoaderVersion);
        LoaderInstaller loaderInstaller;
        switch (modLoaderName) {
            case "forge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, minecraft.version, modLoaderVersion);
            case "fabric":
                return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, minecraft.version, modLoaderVersion);
            case "neoforge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, minecraft.version, modLoaderVersion);
            default:
                return null;
            //TODO: Quilt is also Forge? How does that work?
        }
    }

    private String getDownloadUrl(JsonObject fileMetadata) throws IOException {
        if(fileMetadata.get("modId").isJsonNull() || fileMetadata.get("id").isJsonNull()) throw new IOException("Bad metadata schema!");
        long projectID = fileMetadata.get("modId").getAsLong();
        long fileID = fileMetadata.get("id").getAsLong();

        // First try the official api endpoint
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID+"/download-url", JsonObject.class);
        if (response != null && !response.get("data").isJsonNull())
            return response.get("data").getAsString();

        // Otherwise, fallback to building an edge link
        return String.format("https://edge.forgecdn.net/files/%s/%s/%s", fileID/1000, fileID % 1000, fileMetadata.get("fileName").getAsString());
    }

    private void checkRequiredFileFields(JsonObject fileMetadata) throws IOException {
        if(fileMetadata == null || fileMetadata.isJsonNull()) throw new IOException("File metadata is null!");
        boolean hasProjectId = fileMetadata.has("modId");
        boolean hasFileId = fileMetadata.has("id");
        boolean hasLength = fileMetadata.has("fileLength");
        if(!hasProjectId || !hasFileId || !hasLength) {
            StringBuilder builder = new StringBuilder().append("File metadata is mising the following fields:");
            if(!hasProjectId) builder.append(" modId");
            if(!hasFileId) builder.append(" id");
            if(!hasLength) builder.append(" fileLength");
            throw new IOException(builder.toString());
        }
    }

    private @Nullable JsonObject getFile(long projectID, long fileID) {
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID, JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(response, "data");
    }

    private String getSha1FromModData(@NonNull JsonObject object) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if(hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // The sha1 = 1; md5 = 2;
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if(GsonJsonUtils.getIntSafe(
                    jsonObject,
                    "algo",
                    -1) == ALGO_SHA_1) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }

    private boolean verifyManifest(CurseManifest manifest) {
        if(!"minecraftModpack".equals(manifest.manifestType)) return false;
        if(manifest.manifestVersion != 1) return false;
        if(manifest.minecraft == null) return false;
        if(manifest.minecraft.version == null) return false;
        if(manifest.minecraft.modLoaders == null) return false;
        return manifest.minecraft.modLoaders.length >= 1;
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }

    class SingleModDownloader extends Downloader {
        SingleModDownloader() { super(ProgressLayout.INSTALL_MODPACK); }
        void start(ArrayList<TaskMetadata> tasks) throws IOException, InterruptedException {
            runDownloads(tasks);
        }
    }

    class CurseDownloader extends Downloader {

        public CurseDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        public void start(CurseManifest curseManifest, File instanceDestination) throws IOException, InterruptedException {
            ArrayList<AcquireableTaskMetadata> taskMetadatas = new ArrayList<>(curseManifest.files.length);
            for(final CurseManifest.CurseFile file : curseManifest.files) {
                taskMetadatas.add(new CurseTaskMetadata(file, instanceDestination));
            }
            runDownloads(taskMetadatas);
        }
    }

    class CurseTaskMetadata extends AcquireableTaskMetadata {
        private final CurseManifest.CurseFile mFile;
        private final File mInstanceDestination;

        public CurseTaskMetadata(CurseManifest.CurseFile mFile, File mInstanceDestination) {
            super(DownloadMirror.DOWNLOAD_CLASS_METADATA);
            this.mFile = mFile;
            this.mInstanceDestination = mInstanceDestination;
        }

        @Override
        public void acquireMetadata() throws IOException {
            JsonObject fileMetadata = getFile(mFile.projectID, mFile.fileID);
            checkRequiredFileFields(fileMetadata);
            String url = getDownloadUrl(fileMetadata);
            this.url = new URL(url);
            this.path = new File(mInstanceDestination, "mods/"+ URLDecoder.decode(FileUtils.getFileName(url),"UTF-8"));
            FileUtils.ensureParentDirectorySilently(this.path);
            this.sha1Hash = getSha1FromModData(fileMetadata);
            this.size = fileMetadata.get("fileLength").getAsLong();
        }
    }
}
