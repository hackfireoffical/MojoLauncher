package net.kdt.pojavlaunch.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.InstalledModManager;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tracks the content installed into one instance. Stored as {gameDir}/.mojo-content.json.
 * Replaces the old per-mods-folder manifest, which is migrated automatically on first load.
 */
public final class InstalledStore {
    private static final String FILE_NAME = ".mojo-content.json";
    private static final Object LOCK = new Object();

    private InstalledStore() {}

    public static List<InstalledEntry> load(File gameDir) {
        synchronized (LOCK) {
            List<InstalledEntry> entries = read(gameDir);
            if (entries != null) return entries;
            List<InstalledEntry> migrated = migrateLegacy(gameDir);
            if (!migrated.isEmpty()) {
                try {
                    write(gameDir, migrated);
                } catch (IOException ignored) {
                }
            }
            return migrated;
        }
    }

    public static void upsert(File gameDir, InstalledEntry entry) throws IOException {
        synchronized (LOCK) {
            List<InstalledEntry> entries = load(gameDir);
            for (int i = entries.size() - 1; i >= 0; i--) {
                InstalledEntry existing = entries.get(i);
                boolean sameProject = existing.key().equals(entry.key());
                boolean sameFile = existing.file != null && existing.file.equals(entry.file);
                if (sameProject || sameFile) entries.remove(i);
            }
            entries.add(entry);
            write(gameDir, entries);
        }
    }

    public static void remove(File gameDir, InstalledEntry entry) throws IOException {
        synchronized (LOCK) {
            List<InstalledEntry> entries = load(gameDir);
            for (int i = entries.size() - 1; i >= 0; i--) {
                InstalledEntry existing = entries.get(i);
                if (existing.key().equals(entry.key()) || (existing.file != null && existing.file.equals(entry.file))) {
                    entries.remove(i);
                }
            }
            write(gameDir, entries);
        }
    }

    public static InstalledEntry find(List<InstalledEntry> entries, int source, String projectId) {
        for (InstalledEntry entry : entries) {
            if (entry.source == source && projectId != null && projectId.equals(entry.projectId)) return entry;
        }
        return null;
    }

    private static List<InstalledEntry> read(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (!file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            InstalledEntry[] array = Tools.GLOBAL_GSON.fromJson(reader, InstalledEntry[].class);
            ArrayList<InstalledEntry> list = new ArrayList<>();
            if (array != null) {
                for (InstalledEntry entry : array) {
                    if (entry == null || entry.projectId == null || entry.file == null) continue;
                    if (entry.dependsOn == null) entry.dependsOn = new ArrayList<>();
                    list.add(entry);
                }
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void write(File gameDir, List<InstalledEntry> entries) throws IOException {
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("Cannot create " + gameDir);
        File target = new File(gameDir, FILE_NAME);
        File temp = new File(gameDir, FILE_NAME + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            Tools.GLOBAL_GSON.toJson(entries.toArray(new InstalledEntry[0]), InstalledEntry[].class, writer);
        }
        if (!temp.renameTo(target)) {
            // renameTo can fail when the target exists on some filesystems
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
            if (!temp.renameTo(target)) throw new IOException("Cannot write " + target);
        }
    }

    /** Imports entries from the old mods/.mojo-mods.json manifest. */
    private static List<InstalledEntry> migrateLegacy(File gameDir) {
        ArrayList<InstalledEntry> result = new ArrayList<>();
        try {
            JsonArray legacy = InstalledModManager.read(new File(gameDir, "mods"));
            for (JsonElement element : legacy) {
                if (!element.isJsonObject()) continue;
                JsonObject old = element.getAsJsonObject();
                String id = Json.str(old, "id");
                String file = Json.str(old, "file");
                if (id == null || file == null) continue;
                InstalledEntry entry = new InstalledEntry();
                entry.source = (int) Json.num(old, "source", Constants.SOURCE_MODRINTH);
                entry.projectId = id;
                entry.title = Json.str(old, "title", id);
                entry.contentType = Constants.CONTENT_MOD;
                entry.versionName = Json.str(old, "version", "");
                entry.mcVersion = Json.str(old, "mcVersion", "");
                entry.loader = Json.str(old, "loader", "");
                entry.file = "mods/" + file;
                entry.explicit = true;
                result.add(entry);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** Files in the managed folders that are not tracked, so the manager can still show them. */
    public static List<String> findUntracked(File gameDir, List<InstalledEntry> tracked) {
        ArrayList<String> result = new ArrayList<>();
        for (String folder : Arrays.asList("mods", "shaderpacks", "resourcepacks")) {
            File[] files = new File(gameDir, folder).listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File file : files) {
                if (!file.isFile() || file.getName().startsWith(".")) continue;
                String relative = folder + "/" + file.getName();
                boolean known = false;
                for (InstalledEntry entry : tracked) {
                    if (relative.equals(entry.file)) {
                        known = true;
                        break;
                    }
                }
                if (!known) result.add(relative);
            }
        }
        return result;
    }
}
