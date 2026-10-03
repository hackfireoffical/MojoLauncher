package net.kdt.pojavlaunch.prefs.screens;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.SearchModFragment;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

public class ContentDownloaderFragment extends Fragment {
    public ContentDownloaderFragment() {
        super();
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container,
                             Bundle savedInstanceState) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(getResources().getColor(git.artdeell.mojo.R.color.background_app));

        addButton(layout, "Mods", Constants.CONTENT_MOD);
        Button manager = new Button(requireContext());
        manager.setText("Installed Mods");
        manager.setOnClickListener(v -> Tools.swapFragment(requireActivity(), InstalledModsFragment.class,
                "InstalledModsFragment", null));
        layout.addView(manager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addButton(layout, "Shader Packs", Constants.CONTENT_SHADER);
        addButton(layout, "Texture Packs", Constants.CONTENT_RESOURCEPACK);
        return layout;
    }

    private void addButton(LinearLayout layout, String title, String contentType) {
        Button button = new Button(requireContext());
        button.setText(title);
        button.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString(SearchModFragment.ARG_CONTENT_TYPE, contentType);
            Tools.swapFragment(requireActivity(), SearchModFragment.class,
                    SearchModFragment.TAG, args);
        });
        layout.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
