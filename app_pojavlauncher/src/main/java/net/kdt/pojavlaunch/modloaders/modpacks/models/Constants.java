package net.kdt.pojavlaunch.modloaders.modpacks.models;

public class Constants {
    private Constants(){}

    /** Types of modpack apis */
    public static final int SOURCE_MODRINTH = 0x0;
    public static final int SOURCE_CURSEFORGE = 0x1;
    public static final int SOURCE_TECHNIC = 0x2;
    public static final int SOURCE_ALL = -1;

    public static final String CONTENT_MOD = "mod";
    public static final String CONTENT_MODPACK = "modpack";
    public static final String CONTENT_SHADER = "shader";
    public static final String CONTENT_RESOURCEPACK = "resourcepack";

    /** Modrinth api, file environments */
    public static final String MODRINTH_FILE_ENV_REQUIRED = "required";
    public static final String MODRINTH_FILE_ENV_OPTIONAL = "optional";
    public static final String MODRINTH_FILE_ENV_UNSUPPORTED = "unsupported";

}
