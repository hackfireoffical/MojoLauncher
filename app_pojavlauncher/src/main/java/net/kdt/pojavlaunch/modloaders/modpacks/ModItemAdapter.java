package net.kdt.pojavlaunch.modloaders.modpacks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.SimpleArrayAdapter;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.content.ContentManager;
import net.kdt.pojavlaunch.content.ContentVersion;
import net.kdt.pojavlaunch.content.InstallPlan;
import net.kdt.pojavlaunch.content.Loaders;
import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ImageReceiver;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Future;

public class ModItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements TaskCountListener {
    private static final ModItem[] MOD_ITEMS_EMPTY = new ModItem[0];
    private static final int VIEW_TYPE_MOD_ITEM = 0;
    private static final int VIEW_TYPE_LOADING = 1;

    /* Used when versions haven't loaded yet, default text to reduce layout shifting */
    private final SimpleArrayAdapter<String> mLoadingAdapter = new SimpleArrayAdapter<>(Collections.singletonList("Loading"));
    /* This my seem horribly inefficient but it is in fact the most efficient way without effectively writing a weak collection from scratch */
    private final Set<ViewHolder> mViewHolderSet = Collections.newSetFromMap(new WeakHashMap<>());
    private final ModIconCache mIconCache = new ModIconCache();
    private final SearchResultCallback mSearchResultCallback;
    private ModItem[] mModItems;
    private final ModpackApi mModpackApi;

    /* Cache for ever so slightly rounding the image for the corner not to stick out of the layout */
    private final float mCornerDimensionCache;

    private Future<?> mTaskInProgress;
    private SearchFilters mSearchFilters;
    private SearchResult mCurrentResult;
    private boolean mLastPage;
    private boolean mTasksRunning;


    public ModItemAdapter(Resources resources, ModpackApi api, SearchResultCallback callback) {
        mCornerDimensionCache = resources.getDimension(R.dimen._1sdp) / 250;
        mModpackApi = api;
        mModItems = new ModItem[]{};
        mSearchResultCallback = callback;
    }

