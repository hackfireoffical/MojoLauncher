package net.kdt.pojavlaunch.content;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the ModDetail the browser UI shows for a mod, shader pack or resource pack. */
public final class ContentDetails {
    private ContentDetails() {}

    public static ModDetail load(ModItem item, ContentProvider provider) throws IOException {
        String type = item.contentType != null ? item.contentType : Constants.CONTENT_MOD;
        List<ContentVersion> all = provider.listVersions(item.id, type, null, null);
        ArrayList<ContentVersion> versions = new ArrayList<>();
        for (ContentVersion version : all) {
            if (!version.isDownloadable()) continue;
            version.projectTitle = item.title;
            versions.add(version);
        }
        // Newest first
        Collections.sort(versions, (a, b) -> b.datePublished.compareTo(a.datePublished));

        int n = versions.size();
        String[] names = new String[n];
        String[] blankMc = new String[n];
        String[] mcNames = new String[n];
        String[] urls = new String[n];
        String[] hashes = new String[n];
        String[] loaders = new String[n];
        long[] sizes = new long[n];
        String[][] dependencyNames = new String[n][0];
        String[][] dependencyIds = new String[n][0];
        for (int i = 0; i < n; i++) {
            ContentVersion version = versions.get(i);
            names[i] = version.name;
            blankMc[i] = "";
            ArrayList<String> mc = new ArrayList<>(version.mcVersions);
            Collections.sort(mc, Loaders::compareMinecraftDesc);
            mcNames[i] = join(mc);
            urls[i] = version.url;
            hashes[i] = version.sha1;
            loaders[i] = version.loadersLabel();
            sizes[i] = version.size;
        }

        // Passing blank Minecraft versions keeps the constructor from appending long version lists to the names
        ModDetail detail = new ModDetail(item, names, blankMc, urls, hashes, loaders, sizes, dependencyNames, dependencyIds);
        detail.mcVersionNames = mcNames;
        detail.contentVersions = versions.toArray(new ContentVersion[0]);
        return detail;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }
}
