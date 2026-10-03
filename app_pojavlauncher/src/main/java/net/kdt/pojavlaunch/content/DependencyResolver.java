package net.kdt.pojavlaunch.content;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which required dependencies have to be installed alongside a file.
 * Handles nested dependencies, circular dependencies, dependencies that are already installed
 * and picks versions that match the target instance's Minecraft version and loader.
 */
public final class DependencyResolver {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 60;

    private final ContentManager.Target mTarget;
    private final List<InstalledEntry> mInstalled;

    public DependencyResolver(ContentManager.Target target) {
        mTarget = target;
        mInstalled = InstalledStore.load(target.gameDir);
    }

    public InstallPlan resolve(ContentVersion root) throws IOException {
        String title = root.projectTitle != null && !root.projectTitle.isEmpty() ? root.projectTitle : root.name;
        InstallPlan plan = new InstallPlan(root, title, mTarget.minecraftVersion, mTarget.loader);
        Set<String> visited = new HashSet<>();
        visited.add(root.source + ":" + root.projectId);
        collect(root, plan, visited, 0, plan.root.dependsOn);
        return plan;
    }

    private void collect(ContentVersion parent, InstallPlan plan, Set<String> visited, int depth,
                         List<String> parentDependsOn) throws IOException {
        if (parent.requiredDependencies.isEmpty()) return;
        ContentProvider provider = ContentProviders.get(parent.source);
        if (provider == null) return;

        for (ContentVersion.Dependency dependency : parent.requiredDependencies) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");

            String projectId = dependency.projectId;
            if (projectId == null && dependency.versionId != null) {
                try {
                    projectId = provider.projectIdForVersion(dependency.versionId);
                } catch (IOException e) {
                    plan.warnings.add("Could not look up a dependency: " + e.getMessage());
                }
            }
            if (projectId == null) continue;
            parentDependsOn.add(projectId);

            if (!visited.add(parent.source + ":" + projectId)) continue; // already handled, or a cycle
            if (depth >= MAX_DEPTH || plan.dependencies.size() >= MAX_ITEMS) {
                plan.warnings.add("Dependency chain is too long, stopped at " + projectId);
                continue;
            }

            String title = provider.projectTitle(projectId);
            if (isInstalled(parent.source, projectId, title)) continue;

            ContentVersion best;
            try {
                List<ContentVersion> versions = provider.listVersions(projectId, Constants.CONTENT_MOD,
                        mTarget.minecraftVersion, mTarget.loader);
                best = pickBest(versions, mTarget.minecraftVersion, mTarget.loader, dependency.versionId);
            } catch (IOException e) {
                plan.warnings.add("Could not look up " + title + ": " + e.getMessage());
                continue;
            }
            if (best == null) {
                plan.warnings.add(title + " has no version for Minecraft "
                        + (mTarget.minecraftVersion == null ? "(unknown)" : mTarget.minecraftVersion)
                        + " / " + Loaders.display(mTarget.loader));
                continue;
            }
            best.projectTitle = title;

            // Dependencies of the dependency go in first so the install order is bottom-up
            ArrayList<String> childIds = new ArrayList<>();
            collect(best, plan, visited, depth + 1, childIds);
            InstallPlan.Item item = plan.addDependency(best, title);
            item.dependsOn.addAll(childIds);
        }
    }

    /** Installed already, either by the same source and id, or (across sources) by name. */
    private boolean isInstalled(int source, String projectId, String title) {
        File gameDir = mTarget.gameDir;
        for (InstalledEntry entry : mInstalled) {
            if (entry.file == null || !entry.resolve(gameDir).isFile()) continue;
            if (entry.source == source && projectId.equals(entry.projectId)) return true;
            if (title != null && entry.title != null && !title.equals(projectId)
                    && entry.title.equalsIgnoreCase(title)) return true;
        }
        return false;
    }

    /**
     * Picks the best file for the given Minecraft version and loader: the exact recommended version
     * if it fits, otherwise the newest release (falling back to betas, then alphas).
     */
    public static ContentVersion pickBest(List<ContentVersion> versions, String minecraftVersion,
                                          String loader, String preferredVersionId) {
        ArrayList<ContentVersion> compatible = new ArrayList<>();
        for (ContentVersion version : versions) {
            if (version.isDownloadable() && version.supportsMinecraft(minecraftVersion)
                    && version.supportsLoader(loader)) {
                compatible.add(version);
            }
        }
        if (compatible.isEmpty()) return null;
        if (preferredVersionId != null) {
            for (ContentVersion version : compatible) {
                if (preferredVersionId.equals(version.versionId)) return version;
            }
        }
        Collections.sort(compatible, (a, b) -> {
            if (a.stability != b.stability) return Integer.compare(a.stability, b.stability);
            return b.datePublished.compareTo(a.datePublished);
        });
        return compatible.get(0);
    }
}
