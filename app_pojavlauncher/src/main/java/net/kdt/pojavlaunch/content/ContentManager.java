package net.kdt.pojavlaunch.content;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point of the content system: planning installs (with dependencies), installing, removing,
 * and checking for / applying updates. Every long-running method must be called off the UI thread
 * unless it says otherwise.
 */
public final class ContentManager {
    private static final String TAG = "ContentManager";
    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private ContentManager() {}

    /** The instance content is installed into. */
    public static final class Target {
        public final File gameDir;
        public final String minecraftVersion;
        public final String loader;

        public Target(File gameDir, String minecraftVersion, String loader) {
            this.gameDir = gameDir;
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
        }
    }

    public static final class UpdateInfo {
        public final InstalledEntry entry;
        public final ContentVersion latest;

        UpdateInfo(InstalledEntry entry, ContentVersion latest) {
            this.entry = entry;
            this.latest = latest;
        }
    }

    public interface Callback<T> {
        /** Called on the UI thread. Exactly one of result and error is non-null. */
        void onDone(T result, Exception error);
    }

    public static Target currentTarget() throws IOException {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) throw new IOException("No instance selected");
        String versionId = instance.versionId;
        return new Target(instance.getGameDirectory(), Loaders.minecraftVersion(versionId), Loaders.fromVersionId(versionId));
    }

    public static boolean isBusy() {
        return sBusy.get();
    }

    /** Resolves dependencies on a worker thread; the callback runs on the UI thread. */
    public static void preparePlan(ContentVersion version, Target target, Callback<InstallPlan> callback) {
        PojavApplication.sExecutorService.execute(() -> {
            InstallPlan plan = null;
            Exception error = null;
            try {
                plan = new DependencyResolver(target).resolve(version);
            } catch (Exception e) {
                Log.e(TAG, "Could not prepare install plan", e);
                error = e;
            }
            final InstallPlan finalPlan = plan;
            final Exception finalError = error;
            Tools.runOnUiThread(() -> callback.onDone(finalPlan, finalError));
        });
    }

    /**
     * Installs a plan in the background. The progress bar is always cleared and errors are always reported.
     * @return false if another install is already running
     */
    public static boolean install(Context context, InstallPlan plan, boolean withDependencies,
                                  Target target, Runnable onFinished) {
        if (!sBusy.compareAndSet(false, true)) return false;
        final Context appContext = context.getApplicationContext();
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                ContentInstaller.execute(target.gameDir, plan, withDependencies);
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                Tools.showErrorRemote(appContext, R.string.modpack_install_download_failed, e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                sBusy.set(false);
                if (onFinished != null) Tools.runOnUiThread(onFinished);
            }
        });
        return true;
    }

    /** Bridge for callers that still hold a ModDetail and a version index. */
    public static void installFromDetail(Context context, ModDetail detail, int index, boolean withDependencies) {
        final Context appContext = context.getApplicationContext();
        if (detail.contentVersions == null || index < 0 || index >= detail.contentVersions.length) {
            Tools.runOnUiThread(() -> Toast.makeText(appContext, "This version cannot be installed", Toast.LENGTH_LONG).show());
            return;
        }
        final ContentVersion version = detail.contentVersions[index];
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Target target = currentTarget();
                InstallPlan plan = new DependencyResolver(target).resolve(version);
                Tools.runOnUiThread(() -> {
                    if (!install(appContext, plan, withDependencies, target, null)) {
                        Toast.makeText(appContext, "A download is already in progress", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Tools.showErrorRemote(appContext, R.string.modpack_install_download_failed, e);
            }
        });
    }

    // ----- removal -----

    public static void removeEntry(File gameDir, InstalledEntry entry) throws IOException {
        File file = entry.resolve(gameDir);
        if (file.exists() && !file.delete()) throw new IOException("Could not delete " + file.getName());
        InstalledStore.remove(gameDir, entry);
    }

    /** Other installed entries that list this one as a requirement. */
    public static List<InstalledEntry> dependentsOf(File gameDir, InstalledEntry entry) {
        ArrayList<InstalledEntry> result = new ArrayList<>();
        for (InstalledEntry other : InstalledStore.load(gameDir)) {
            if (other.key().equals(entry.key())) continue;
            if (other.source == entry.source && other.dependsOn != null && other.dependsOn.contains(entry.projectId)) {
                result.add(other);
            }
        }
        return result;
    }

    /** Dependencies that were pulled in automatically and are no longer needed by anything. */
    public static List<InstalledEntry> findOrphans(File gameDir) {
        List<InstalledEntry> entries = InstalledStore.load(gameDir);
        Set<String> needed = new HashSet<>();
        ArrayList<InstalledEntry> queue = new ArrayList<>();
        for (InstalledEntry entry : entries) {
            if (entry.explicit) {
                needed.add(entry.key());
                queue.add(entry);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            InstalledEntry current = queue.get(i);
            if (current.dependsOn == null) continue;
            for (String dependencyId : current.dependsOn) {
                InstalledEntry dependency = InstalledStore.find(entries, current.source, dependencyId);
                if (dependency != null && needed.add(dependency.key())) queue.add(dependency);
            }
        }
        ArrayList<InstalledEntry> orphans = new ArrayList<>();
        for (InstalledEntry entry : entries) {
            if (!needed.contains(entry.key())) orphans.add(entry);
        }
        return orphans;
    }

    // ----- updates -----

    /** Blocking. Looks up the newest compatible version of every tracked file. */
    public static List<UpdateInfo> checkUpdates(Target target) {
        ArrayList<UpdateInfo> updates = new ArrayList<>();
        for (InstalledEntry entry : InstalledStore.load(target.gameDir)) {
            if (Thread.currentThread().isInterrupted()) break;
            ContentProvider provider = ContentProviders.get(entry.source);
            if (provider == null) continue;
            String type = entry.contentType == null ? Constants.CONTENT_MOD : entry.contentType;
            String minecraft = target.minecraftVersion != null ? target.minecraftVersion : entry.mcVersion;
            String loader = target.loader != null ? target.loader : entry.loader;
            try {
                List<ContentVersion> versions = provider.listVersions(entry.projectId, type, minecraft, loader);
                ContentVersion best = DependencyResolver.pickBest(versions, minecraft, loader, null);
                if (best != null && isNewer(entry, best)) updates.add(new UpdateInfo(entry, best));
            } catch (IOException e) {
                Log.w(TAG, "Update check failed for " + entry.title, e);
            }
        }
        return updates;
    }

    private static boolean isNewer(InstalledEntry entry, ContentVersion best) {
        if (entry.versionId != null && entry.versionId.equals(best.versionId)) return false;
        if (entry.publishedAt != null && !entry.publishedAt.isEmpty()) {
            return best.datePublished.compareTo(entry.publishedAt) > 0;
        }
        // Migrated entry without version info: compare file names
        String current = entry.file == null ? "" : new File(entry.file).getName();
        return !current.equals(best.fileName);
    }

    /** Applies updates (and any new required dependencies) one after another in the background. */
    public static boolean updateEntries(Context context, Target target, List<UpdateInfo> updates, Runnable onFinished) {
        if (!sBusy.compareAndSet(false, true)) return false;
        final Context appContext = context.getApplicationContext();
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        final ArrayList<UpdateInfo> work = new ArrayList<>(updates);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                for (UpdateInfo info : work) {
                    info.latest.projectTitle = info.entry.title;
                    InstallPlan plan = new DependencyResolver(target).resolve(info.latest);
                    ContentInstaller.execute(target.gameDir, plan, true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Update failed", e);
                Tools.showErrorRemote(appContext, R.string.modpack_install_download_failed, e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                sBusy.set(false);
                if (onFinished != null) Tools.runOnUiThread(onFinished);
            }
        });
        return true;
    }
}
