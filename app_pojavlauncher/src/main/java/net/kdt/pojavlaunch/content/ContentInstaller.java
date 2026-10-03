package net.kdt.pojavlaunch.content;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;

/**
 * Downloads an InstallPlan into an instance in a single batch (one progress bar for the whole plan),
 * verifies the files and records them in the InstalledStore.
 * Callers are responsible for clearing the progress bar afterwards (ContentManager does).
 */
final class ContentInstaller {
    private ContentInstaller() {}

    static void execute(File gameDir, InstallPlan plan, boolean withDependencies) throws IOException {
        ArrayList<InstallPlan.Item> items = new ArrayList<>();
        if (withDependencies) items.addAll(plan.dependencies);
        items.add(plan.root);

        ArrayList<TaskMetadata> tasks = new ArrayList<>(items.size());
        ArrayList<File> targets = new ArrayList<>(items.size());
        for (InstallPlan.Item item : items) {
            ContentVersion version = item.version;
            if (!version.isDownloadable()) throw new IOException(item.title + " has no downloadable file");
            File directory = new File(gameDir, version.directory());
            FileUtils.ensureDirectory(directory);
            File target = new File(directory, safeFileName(version.fileName));
            // Never let a hostile file name escape the content folder
            if (!target.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) {
                throw new IOException("Unsafe file name: " + version.fileName);
            }
            targets.add(target);
            tasks.add(new TaskMetadata(target, new URL(version.url), version.size, version.sha1,
                    DownloadMirror.DOWNLOAD_CLASS_NONE));
        }

        try {
            new ContentDownloader().start(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }

        for (int i = 0; i < items.size(); i++) {
            File target = targets.get(i);
            if (!target.isFile() || target.length() == 0) {
                throw new IOException("Download of " + items.get(i).title + " did not complete");
            }
        }

        for (int i = 0; i < items.size(); i++) {
            record(gameDir, plan, items.get(i), targets.get(i));
        }
    }

    private static void record(File gameDir, InstallPlan plan, InstallPlan.Item item, File target) throws IOException {
        ContentVersion version = item.version;
        InstalledEntry old = InstalledStore.find(InstalledStore.load(gameDir), version.source, version.projectId);

        InstalledEntry entry = new InstalledEntry();
        entry.source = version.source;
        entry.projectId = version.projectId;
        entry.versionId = version.versionId;
        entry.title = item.title;
        entry.contentType = version.contentType;
        entry.versionName = version.name;
        entry.mcVersion = plan.minecraftVersion != null ? plan.minecraftVersion : version.minecraftLabel(1);
        entry.loader = plan.loader != null ? plan.loader : (version.loaders.isEmpty() ? "" : version.loaders.get(0));
        entry.file = version.directory() + "/" + target.getName();
        entry.sha1 = version.sha1;
        entry.publishedAt = version.datePublished;
        entry.installedAt = System.currentTimeMillis();
        entry.dependsOn.addAll(item.dependsOn);
        // Something the user explicitly picked stays "explicit" even if it is later also needed as a dependency
        entry.explicit = item == plan.root || (old != null && old.explicit);
        InstalledStore.upsert(gameDir, entry);

        // Replacing an older version: remove the previous file once the new one is in place
        if (old != null && old.file != null && !old.file.equals(entry.file)) {
            File oldFile = old.resolve(gameDir);
            if (oldFile.isFile()) oldFile.delete();
        }
    }

    private static String safeFileName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean bad = c == ':' || c == '*' || c == '?' || c == '<' || c == '>' || c == '|' || c == (char) 34 || c < 32;
            cleaned.append(bad ? '_' : c);
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? "download.jar" : result;
    }

    private static final class ContentDownloader extends Downloader {
        ContentDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        void start(ArrayList<TaskMetadata> tasks) throws IOException, InterruptedException {
            runDownloads(tasks);
        }
    }
}
