package net.kdt.pojavlaunch.prefs.screens;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modloaders.modpacks.InstalledModManager;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;

import git.artdeell.mojo.R;

public class InstalledModsFragment extends Fragment {
    private LinearLayout list;
    private CommonApi api;

    public InstalledModsFragment() {
        super();
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container,
                             Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getResources().getColor(R.color.background_app));

        Button refresh = new Button(requireContext());
        refresh.setText("Check for updates");
        refresh.setOnClickListener(v -> checkUpdates());
        root.addView(refresh);

        TextView note = new TextView(requireContext());
        note.setText("Installed mods");
        note.setTextSize(18);
        root.addView(note);

        list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        api = new CommonApi(getString(R.string.curseforge_api_key));
        loadInstalled();
        return root;
    }

    private File getModsDirectory() {
        Instance instance = Instances.loadSelectedInstance();
        return instance == null ? null : new File(instance.getGameDirectory(), "mods");
    }

    private void loadInstalled() {
        list.removeAllViews();
        File mods = getModsDirectory();
        if (mods == null) {
            addText("No instance selected.");
            return;
        }

        JsonArray entries = InstalledModManager.read(mods);
        if (entries.size() == 0) {
            addText("No managed mods yet. Mods installed outside MojoLauncher may still be present.");
            for (File file : InstalledModManager.listJarFiles(mods)) addUnmanaged(file);
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            addManaged(entries.get(i).getAsJsonObject());
        }
    }

    private void addManaged(JsonObject entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 10, 0, 10);

        TextView title = new TextView(requireContext());
        title.setText(entry.get("title").getAsString() + "  •  " + entry.get("version").getAsString());
        title.setTextSize(16);
        row.addView(title);

        TextView info = new TextView(requireContext());
        info.setText(entry.get("file").getAsString());
        row.addView(info);

        LinearLayout actions = new LinearLayout(requireContext());
        Button update = new Button(requireContext());
        update.setText("Check update");
        update.setOnClickListener(v -> checkSingleUpdate(entry, update));
        actions.addView(update, new LinearLayout.LayoutParams(0, -2, 1));

        Button remove = new Button(requireContext());
        remove.setText("Remove");
        remove.setOnClickListener(v -> remove(entry));
        actions.addView(remove, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(actions);
        list.addView(row);
    }

    private void addUnmanaged(File file) {
        TextView text = new TextView(requireContext());
        text.setText(file.getName() + "  •  not managed");
        text.setPadding(0, 8, 0, 8);
        list.addView(text);
    }

    private void addText(String value) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setPadding(0, 12, 0, 12);
        list.addView(text);
    }

    private void checkUpdates() {
        File mods = getModsDirectory();
        if (mods == null) return;
        JsonArray entries = InstalledModManager.read(mods);
        PojavApplication.sExecutorService.execute(() -> {
            int updates = 0;
            for (int i = 0; i < entries.size(); i++) {
                JsonObject entry = entries.get(i).getAsJsonObject();
                if (findUpdate(entry) != null) updates++;
            }
            final int found = updates;
            Tools.runOnUiThread(() -> new AlertDialog.Builder(requireContext())
                    .setTitle("Mod updates")
                    .setMessage(found == 0 ? "All managed mods are up to date." : found + " mod update(s) are available.")
                    .setPositiveButton("OK", null)
                    .show());
        });
    }

    private void checkSingleUpdate(JsonObject entry, Button button) {
        button.setEnabled(false);
        PojavApplication.sExecutorService.execute(() -> {
            ModDetail detail = findUpdate(entry);
            Tools.runOnUiThread(() -> {
                button.setEnabled(true);
                if (detail == null) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("No update")
                            .setMessage("No newer compatible version was found for this Minecraft version and loader.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle("Update available")
                        .setMessage(entry.get("title").getAsString() + "\n\nInstalled: "
                                + entry.get("version").getAsString() + "\nNew: "
                                + detail.versionNames[findVersionIndex(detail, entry)])
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Update", (d, w) -> performUpdate(entry, detail))
                        .show();
            });
        });
    }

    private ModDetail findUpdate(JsonObject entry) {
        try {
            ModItem item = new ModItem(
                    entry.get("source").getAsInt() == Constants.SOURCE_CURSEFORGE
                            ? Constants.SOURCE_CURSEFORGE : Constants.SOURCE_MODRINTH,
                    false, entry.get("id").getAsString(),
                    entry.get("title").getAsString(), "", "");
            item.contentType = Constants.CONTENT_MOD;
            ModDetail detail = api.getModDetails(item);
            if (detail == null) return null;
            int index = findVersionIndex(detail, entry);
            if (index < 0) return null;
            String installed = entry.get("version").getAsString();
            return installed.equals(detail.versionNames[index]) ? null : detail;
        } catch (Exception e) {
            return null;
        }
    }

    private int findVersionIndex(ModDetail detail, JsonObject entry) {
        String mc = entry.has("mcVersion") ? entry.get("mcVersion").getAsString() : "";
        String loader = entry.has("loader") ? entry.get("loader").getAsString() : "";
        for (int i = 0; i < detail.versionNames.length; i++) {
            if (!containsVersion(detail.mcVersionNames[i], mc)) continue;
            String candidateLoader = detail.loaderNames != null && i < detail.loaderNames.length
                    ? detail.loaderNames[i] : "Unknown";
            if (!loader.isEmpty() && !"Unknown".equalsIgnoreCase(loader)
                    && !loader.equalsIgnoreCase(candidateLoader)) continue;
            return i;
        }
        return -1;
    }

    private boolean containsVersion(String values, String requested) {
        if (values == null || requested == null) return false;
        for (String value : values.split(",\\s*")) if (requested.equals(value.trim())) return true;
        return false;
    }

    private void performUpdate(JsonObject entry, ModDetail detail) {
        int newIndex = findVersionIndex(detail, entry);
        if (newIndex < 0) return;
        File mods = getModsDirectory();
        if (mods == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File old = new File(mods, entry.get("file").getAsString());
                api.installMod(detail, newIndex, Instances.loadSelectedInstance().getGameDirectory());
                if (old.isFile()) old.delete();
                String newName = new File(new java.net.URL(detail.versionUrls[newIndex]).getPath()).getName();
                JsonArray entries = InstalledModManager.read(mods);
                for (int i = 0; i < entries.size(); i++) {
                    JsonObject e = entries.get(i).getAsJsonObject();
                    if (entry.get("file").getAsString().equals(e.get("file").getAsString())) {
                        e.addProperty("file", newName);
                        e.addProperty("version", detail.versionNames[newIndex]);
                        e.addProperty("mcVersion", detail.mcVersionNames[newIndex]);
                        e.addProperty("loader", detail.loaderNames[newIndex]);
                    }
                }
                InstalledModManager.write(mods, entries);
                Tools.runOnUiThread(this::loadInstalled);
            } catch (Exception e) {
                Tools.showErrorRemote("Mod update failed", e);
            }
        });
    }

    private void remove(JsonObject entry) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove mod?")
                .setMessage(entry.get("title").getAsString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    try {
                        File mods = getModsDirectory();
                        if (mods == null) return;
                        File target = new File(mods, entry.get("file").getAsString());
                        target.delete();
                        JsonArray entries = InstalledModManager.read(mods);
                        for (int i = entries.size() - 1; i >= 0; i--) {
                            if (entry.get("file").getAsString().equals(entries.get(i).getAsJsonObject().get("file").getAsString()))
                                entries.remove(i);
                        }
                        InstalledModManager.write(mods, entries);
                        loadInstalled();
                    } catch (Exception e) {
                        Tools.showErrorRemote("Could not remove mod", e);
                    }
                }).show();
    }
}
