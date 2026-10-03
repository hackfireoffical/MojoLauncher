package net.kdt.pojavlaunch.content;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One downloadable file (a "version" of a project) from Modrinth or CurseForge. */
public final class ContentVersion {
    public static final int STABILITY_RELEASE = 0;
    public static final int STABILITY_BETA = 1;
    public static final int STABILITY_ALPHA = 2;

    public static final class Dependency {
        public final String projectId;
        /** Specific version the author recommends, or null. */
        public final String versionId;

        public Dependency(String projectId, String versionId) {
            this.projectId = projectId;
            this.versionId = versionId;
        }
    }

    public final int source;
    public final String projectId;
    public final String versionId;
    public final String contentType;

    public String projectTitle;
    public String name;
    /** ISO-8601 timestamp; lexicographic order equals chronological order within one source. */
    public String datePublished = "";
    public int stability = STABILITY_RELEASE;
    public final List<String> mcVersions = new ArrayList<>();
    public final List<String> loaders = new ArrayList<>();
    public String url;
    public String fileName;
    public long size = -1;
    public String sha1;
    public final List<Dependency> requiredDependencies = new ArrayList<>();

    public ContentVersion(int source, String projectId, String versionId, String contentType) {
        this.source = source;
        this.projectId = projectId;
        this.versionId = versionId;
        this.contentType = contentType == null ? Constants.CONTENT_MOD : contentType;
    }

    public boolean isDownloadable() {
        return url != null && !url.isEmpty() && fileName != null && !fileName.isEmpty();
    }

    public boolean supportsMinecraft(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isEmpty()) return true;
        return mcVersions.contains(minecraftVersion);
    }

    public boolean supportsLoader(String loader) {
        return Loaders.isCompatible(contentType, loader, loaders);
    }

    /** Folder inside the instance's game directory this content belongs in. */
    public String directory() {
        if (Constants.CONTENT_SHADER.equals(contentType)) return "shaderpacks";
        if (Constants.CONTENT_RESOURCEPACK.equals(contentType)) return "resourcepacks";
        return "mods";
    }

    public String loadersLabel() {
        if (loaders.isEmpty()) return Constants.CONTENT_MOD.equals(contentType) ? "Unknown" : "Minecraft";
        StringBuilder builder = new StringBuilder();
        for (String loader : loaders) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(Constants.CONTENT_MOD.equals(contentType) ? Loaders.display(loader) : loader);
        }
        return builder.toString();
    }

    /** Supported Minecraft versions, newest first, at most {@code max} of them. */
    public String minecraftLabel(int max) {
        ArrayList<String> sorted = new ArrayList<>(mcVersions);
        Collections.sort(sorted, Loaders::compareMinecraftDesc);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sorted.size() && i < max; i++) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(sorted.get(i));
        }
        if (sorted.size() > max) builder.append("...");
        return builder.toString();
    }
}
