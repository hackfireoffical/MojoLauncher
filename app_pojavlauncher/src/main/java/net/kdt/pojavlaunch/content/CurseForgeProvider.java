package net.kdt.pojavlaunch.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CurseForgeProvider implements ContentProvider {
    private static final String BASE = "https://api.curseforge.com/v1";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 6;
    private static final int ALGO_SHA1 = 1;
    private static final int RELATION_REQUIRED = 3;

    private final Map<String, String> mHeaders = new HashMap<>();
    private final Map<String, String> mTitles = new ConcurrentHashMap<>();

    public CurseForgeProvider(String apiKey) {
        mHeaders.put("x-api-key", apiKey);
    }

    @Override
    public int source() {
        return Constants.SOURCE_CURSEFORGE;
    }

    @Override
    public List<ContentVersion> listVersions(String projectId, String contentType, String mcVersion, String loader) throws IOException {
        ArrayList<ContentVersion> result = new ArrayList<>();
        int loaderCode = Constants.CONTENT_MOD.equals(contentType) ? loaderCode(loader) : -1;
        int index = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            StringBuilder url = new StringBuilder(BASE)
                    .append("/mods/").append(Http.encode(projectId)).append("/files")
                    .append("?index=").append(index).append("&pageSize=").append(PAGE_SIZE);
            if (mcVersion != null && !mcVersion.isEmpty()) {
                url.append("&gameVersion=").append(Http.encode(mcVersion));
            }
            if (loaderCode > 0) url.append("&modLoaderType=").append(loaderCode);

            JsonElement response = Http.getJson(url.toString(), mHeaders);
            JsonArray data = response.isJsonObject() ? Json.arr(response.getAsJsonObject(), "data") : null;
            if (data == null) throw new IOException("Unexpected response from CurseForge");
            for (JsonElement element : data) {
                if (!element.isJsonObject()) continue;
                ContentVersion version = parse(element.getAsJsonObject(), projectId, contentType);
                if (version != null) result.add(version);
            }
            if (data.size() < PAGE_SIZE) break;
            index += data.size();
        }
        return result;
    }

    /** CurseForge modLoaderType codes. Quilt is left unfiltered because it also runs Fabric mods. */
    private static int loaderCode(String loader) {
        String n = Loaders.normalize(loader);
        if (n == null) return -1;
        switch (n) {
            case "forge": return 1;
            case "fabric": return 4;
            case "neoforge": return 6;
            default: return -1;
        }
    }

    private ContentVersion parse(JsonObject file, String projectId, String contentType) {
        if (Json.bool(file, "isServerPack")) return null;
        long fileId = Json.num(file, "id", -1);
        if (fileId < 0) return null;
        String fileName = Json.str(file, "fileName");
        if (fileName == null || fileName.isEmpty()) return null;

        ContentVersion version = new ContentVersion(Constants.SOURCE_CURSEFORGE,
                projectId, String.valueOf(fileId), contentType);
        version.name = Json.str(file, "displayName", fileName);
        version.datePublished = Json.str(file, "fileDate", "");
        long releaseType = Json.num(file, "releaseType", 1);
        version.stability = releaseType >= 3 ? ContentVersion.STABILITY_ALPHA
                : releaseType == 2 ? ContentVersion.STABILITY_BETA : ContentVersion.STABILITY_RELEASE;
        version.fileName = fileName;
        version.size = Json.num(file, "fileLength", -1);

        // downloadUrl is null for many files; the edge CDN link works for those
        String url = Json.str(file, "downloadUrl");
        if (url == null || url.isEmpty()) {
            url = "https://edge.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000) + "/"
                    + fileName.replace(" ", "%20");
        }
        version.url = url;

        JsonArray hashes = Json.arr(file, "hashes");
        if (hashes != null) {
            for (JsonElement element : hashes) {
                if (!element.isJsonObject()) continue;
                JsonObject hash = element.getAsJsonObject();
                if (Json.num(hash, "algo", -1) == ALGO_SHA1) {
                    version.sha1 = Json.str(hash, "value");
                    break;
                }
            }
        }

        // gameVersions mixes Minecraft versions with loader names and other tags ("Java 17", "Client")
        JsonArray gameVersions = Json.arr(file, "gameVersions");
        if (gameVersions != null) {
            for (JsonElement element : gameVersions) {
                if (!element.isJsonPrimitive()) continue;
                String value = element.getAsString();
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.equals("fabric") || lower.equals("forge") || lower.equals("neoforge") || lower.equals("quilt")) {
                    if (!version.loaders.contains(lower)) version.loaders.add(lower);
                } else if (Loaders.isMinecraftVersion(value)) {
                    version.mcVersions.add(value);
                }
            }
        }

        JsonArray dependencies = Json.arr(file, "dependencies");
        if (dependencies != null) {
            for (JsonElement element : dependencies) {
                if (!element.isJsonObject()) continue;
                JsonObject dependency = element.getAsJsonObject();
                if (Json.num(dependency, "relationType", -1) != RELATION_REQUIRED) continue;
                String modId = Json.str(dependency, "modId");
                if (modId != null) version.requiredDependencies.add(new ContentVersion.Dependency(modId, null));
            }
        }
        return version;
    }

    @Override
    public String projectTitle(String projectId) {
        String cached = mTitles.get(projectId);
        if (cached != null) return cached;
        try {
            JsonElement response = Http.getJson(BASE + "/mods/" + Http.encode(projectId), mHeaders);
            if (response.isJsonObject()) {
                JsonObject data = Json.obj(response.getAsJsonObject(), "data");
                String name = Json.str(data, "name");
                if (name != null && !name.isEmpty()) {
                    mTitles.put(projectId, name);
                    return name;
                }
            }
        } catch (IOException ignored) {
        }
        return projectId;
    }
}
