package net.kdt.pojavlaunch.content;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

/** Lookup for the content providers. CurseForge is only available once an API key was configured. */
public final class ContentProviders {
    private static final ModrinthProvider sModrinth = new ModrinthProvider();
    private static String sCurseforgeKey;
    private static CurseForgeProvider sCurseforge;
    private static String sCurseforgeProviderKey;

    private ContentProviders() {}

    public static synchronized void configure(String curseforgeKey) {
        sCurseforgeKey = curseforgeKey;
    }

    /** @return the provider, or null if that source is unavailable */
    public static synchronized ContentProvider get(int source) {
        if (source == Constants.SOURCE_MODRINTH) return sModrinth;
        if (source == Constants.SOURCE_CURSEFORGE) {
            String key = sCurseforgeKey;
            if (key == null || key.isEmpty() || "DUMMY".equals(key)) return null;
            if (sCurseforge == null || !key.equals(sCurseforgeProviderKey)) {
                sCurseforge = new CurseForgeProvider(key);
                sCurseforgeProviderKey = key;
            }
            return sCurseforge;
        }
        return null;
    }
}