    public void performSearchQuery(SearchFilters searchFilters) {
        if(mTaskInProgress != null) {
            mTaskInProgress.cancel(true);
            mTaskInProgress = null;
        }
        this.mSearchFilters = searchFilters;
        this.mLastPage = false;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, null))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
        View view;
        switch (viewType) {
            case VIEW_TYPE_MOD_ITEM:
                // Create a new view, which defines the UI of the list item
                view = layoutInflater.inflate(R.layout.view_mod, viewGroup, false);
                return new ViewHolder(view);
            case VIEW_TYPE_LOADING:
                // Create a new view, which is actually just the progress bar
                view = layoutInflater.inflate(R.layout.view_loading, viewGroup, false);
                return new LoadingViewHolder(view);
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_MOD_ITEM:
                ((ModItemAdapter.ViewHolder)holder).setStateLimited(mModItems[position]);
                break;
            case VIEW_TYPE_LOADING:
                loadMoreResults();
                break;
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public int getItemCount() {
        if(mLastPage || mModItems.length == 0) return mModItems.length;
        return mModItems.length+1;
    }

    private void loadMoreResults() {
        if(mTaskInProgress != null) return;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, mCurrentResult))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @Override
    public int getItemViewType(int position) {
        if(position < mModItems.length) return VIEW_TYPE_MOD_ITEM;
        return VIEW_TYPE_LOADING;
    }

    @Override
    public boolean onUpdateTaskCount(int taskCount) {
        Tools.runOnUiThread(()->{
            mTasksRunning = taskCount != 0;
            for(ViewHolder viewHolder : mViewHolderSet) {
                viewHolder.updateInstallButtonState();
            }
        });
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%d KB", Math.max(1, bytes / 1024));
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String stabilityLabel(int stability) {
        if (stability == ContentVersion.STABILITY_ALPHA) return "alpha";
        if (stability == ContentVersion.STABILITY_BETA) return "beta";
        return "";
    }

    private static boolean listContainsVersion(String supportedVersions, String requestedVersion) {
        if (supportedVersions == null || requestedVersion == null) return false;
        for (String version : supportedVersions.split(",")) {
            if (requestedVersion.equals(version.trim())) return true;
        }
        return false;
    }

    private static boolean loaderListMatches(String preferredLoader, String loaderLabel) {
        if (preferredLoader == null || loaderLabel == null) return false;
        for (String loader : loaderLabel.split(",")) {
            String trimmed = loader.trim();
            if (trimmed.equalsIgnoreCase(preferredLoader)) return true;
            if ("quilt".equalsIgnoreCase(preferredLoader) && "fabric".equalsIgnoreCase(trimmed)) return true;
        }
        return false;
    }

    private static String firstLoader(String loaderLabel) {
        if (loaderLabel == null) return null;
        int comma = loaderLabel.indexOf(',');
        return (comma >= 0 ? loaderLabel.substring(0, comma) : loaderLabel).trim();
    }


    /**
     * Basic viewholder with expension capabilities
     */
    public class ViewHolder extends RecyclerView.ViewHolder {

        private ModDetail mModDetail = null;
        private ModItem mModItem = null;
        private final TextView mTitle, mDescription;
        private final ImageView mIconView, mSourceView;
        private View mExtendedLayout;
        private LinearLayout mVersionList;
        private Spinner mMinecraftSpinner;
        private Button mExtendedButton;
        private int mSelectedVersion = -1;
        private TextView mExtendedErrorTextView;
        private Future<?> mExtensionFuture;
        private Bitmap mThumbnailBitmap;
        private ImageReceiver mImageReceiver;
        private boolean mInstallEnabled;
        private boolean mPreparing;

        public ViewHolder(View view) {
            super(view);
            mViewHolderSet.add(this);
            view.setOnClickListener(v -> {
                if(!hasExtended()){
                    // Inflate the ViewStub
                    mExtendedLayout = ((ViewStub)v.findViewById(R.id.mod_limited_state_stub)).inflate();
                    mExtendedButton = mExtendedLayout.findViewById(R.id.mod_extended_select_version_button);
                    mVersionList = mExtendedLayout.findViewById(R.id.mod_extended_version_list);
                    mMinecraftSpinner = mExtendedLayout.findViewById(R.id.mod_extended_minecraft_spinner);
                    mExtendedErrorTextView = mExtendedLayout.findViewById(R.id.mod_extended_error_textview);

                    mExtendedButton.setOnClickListener(v1 -> startInstall());
                    mVersionList.removeAllViews();
                } else {
                    if(isExtended()) closeDetailedView();
                    else openDetailedView();
                }

                if(isExtended() && mModDetail == null && mExtensionFuture == null) { // only reload if no reloads are in progress
                    setDetailedStateDefault();
                    /*
                     * Why do we do this?
                     * The reason is simple: multithreading is difficult as hell to manage
                     * Let me explain:
                     */
                    mExtensionFuture = new SelfReferencingFuture(myFuture -> {
                        /*
                         * While we are sitting in the function below doing networking, the view might have already gotten recycled.
                         * If we didn't use a Future, we would have extended a ViewHolder with completely unrelated content
                         * or with an error that has never actually happened
                         */
                        ModDetail fetchedDetail = null;
                        try {
                            fetchedDetail = mModpackApi.getModDetails(mModItem);
                        } catch (Exception e) {
                            Log.e("ModItemAdapter", "Failed to load details", e);
                        }
                        final ModDetail finalDetail = fetchedDetail;
                        Tools.runOnUiThread(() -> {
                            /*
                             * Once we enter here, the state we're in is already defined - no view shuffling can happen on the UI
                             * thread while we are on the UI thread ourselves. If we were cancelled, this means that the future
                             * we were supposed to have no longer makes sense, so we return and do not alter the state (since we might
                             * alter the state of an unrelated item otherwise)
                             */
                            if(myFuture.isCancelled()) return;
                            /*
                             * We do not null the future before returning since this field might already belong to a different item with its
                             * own Future, which we don't want to interfere with.
                             * But if the future is not cancelled, it is the right one for this ViewHolder, and we don't need it anymore, so
                             * let's help GC clean it up once we exit!
                             */
                            mExtensionFuture = null;
                            mModDetail = finalDetail;
                            setStateDetailed(finalDetail);
                        });
                    }).startOnExecutor(PojavApplication.sExecutorService);
                }
            });

            // Define click listener for the ViewHolder's View
            mTitle = view.findViewById(R.id.mod_title_textview);
            mDescription = view.findViewById(R.id.mod_body_textview);
            mIconView = view.findViewById(R.id.mod_thumbnail_imageview);
            mSourceView = view.findViewById(R.id.mod_source_imageview);
        }

        /** Display basic info about the moditem */
        public void setStateLimited(ModItem item) {
            mModDetail = null;
            mPreparing = false;
            if(mThumbnailBitmap != null) {
                mIconView.setImageBitmap(null);
                mThumbnailBitmap.recycle();
            }
            if(mImageReceiver != null) {
                mIconCache.cancelImage(mImageReceiver);
            }
            if(mExtensionFuture != null) {
                /*
                 * Since this method reinitializes the ViewHolder for a new mod, this Future stops being ours, so we cancel it
                 * and null it. The rest is handled above
                 */
                mExtensionFuture.cancel(true);
                mExtensionFuture = null;
            }

            mModItem = item;
            // here the previous reference to the image receiver will disappear
            mImageReceiver = bm->{
                mImageReceiver = null;
                mThumbnailBitmap = bm;
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(mIconView.getResources(), bm);
                drawable.setCornerRadius(mCornerDimensionCache * bm.getHeight());
                mIconView.setImageDrawable(drawable);
            };
            mIconCache.getImage(mImageReceiver, mModItem.getIconCacheTag(), mModItem.imageUrl);
            mSourceView.setImageResource(getSourceDrawable(item.apiSource));
            mTitle.setText(item.title);
            mDescription.setText(item.description);

            if(hasExtended()){
                closeDetailedView();
            }
        }

        /** Display extended info/interaction about a mod, shader, resource pack or modpack */
        private void setStateDetailed(ModDetail detailedItem) {
            if(detailedItem != null) {
                mExtendedErrorTextView.setVisibility(View.GONE);
                mVersionList.removeAllViews();

                // Each entry in mcVersionNames is a comma-joined list (e.g. "1.21, 1.21.1"); offer single versions
                LinkedHashSet<String> versionSet = new LinkedHashSet<>();
                for (String joined : detailedItem.mcVersionNames) {
                    if (joined == null) continue;
                    for (String version : joined.split(",")) {
                        String trimmed = version.trim();
                        if (!trimmed.isEmpty()) versionSet.add(trimmed);
                    }
                }
                // Show release versions only, unless that would leave nothing to choose from
                ArrayList<String> minecraftVersions = new ArrayList<>();
                for (String version : versionSet) {
                    if (Loaders.isMinecraftRelease(version)) minecraftVersions.add(version);
                }
                if (minecraftVersions.isEmpty()) minecraftVersions.addAll(versionSet);
                Collections.sort(minecraftVersions, Loaders::compareMinecraftDesc);

                ArrayAdapter<String> minecraftAdapter = new ArrayAdapter<>(
                        mMinecraftSpinner.getContext(),
                        android.R.layout.simple_spinner_item,
                        minecraftVersions);
                minecraftAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mMinecraftSpinner.setAdapter(minecraftAdapter);

                String instanceMinecraft = null;
                String instanceLoader = null;
                try {
                    ContentManager.Target target = ContentManager.currentTarget();
                    instanceMinecraft = target.minecraftVersion;
                    instanceLoader = target.loader;
                } catch (Exception ignored) {
                }

                int initialMinecraft = 0;
                if (instanceMinecraft != null && minecraftVersions.contains(instanceMinecraft)) {
                    initialMinecraft = minecraftVersions.indexOf(instanceMinecraft);
                } else if (mSearchFilters != null && mSearchFilters.mcVersion != null
                        && minecraftVersions.contains(mSearchFilters.mcVersion)) {
                    initialMinecraft = minecraftVersions.indexOf(mSearchFilters.mcVersion);
                }

                final String finalInstanceLoader = instanceLoader;
                mMinecraftSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        populateModVersions(detailedItem, minecraftVersions.get(position), finalInstanceLoader);
                    }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
                if (!minecraftVersions.isEmpty()) {
                    mMinecraftSpinner.setSelection(initialMinecraft);
                    populateModVersions(detailedItem, minecraftVersions.get(initialMinecraft), finalInstanceLoader);
                } else {
                    mSelectedVersion = -1;
                    setInstallEnabled(false);
                }
            } else {
                // Keep the panel open so the error is actually visible
                setInstallEnabled(false);
                mSelectedVersion = -1;
                mVersionList.removeAllViews();
                mExtendedErrorTextView.setVisibility(View.VISIBLE);
            }
        }

        private void populateModVersions(ModDetail detailedItem, String minecraftVersion, String preferredLoader) {
            mVersionList.removeAllViews();
            mSelectedVersion = -1;
            boolean preferredLoaderFound = false;

            for (int i = 0; i < detailedItem.versionNames.length; i++) {
                if (!listContainsVersion(detailedItem.mcVersionNames[i], minecraftVersion)) continue;

                final int versionIndex = i;
                LinearLayout row = new LinearLayout(mVersionList.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(12, 10, 12, 10);

                String loader = detailedItem.loaderNames != null && i < detailedItem.loaderNames.length
                        ? detailedItem.loaderNames[i] : "Unknown";
                int loaderDrawable = getLoaderDrawable(firstLoader(loader));
                if (loaderDrawable != 0) {
                    ImageView loaderIcon = new ImageView(mVersionList.getContext());
                    loaderIcon.setImageResource(loaderDrawable);
                    int size = (int) (32 * mVersionList.getResources().getDisplayMetrics().density);
                    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
                    iconParams.setMarginEnd(12);
                    row.addView(loaderIcon, iconParams);
                }

                StringBuilder label = new StringBuilder(detailedItem.versionNames[i]);
                label.append("  \u2022  ").append(loader);
                if (detailedItem.versionSizes != null && i < detailedItem.versionSizes.length) {
                    String size = formatSize(detailedItem.versionSizes[i]);
                    if (!size.isEmpty()) label.append("  \u2022  ").append(size);
                }
                if (detailedItem.contentVersions != null && i < detailedItem.contentVersions.length) {
                    String stability = stabilityLabel(detailedItem.contentVersions[i].stability);
                    if (!stability.isEmpty()) label.append("  \u2022  ").append(stability);
                }

                TextView versionText = new TextView(mVersionList.getContext());
                versionText.setText(label.toString());
                versionText.setTextSize(15);
                versionText.setTextColor(mVersionList.getResources().getColor(R.color.primary_text));
                row.addView(versionText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                row.setTag(versionIndex);
                row.setOnClickListener(v -> {
                    Object tag = v.getTag();
                    mSelectedVersion = tag instanceof Integer ? (Integer) tag : versionIndex;
                    updateVersionSelection();
                    setInstallEnabled(true);
                });
                mVersionList.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                // APIs return newest versions first, so the first compatible version is selected automatically,
                // preferring one that matches the selected instance's loader
                if (loaderListMatches(preferredLoader, loader) && !preferredLoaderFound) {
                    mSelectedVersion = versionIndex;
                    preferredLoaderFound = true;
                } else if (mSelectedVersion == -1) {
                    mSelectedVersion = versionIndex;
                }
            }

            updateVersionSelection();
            setInstallEnabled(mSelectedVersion >= 0);
        }

        /** Download button: modpacks go to the modpack installer, everything else through the content system. */
        private void startInstall() {
            if (mSelectedVersion < 0 || mModDetail == null || mPreparing) return;
            final ModDetail detail = mModDetail;
            final int index = mSelectedVersion;
            final Context context = mExtendedButton.getContext();

            if (detail.isModpack || detail.contentVersions == null || index >= detail.contentVersions.length) {
                mModpackApi.handleModpackInstallation(context.getApplicationContext(), detail, index);
                return;
            }

            final ContentVersion version = detail.contentVersions[index];
            final ContentManager.Target target;
            try {
                target = ContentManager.currentTarget();
            } catch (IOException e) {
                Toast.makeText(context, "Select an instance first", Toast.LENGTH_LONG).show();
                return;
            }

            mPreparing = true;
            updateInstallButtonState();
            ContentManager.preparePlan(version, target, (plan, error) -> {
                mPreparing = false;
                updateInstallButtonState();
                if (error != null || plan == null) {
                    String reason = error != null && error.getMessage() != null ? error.getMessage() : "unknown error";
                    Toast.makeText(context, "Could not check dependencies: " + reason, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmAndInstall(context, plan, target);
            });
        }

        private void confirmAndInstall(Context context, InstallPlan plan, ContentManager.Target target) {
            StringBuilder message = new StringBuilder();
            String compatibility = plan.compatibilityWarning();
            if (compatibility != null) message.append(compatibility).append("\n\n");
            if (plan.hasDependencies()) {
                message.append("This requires:\n");
                for (InstallPlan.Item item : plan.dependencies) {
                    message.append("\u2022 ").append(item.title).append('\n');
                }
            }
            for (String warning : plan.warnings) {
                message.append("\n\u26A0 ").append(warning);
            }

            if (message.length() == 0) {
                startPlan(context, plan, false, target);
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(plan.hasDependencies() ? "Required dependencies" : "Check before installing")
                    .setMessage(message.toString().trim())
                    .setNegativeButton("Cancel", null);
            if (plan.hasDependencies()) {
                builder.setNeutralButton("Download only this", (dialog, which) -> startPlan(context, plan, false, target));
                builder.setPositiveButton("Download + required", (dialog, which) -> startPlan(context, plan, true, target));
            } else {
                builder.setPositiveButton("Install anyway", (dialog, which) -> startPlan(context, plan, false, target));
            }
            builder.show();
        }

        private void startPlan(Context context, InstallPlan plan, boolean withDependencies, ContentManager.Target target) {
            if (!ContentManager.install(context, plan, withDependencies, target, null)) {
                Toast.makeText(context, "A download is already in progress", Toast.LENGTH_SHORT).show();
            }
        }

        private void openDetailedView() {
            mExtendedLayout.setVisibility(View.VISIBLE);
            mDescription.setMaxLines(99);

            // We need to align to the longer section
            int futureBottom = mDescription.getBottom() + Tools.mesureTextviewHeight(mDescription) - mDescription.getHeight();
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) mExtendedLayout.getLayoutParams();
            params.topToBottom = futureBottom > mIconView.getBottom() ? R.id.mod_body_textview : R.id.mod_thumbnail_imageview;
            mExtendedLayout.setLayoutParams(params);
        }

        private void closeDetailedView(){
            mExtendedLayout.setVisibility(View.GONE);
            mDescription.setMaxLines(3);
        }

        private void setDetailedStateDefault() {
            setInstallEnabled(false);
            mVersionList.removeAllViews();
            TextView loading = new TextView(mVersionList.getContext());
            loading.setText("Loading versions\u2026");
            mVersionList.addView(loading);
            mExtendedErrorTextView.setVisibility(View.GONE);
            openDetailedView();
        }

        private boolean hasExtended(){
            return mExtendedLayout != null;
        }

        private boolean isExtended(){
            return hasExtended() && mExtendedLayout.getVisibility() == View.VISIBLE;
        }

        private int getSourceDrawable(int apiSource) {
            switch (apiSource) {
                case Constants.SOURCE_CURSEFORGE:
                    return R.drawable.ic_curseforge;
                case Constants.SOURCE_MODRINTH:
                    return R.drawable.ic_modrinth;
                default:
                    throw new RuntimeException("Unknown API source");
            }
        }

        private void setInstallEnabled(boolean enabled) {
            mInstallEnabled = enabled;
            updateInstallButtonState();
        }

        private void updateInstallButtonState() {
            if(mExtendedButton != null)
                mExtendedButton.setEnabled(mInstallEnabled && !mTasksRunning && !mPreparing);
        }

        private void updateVersionSelection() {
            if (mVersionList == null) return;
            for (int i = 0; i < mVersionList.getChildCount(); i++) {
                View child = mVersionList.getChildAt(i);
                Object tag = child.getTag();
                boolean selected = tag instanceof Integer && ((Integer) tag) == mSelectedVersion;
                child.setAlpha(selected ? 1.0f : 0.72f);
            }
        }

        private int getLoaderDrawable(String loader) {
            if (loader == null) return 0;
            switch (loader.toLowerCase(Locale.ROOT)) {
                case "fabric": return R.drawable.ic_fabric;
                case "forge": return R.drawable.ic_forge;
                case "neoforge": return R.drawable.ic_neoforge;
                case "quilt": return R.drawable.ic_quilt;
                default: return 0;
            }
        }
    }

    /**
     * The view holder used to hold the progress bar at the end of the list
     */
    private static class LoadingViewHolder extends RecyclerView.ViewHolder {
        public LoadingViewHolder(View view) {
            super(view);
        }
    }

    private class SearchApiTask implements SelfReferencingFuture.FutureInterface {
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousResult;

        private SearchApiTask(SearchFilters searchFilters, SearchResult previousResult) {
            this.mSearchFilters = searchFilters;
            this.mPreviousResult = previousResult;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void run(Future<?> myFuture) {
            SearchResult result = mModpackApi.searchMod(mSearchFilters, mPreviousResult);
            ModItem[] resultModItems = result != null ? result.results : null;
            if(resultModItems != null && resultModItems.length != 0 && mPreviousResult != null) {
                ModItem[] newModItems = new ModItem[resultModItems.length + mModItems.length];
                System.arraycopy(mModItems, 0, newModItems, 0, mModItems.length);
                System.arraycopy(resultModItems, 0, newModItems, mModItems.length, resultModItems.length);
                resultModItems = newModItems;
            }
            ModItem[] finalModItems = resultModItems;
            Tools.runOnUiThread(() -> {
                if(myFuture.isCancelled()) return;
                mTaskInProgress = null;
                if(finalModItems == null) {
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                }else if(finalModItems.length == 0) {
                    if(mPreviousResult != null) {
                        mLastPage = true;
                        notifyItemChanged(mModItems.length);
                        mSearchResultCallback.onSearchFinished();
                        return;
                    }
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS);
                }else{
                    mSearchResultCallback.onSearchFinished();
                }
                mCurrentResult = result;
                if(finalModItems == null) {
                    mModItems = MOD_ITEMS_EMPTY;
                    notifyDataSetChanged();
                    return;
                }
                if(mPreviousResult != null) {
                    int prevLength = mModItems.length;
                    mModItems = finalModItems;
                    notifyItemChanged(prevLength);
                    notifyItemRangeInserted(prevLength+1, mModItems.length);
                }else {
                    mModItems = finalModItems;
                    notifyDataSetChanged();
                }
            });
        }
    }

    public interface SearchResultCallback {
        int ERROR_INTERNAL = 0;
        int ERROR_NO_RESULTS = 1;
        void onSearchFinished();
        void onSearchError(int error);
    }
}
