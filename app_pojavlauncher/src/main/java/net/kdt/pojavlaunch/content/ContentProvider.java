package net.kdt.pojavlaunch.content;

import java.io.IOException;
import java.util.List;

/** A source of downloadable content (Modrinth, CurseForge). */
public interface ContentProvider {
    /** One of the Constants.SOURCE_* values. */
    int source();

    /**
     * Lists the versions of a project. mcVersion and loader are optional server-side hints; callers
     * must still filter the result with ContentVersion.supportsMinecraft/supportsLoader.
     */
    List<ContentVersion> listVersions(String projectId, String contentType, String mcVersion, String loader) throws IOException;

    /** Human readable project name; falls back to the id if it cannot be fetched. */
    String projectTitle(String projectId);

    /** Resolves the project a specific version belongs to, or null when unsupported. */
    default String projectIdForVersion(String versionId) throws IOException {
        return null;
    }
}
