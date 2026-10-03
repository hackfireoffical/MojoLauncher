package net.kdt.pojavlaunch.content;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** A file MojoLauncher installed (or adopted from the old manifest) into an instance. */
public final class InstalledEntry {
    public int source;
    public String projectId;
    /** Null for entries migrated from the old manifest. */
    public String versionId;
    public String title;
    public String contentType;
    public String versionName;
    public String mcVersion;
    public String loader;
    /** Path relative to the instance's game directory, e.g. mods/sodium.jar */
    public String file;
    public String sha1;
    /** ISO timestamp the installed version was published, used to detect newer versions. */
    public String publishedAt;
    /** True if the user picked it, false if it was pulled in as somebody's dependency. */
    public boolean explicit = true;
    public long installedAt;
    /** Project ids this entry requires. */
    public List<String> dependsOn = new ArrayList<>();

    public File resolve(File gameDir) {
        return new File(gameDir, file);
    }

    public String key() {
        return source + ":" + projectId;
    }
}
