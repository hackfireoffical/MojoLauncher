package net.kdt.pojavlaunch.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModrinthProvider implements ContentProvider {
    private static final String BASE = "https://api.modrinth.com/v2";
    private final Map<String, String> mTitles = new ConcurrentHashMap<>();

    @Override
    public int source() {
        return Constants.SOURCE_MODRINTH;
    }

    @Override
    public List<ContentVersion> listVersions(String projectId, String contentType, String mcVersion, String loader) throws IOException {
        StringBuilder url = new StringBuilder(BASE)
                .append("/project/").append(Http.encode(projectId)).append("/version");
        char separator = '?';
        String loaderFilter = Constants.CONTENT_MOD.equals(contentType) ? loaderFilter(loader) : null;
        if (loaderFilter != null) {
            url.append(separator).append("loaders=").append(Http.encode(loaderFilter));
            separator = '&';
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append(separator).append("game_versions=")
                    .append(Http.encode("[" + Http.quoted(mcVersion) + "]"));
        }
        JsonElement response = Http.getJson(url.toString(), null);
        if (!response.isJsonArray()) throw new IOException("Unexpected response from Modrinth");
        ArrayList<ContentVersion> result = new ArrayList<>();
        for (JsonElement element : response.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            ContentVersion version = parse(element.getAsJsonObject(), projectId, contentType);
            if (version != null) result.add(version);
        }
        return result;
    }

    private static String loaderFilter(String loader) {
        String n = Loaders.normalize(loader);
        if (n == null) return null;
        switch (n) {
            case "quilt": return "[" + Http.quoted("quilt") + "," + Http.quoted("fabric") + "]";
            case "fabric":
            case "forge":
            case "neoforge":
                return "[" + Http.quoted(n) + "]";
            default: return null;
        }
    }

    private ContentVersion parse(JsonObject object, String requestedProjectId, String contentType) {
        JsonObject file = pickPrimaryFile(object);
        if (file == null) return null;
        String url = Json.str(file, "url");
        if (url == null || url.isEmpty()) return null;
        String versionId = Json.str(object, "id");
        if (versionId == null) return null;

        ContentVersion version = new ContentVersion(Constants.SOURCE_MODRINTH,
                Json.str(object, "project_id", requestedProjectId), versionId, contentType);
        String name = Json.str(object, "name");
        if (name == null || name.isEmpty()) name = Json.str(object, "version_number", "Unnamed version");
        version.name = name;
        version.datePublished = Json.str(object, "date_published", "");
        String type = Json.str(object, "version_type", "release");
        version.stability = "alpha".equals(type) ? ContentVersion.STABILITY_ALPHA
                : "beta".equals(type) ? ContentVersion.STABILITY_BETA : ContentVersion.STABILITY_RELEASE;
        addStrings(Json.arr(object, "game_versions"), version.mcVersions);
        addStrings(Json.arr(object, "loaders"), version.loaders);

        version.url = url;
        String fileName = Json.str(file, "filename");
        if (fileName == null || fileName.isEmpty()) {
            int slash = url.lastIndexOf('/');
            fileName = slash >= 0 ? url.substring(slash + 1) : url;
        }
        version.fileName = fileName;
        version.size = Json.num(file, "size", -1);
        version.sha1 = Json.str(Json.obj(file, "hashes"), "sha1");

        JsonArray dependencies = Json.arr(object, "dependencies");
        if (dependencies != null) {
            for (JsonElement element : dependencies) {
                if (!element.isJsonObject()) continue;
                JsonObject dependency = element.getAsJsonObject();
                if (!"required".equals(Json.str(dependency, "dependency_type"))) continue;
                String depProject = Json.str(dependency, "project_id");
                String depVersion = Json.str(dependency, "version_id");
                if (depProject == null && depVersion == null) continue;
                version.requiredDependencies.add(new ContentVersion.Dependency(depProject, depVersion));
            }
        }
        return version;
    }

    /** The file flagged primary, or the first one if none is flagged. */
    private static JsonObject pickPrimaryFile(JsonObject version) {
        JsonArray files = Json.arr(version, "files");
        if (files == null) return null;
        JsonObject first = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (first == null) first = file;
            if (Json.bool(file, "primary")) return file;
        }
        return first;
    }

    private static void addStrings(JsonArray array, List<String> out) {
        if (array == null) return;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) out.add(element.getAsString());
        }
    }

    @Override
    public String projectTitle(String projectId) {
        String cached = mTitles.get(projectId);
        if (cached != null) return cached;
        try {
            JsonElement response = Http.getJson(BASE + "/project/" + Http.encode(projectId), null);
            if (response.isJsonObject()) {
                String title = Json.str(response.getAsJsonObject(), "title");
                if (title != null && !title.isEmpty()) {
                    mTitles.put(projectId, title);
                    return title;
                }
            }
        } catch (IOException ignored) {
        }
        return projectId;
    }

    @Override
    public String projectIdForVersion(String versionId) throws IOException {
        JsonElement response = Http.getJson(BASE + "/version/" + Http.encode(versionId), null);
        return response.isJsonObject() ? Json.str(response.getAsJsonObject(), "project_id") : null;
    }
}
