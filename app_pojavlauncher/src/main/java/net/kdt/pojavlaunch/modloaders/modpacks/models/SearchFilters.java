package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    /** API source filter. SOURCE_ALL searches every configured source. */
    public int source = Constants.SOURCE_ALL;
    /** Sort mode shared by Modrinth and CurseForge adapters. */
    public String sort = "relevance";

}
