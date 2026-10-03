package net.kdt.pojavlaunch.content;

import java.util.ArrayList;
import java.util.List;

/** What would be installed for one request: the chosen file plus any required dependencies. */
public final class InstallPlan {
    public static final class Item {
        public final ContentVersion version;
        public final String title;
        /** Project ids this file requires. */
        public final List<String> dependsOn = new ArrayList<>();

        Item(ContentVersion version, String title) {
            this.version = version;
            this.title = title;
        }
    }

    public final Item root;
    /** Dependencies in install order: a dependency always comes before whatever needs it. */
    public final List<Item> dependencies = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final String minecraftVersion;
    public final String loader;

    InstallPlan(ContentVersion rootVersion, String rootTitle, String minecraftVersion, String loader) {
        this.root = new Item(rootVersion, rootTitle);
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
    }

    Item addDependency(ContentVersion version, String title) {
        Item item = new Item(version, title);
        dependencies.add(item);
        return item;
    }

    public boolean hasDependencies() {
        return !dependencies.isEmpty();
    }

    /** Explains why the chosen file might not work in the selected instance, or null if it looks fine. */
    public String compatibilityWarning() {
        StringBuilder builder = new StringBuilder();
        ContentVersion version = root.version;
        if (minecraftVersion != null && !version.supportsMinecraft(minecraftVersion)) {
            builder.append("This file is built for Minecraft ").append(version.minecraftLabel(4))
                    .append(", but the selected instance runs ").append(minecraftVersion).append('.');
        }
        if (!version.supportsLoader(loader)) {
            if (builder.length() > 0) builder.append('\n');
            builder.append("This file supports ").append(version.loadersLabel())
                    .append(", but the selected instance uses ").append(Loaders.display(loader)).append('.');
        }
        return builder.length() == 0 ? null : builder.toString();
    }
}
