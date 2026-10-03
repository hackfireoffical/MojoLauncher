package net.kdt.pojavlaunch.prefs.screens;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.content.ContentManager;
import net.kdt.pojavlaunch.content.ContentProviders;
import net.kdt.pojavlaunch.content.InstalledEntry;
import net.kdt.pojavlaunch.content.InstalledStore;
import net.kdt.pojavlaunch.content.Loaders;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows everything installed in the selected instance (mods, shader packs, resource packs),
 * lets the user remove it, check for updates and update it.
 */
public class InstalledModsFragment extends Fragment {
    private LinearLayout mList;
    private TextView mStatus;
    private Button mCheckButton;
    private Button mUpdateAllButton;
    private final Map<String, ContentManager.UpdateInfo> mUpdates = new HashMap<>();
    private String mNotice = "";
    private boolean mChecking;

    public InstalledModsFragment() {
        super();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context context = requireContext();
        // The providers need the CurseForge key before anything is looked up
        ContentProviders.configure(getString(R.string.curseforge_api_key));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getResources().getColor(R.color.background_app));

        root.addView(label(context, "Installed content", 20));
        mStatus = label(context, "", 13);
        mStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(mStatus);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        mCheckButton = new Button(context);
        mCheckButton.setText("Check for updates");
        mCheckButton.setOnClickListener(v -> checkUpdates());
        buttons.addView(mCheckButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mUpdateAllButton = new Button(context);
        mUpdateAllButton.setText("Update all");
        mUpdateAllButton.setVisibility(View.GONE);
        mUpdateAllButton.setOnClickListener(v -> updateAll());
        buttons.addView(mUpdateAllButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        ScrollView scroll = new ScrollView(context);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        reload();
        return root;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private TextView label(Context context, String text, int sizeSp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(getResources().getColor(R.color.primary_text));
        return view;
    }

    private static int typeOrder(InstalledEntry entry) {
        if (Constants.CONTENT_SHADER.equals(entry.contentType)) return 1;
        if (Constants.CONTENT_RESOURCEPACK.equals(entry.contentType)) return 2;
        return 0;
    }

    private static String typeHeading(int order) {
        if (order == 1) return "Shader packs";
        if (order == 2) return "Resource packs";
        return "Mods";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void reload() {
        if (mList == null) return;
        mList.removeAllViews();
        final ContentManager.Target target;
        try {
            target = ContentManager.currentTarget();
        } catch (IOException e) {
            mStatus.setText("No instance selected.");
            mUpdateAllButton.setVisibility(View.GONE);
            return;
        }

        String minecraft = target.minecraftVersion != null ? "Minecraft " + target.minecraftVersion : "unknown Minecraft version";
        String loader = target.loader != null ? Loaders.display(target.loader) : "no mod loader";
        mStatus.setText(minecraft + "  \u2022  " + loader + (mNotice.isEmpty() ? "" : "\n" + mNotice));

        List<InstalledEntry> entries = new ArrayList<>(InstalledStore.load(target.gameDir));
        Collections.sort(entries, (a, b) -> {
            int order = Integer.compare(typeOrder(a), typeOrder(b));
            return order != 0 ? order : safe(a.title).compareToIgnoreCase(safe(b.title));
        });

        int lastOrder = -1;
        for (InstalledEntry entry : entries) {
            int order = typeOrder(entry);
            if (order != lastOrder) {
                addHeading(typeHeading(order));
                lastOrder = order;
            }
            addEntryRow(target, entry);
        }

        List<String> untracked = InstalledStore.findUntracked(target.gameDir, entries);
        if (!untracked.isEmpty()) {
            addHeading("Not installed through MojoLauncher");
            for (String relative : untracked) addUntrackedRow(target, relative);
        }

        if (entries.isEmpty() && untracked.isEmpty()) {
            TextView empty = label(requireContext(), "Nothing installed yet. Use the content downloader to add mods, shader packs and resource packs.", 14);
            empty.setPadding(0, dp(12), 0, dp(12));
            mList.addView(empty);
        }

        if (mUpdates.isEmpty()) {
            mUpdateAllButton.setVisibility(View.GONE);
        } else {
            mUpdateAllButton.setText("Update all (" + mUpdates.size() + ")");
            mUpdateAllButton.setVisibility(View.VISIBLE);
        }
    }

    private void addHeading(String text) {
        TextView heading = label(requireContext(), text, 16);
        heading.setPadding(0, dp(14), 0, dp(4));
        mList.addView(heading);
    }

    private void addEntryRow(ContentManager.Target target, InstalledEntry entry) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        String version = safe(entry.versionName);
        row.addView(label(context, safe(entry.title) + (version.isEmpty() ? "" : "  \u2022  " + version), 15));

        StringBuilder info = new StringBuilder(new File(safe(entry.file)).getName());
        if (!entry.explicit) info.append("  \u2022  dependency");
        ContentManager.UpdateInfo update = mUpdates.get(entry.key());
        if (update != null) info.append("  \u2022  update: ").append(update.latest.name);
        TextView infoView = label(context, info.toString(), 12);
        infoView.setAlpha(0.7f);
        row.addView(infoView);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (update != null) {
            Button updateButton = new Button(context);
            updateButton.setText("Update");
            updateButton.setOnClickListener(v -> runUpdates(target, Collections.singletonList(update)));
            actions.addView(updateButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        Button removeButton = new Button(context);
        removeButton.setText("Remove");
        removeButton.setOnClickListener(v -> confirmRemove(target, entry));
        actions.addView(removeButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(actions);
        mList.addView(row);
    }

    private void addUntrackedRow(ContentManager.Target target, String relative) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView name = label(context, relative, 13);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button removeButton = new Button(context);
        removeButton.setText("Delete");
        removeButton.setOnClickListener(v -> new AlertDialog.Builder(context)
                .setTitle("Delete file?")
                .setMessage(relative + "\n\nThis file was not installed by MojoLauncher.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    File file = new File(target.gameDir, relative);
                    if (file.isFile() && !file.delete()) {
                        Toast.makeText(context, "Could not delete " + file.getName(), Toast.LENGTH_LONG).show();
                    }
                    reload();
                }).show());
        row.addView(removeButton);
        mList.addView(row);
    }

    // ----- remove -----

    private void confirmRemove(ContentManager.Target target, InstalledEntry entry) {
        StringBuilder message = new StringBuilder(safe(entry.title));
        List<InstalledEntry> dependents = ContentManager.dependentsOf(target.gameDir, entry);
        if (!dependents.isEmpty()) {
            message.append("\n\nRequired by: ");
            for (int i = 0; i < dependents.size(); i++) {
                if (i > 0) message.append(", ");
                message.append(safe(dependents.get(i).title));
            }
            message.append("\nThey may stop working without it.");
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove?")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> doRemove(target, entry))
                .show();
    }

    private void doRemove(ContentManager.Target target, InstalledEntry entry) {
        try {
            ContentManager.removeEntry(target.gameDir, entry);
        } catch (IOException e) {
            Tools.showErrorRemote("Could not remove " + safe(entry.title), e);
            return;
        }
        mUpdates.remove(entry.key());
        List<InstalledEntry> orphans = ContentManager.findOrphans(target.gameDir);
        reload();
        if (orphans.isEmpty()) return;

        StringBuilder message = new StringBuilder("These were installed only as dependencies and nothing needs them any more:\n");
        for (InstalledEntry orphan : orphans) message.append("\u2022 ").append(safe(orphan.title)).append('\n');
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove unused dependencies?")
                .setMessage(message.toString().trim())
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove them", (d, w) -> {
                    for (InstalledEntry orphan : orphans) {
                        try {
                            ContentManager.removeEntry(target.gameDir, orphan);
                            mUpdates.remove(orphan.key());
                        } catch (IOException e) {
                            Tools.showErrorRemote("Could not remove " + safe(orphan.title), e);
                        }
                    }
                    reload();
                }).show();
    }

    // ----- updates -----

    private void checkUpdates() {
        if (mChecking) return;
        final ContentManager.Target target;
        try {
            target = ContentManager.currentTarget();
        } catch (IOException e) {
            Toast.makeText(requireContext(), "Select an instance first", Toast.LENGTH_SHORT).show();
            return;
        }
        mChecking = true;
        mCheckButton.setEnabled(false);
        mNotice = "Checking for updates...";
        reload();
        PojavApplication.sExecutorService.execute(() -> {
            final List<ContentManager.UpdateInfo> found = ContentManager.checkUpdates(target);
            Tools.runOnUiThread(() -> {
                mChecking = false;
                if (!isAdded()) return;
                mCheckButton.setEnabled(true);
                mUpdates.clear();
                for (ContentManager.UpdateInfo info : found) mUpdates.put(info.entry.key(), info);
                mNotice = found.isEmpty() ? "Everything is up to date." : found.size() + " update(s) available.";
                reload();
            });
        });
    }

    private void updateAll() {
        try {
            runUpdates(ContentManager.currentTarget(), new ArrayList<>(mUpdates.values()));
        } catch (IOException e) {
            Toast.makeText(requireContext(), "Select an instance first", Toast.LENGTH_SHORT).show();
        }
    }

    private void runUpdates(ContentManager.Target target, List<ContentManager.UpdateInfo> updates) {
        if (updates.isEmpty()) return;
        final List<ContentManager.UpdateInfo> work = new ArrayList<>(updates);
        boolean started = ContentManager.updateEntries(requireContext(), target, work, () -> {
            if (!isAdded()) return;
            for (ContentManager.UpdateInfo info : work) mUpdates.remove(info.entry.key());
            mNotice = "Update finished.";
            reload();
        });
        if (!started) {
            Toast.makeText(requireContext(), "A download is already in progress", Toast.LENGTH_SHORT).show();
        } else {
            mNotice = "Updating...";
            reload();
        }
    }
}
