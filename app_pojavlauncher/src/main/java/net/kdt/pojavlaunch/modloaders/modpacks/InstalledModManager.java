package net.kdt.pojavlaunch.modloaders.modpacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public final class InstalledModManager {
    private static final String MANIFEST = ".mojo-mods.json";

    private InstalledModManager() {}

    public static void record(File instanceDirectory, ModDetail detail, int versionIndex, File installedFile) {
        if (detail == null || installedFile == null) return;
        try {
            File modsDirectory = installedFile.getParentFile();
            if (modsDirectory == null) return;
            JsonArray entries = read(modsDirectory);
            for (int i = entries.size() - 1; i >= 0; i--) {
                JsonObject old = entries.get(i).getAsJsonObject();
                if (installedFile.getName().equals(old.get("file").getAsString())) entries.remove(i);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", detail.id);
            entry.addProperty("title", detail.title);
            entry.addProperty("source", detail.apiSource);
            entry.addProperty("file", installedFile.getName());
            entry.addProperty("version", detail.versionNames[versionIndex]);
            entry.addProperty("mcVersion", detail.mcVersionNames[versionIndex]);
            entry.addProperty("loader", detail.loaderNames != null && versionIndex < detail.loaderNames.length
                    ? detail.loaderNames[versionIndex] : "Unknown");
            entries.add(entry);
            write(modsDirectory, entries);
        } catch (Exception ignored) {}
    }

    public static JsonArray read(File modsDirectory) {
        File manifest = new File(modsDirectory, MANIFEST);
        if (!manifest.isFile()) return new JsonArray();
        try (FileReader reader = new FileReader(manifest)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            return array;
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    public static void write(File modsDirectory, JsonArray entries) throws Exception {
        if (!modsDirectory.exists() && !modsDirectory.mkdirs()) return;
        try (FileWriter writer = new FileWriter(new File(modsDirectory, MANIFEST))) {
            Tools.GLOBAL_GSON.toJson(entries, writer);
        }
    }

    public static List<File> listJarFiles(File modsDirectory) {
        ArrayList<File> result = new ArrayList<>();
        File[] files = modsDirectory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) result.add(file);
        }
        return result;
    }
}
