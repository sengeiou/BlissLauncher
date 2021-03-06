package foundation.e.blisslauncher.features.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Process;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LongSparseArray;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import foundation.e.blisslauncher.BlissLauncher;
import foundation.e.blisslauncher.core.Utilities;
import foundation.e.blisslauncher.core.broadcast.PackageAddedRemovedHandler;
import foundation.e.blisslauncher.core.database.DatabaseManager;
import foundation.e.blisslauncher.core.database.model.ApplicationItem;
import foundation.e.blisslauncher.core.database.model.FolderItem;
import foundation.e.blisslauncher.core.database.model.LauncherItem;
import foundation.e.blisslauncher.core.database.model.ShortcutItem;
import foundation.e.blisslauncher.core.executors.AppExecutors;
import foundation.e.blisslauncher.core.utils.AppUtils;
import foundation.e.blisslauncher.core.utils.Constants;
import foundation.e.blisslauncher.core.utils.GraphicsUtil;
import foundation.e.blisslauncher.core.utils.UserHandle;
import foundation.e.blisslauncher.features.shortcuts.DeepShortcutManager;
import foundation.e.blisslauncher.features.shortcuts.ShortcutInfoCompat;

public class AppProvider {

    /**
     * Represents networkItems in workspace.
     */
    private List<LauncherItem> mLauncherItems;

    /**
     * Represents networkItems stored in database.
     */
    private List<LauncherItem> mDatabaseItems;

    /**
     * Represents all applications installed in device.
     */
    private Map<String, ApplicationItem> mApplicationItems;

    /**
     * Represents all shortcuts which user has created.
     */
    private Map<String, ShortcutInfoCompat> mShortcutInfoCompats;

    private boolean appsLoaded = false;
    private boolean shortcutsLoaded = false;
    private boolean databaseLoaded = false;

    private AppsRepository mAppsRepository;

    private static final String MICROG_PACKAGE = "com.google.android.gms";
    private static final String MUPDF_PACKAGE = "com.artifex.mupdf.mini.app";
    private static final String OPENKEYCHAIN_PACKAGE = "org.sufficientlysecure.keychain";
    private static final String LIBREOFFICE_PACKAGE = "org.documentfoundation.libreoffice";

    public static HashSet<String> DISABLED_PACKAGE = new HashSet<>();

    static {
        DISABLED_PACKAGE.add(MICROG_PACKAGE);
        DISABLED_PACKAGE.add(MUPDF_PACKAGE);
        DISABLED_PACKAGE.add(OPENKEYCHAIN_PACKAGE);
        DISABLED_PACKAGE.add(LIBREOFFICE_PACKAGE);
    }

    private PackageAddedRemovedHandler mPackageAddedRemovedHandler;

    private static final String TAG = "AppProvider";
    private Context mContext;
    private static AppProvider sInstance;

    private AppProvider(Context context) {
        this.mContext = context;
        initialise();
    }

    private void initialise() {
        final UserManager manager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        assert manager != null;

        final LauncherApps launcher = (LauncherApps) mContext.getSystemService(
                Context.LAUNCHER_APPS_SERVICE);
        assert launcher != null;

        launcher.registerCallback(new LauncherApps.Callback() {
            @Override
            public void onPackageRemoved(String packageName, android.os.UserHandle user) {
                if (packageName.equalsIgnoreCase(MICROG_PACKAGE) || packageName.equalsIgnoreCase(
                        MUPDF_PACKAGE)) {
                    return;
                }

                PackageAddedRemovedHandler.handleEvent(mContext,
                        "android.intent.action.PACKAGE_REMOVED",
                        packageName, new UserHandle(manager.getSerialNumberForUser(user), user),
                        false
                );
            }

            @Override
            public void onPackageAdded(String packageName, android.os.UserHandle user) {
                if (packageName.equalsIgnoreCase(MICROG_PACKAGE) || packageName.equalsIgnoreCase(
                        MUPDF_PACKAGE)) {
                    return;
                }

                PackageAddedRemovedHandler.handleEvent(mContext,
                        "android.intent.action.PACKAGE_ADDED",
                        packageName, new UserHandle(manager.getSerialNumberForUser(user), user),
                        false
                );
            }

            @Override
            public void onPackageChanged(String packageName, android.os.UserHandle user) {
                if (packageName.equalsIgnoreCase(MICROG_PACKAGE) || packageName.equalsIgnoreCase(
                        MUPDF_PACKAGE)) {
                    return;
                }

                PackageAddedRemovedHandler.handleEvent(mContext,
                        "android.intent.action.PACKAGE_CHANGED",
                        packageName, new UserHandle(manager.getSerialNumberForUser(user), user),
                        true
                );
            }

            @Override
            public void onPackagesAvailable(String[] packageNames, android.os.UserHandle user,
                    boolean replacing) {
                PackageAddedRemovedHandler.handleEvent(mContext,
                        "android.intent.action.MEDIA_MOUNTED",
                        null, new UserHandle(manager.getSerialNumberForUser(user), user), false
                );

            }

            @Override
            public void onPackagesUnavailable(String[] packageNames, android.os.UserHandle user,
                    boolean replacing) {
                PackageAddedRemovedHandler.handleEvent(mContext,
                        "android.intent.action.MEDIA_UNMOUNTED",
                        null, new UserHandle(manager.getSerialNumberForUser(user), user), false
                );
            }
        });

        mAppsRepository = AppsRepository.getAppsRepository();
    }

    public static AppProvider getInstance(Context context){
        if(sInstance == null){
            sInstance = new AppProvider(context);
            sInstance.reload();
        }
        return sInstance;
    }

    public Context getContext() {
        return mContext;
    }

    public void reload() {
        initializeAppLoading(new LoadAppsTask());
        if (Utilities.ATLEAST_OREO) {
            initializeShortcutsLoading(new LoadShortcutTask());
        } else {
            shortcutsLoaded = true; // will be loaded from database automatically.
        }
        initializeDatabaseLoading(new LoadDatabaseTask());
    }

    private void initializeAppLoading(LoadAppsTask loader) {
        appsLoaded = false;
        loader.setAppProvider(this);
        loader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void initializeShortcutsLoading(LoadShortcutTask loader) {
        shortcutsLoaded = false;
        loader.setAppProvider(this);
        loader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void initializeDatabaseLoading(LoadDatabaseTask loader) {
        databaseLoaded = false;
        loader.setAppProvider(this);
        loader.executeOnExecutor(AppExecutors.getInstance().diskIO());
    }

    public void loadAppsOver(Map<String, ApplicationItem> appItemsPair) {
        mApplicationItems = appItemsPair;
        appsLoaded = true;
        handleAllProviderLoaded();
    }

    public void loadShortcutsOver(Map<String, ShortcutInfoCompat> shortcuts) {
        mShortcutInfoCompats = shortcuts;
        shortcutsLoaded = true;
        handleAllProviderLoaded();
    }

    public void loadDatabaseOver(List<LauncherItem> databaseItems) {
        this.mDatabaseItems = databaseItems;
        databaseLoaded = true;
        handleAllProviderLoaded();
    }

    private synchronized void handleAllProviderLoaded() {
        if (appsLoaded && shortcutsLoaded && databaseLoaded) {
            if (mDatabaseItems == null || mDatabaseItems.size() <= 0) {
                prepareDefaultLauncherItems();
            } else {
                prepareLauncherItems();
            }
            mAppsRepository.updateAppsRelay(mLauncherItems);
        }
    }

    private void prepareLauncherItems() {

        // Stores networkItems that user put in any folder.
        LongSparseArray<List<LauncherItem>> folderItems = new LongSparseArray<>();

        /**
         * Indices of folder in {@link #mLauncherItems}.
         */
        LongSparseArray<Integer> foldersIndex = new LongSparseArray<>();

        mLauncherItems = new ArrayList<>();
        Collection<ApplicationItem> applicationItems = mApplicationItems.values();
        for (LauncherItem databaseItem : mDatabaseItems) {
            if (databaseItem.itemType == Constants.ITEM_TYPE_APPLICATION) {
                ApplicationItem applicationItem = mApplicationItems.get(databaseItem.id);
                if (applicationItem == null) {
                    DatabaseManager.getManager(mContext).removeLauncherItem(databaseItem.id);
                    continue;
                }
                applicationItem.container = databaseItem.container;
                applicationItem.screenId = databaseItem.screenId;
                applicationItem.cell = databaseItem.cell;
                applicationItem.keyId = databaseItem.keyId;
                if (applicationItem.container == Constants.CONTAINER_DESKTOP
                        || applicationItem.container == Constants.CONTAINER_HOTSEAT) {
                    mLauncherItems.add(applicationItem);
                } else {
                    FolderItem folderItem =
                            (FolderItem) mLauncherItems.get(foldersIndex.get(applicationItem.container));
                    if (folderItem.items == null) {
                        folderItem.items = new ArrayList<>();
                    }
                    folderItem.items.add(applicationItem);
                }
            } else if (databaseItem.itemType == Constants.ITEM_TYPE_SHORTCUT) {
                ShortcutItem shortcutItem;
                if (Utilities.ATLEAST_OREO) {
                    shortcutItem = prepareShortcutForOreo(databaseItem);
                } else {
                    shortcutItem = prepareShortcutForNougat(databaseItem);
                }

                if (shortcutItem == null) {
                    DatabaseManager.getManager(mContext).removeLauncherItem(databaseItem.id);
                    continue;
                }

                if (shortcutItem.container == Constants.CONTAINER_DESKTOP
                        || shortcutItem.container == Constants.CONTAINER_HOTSEAT) {
                    mLauncherItems.add(shortcutItem);
                } else {
                    FolderItem folderItem =
                            (FolderItem) mLauncherItems.get(foldersIndex.get(shortcutItem.container));
                    if (folderItem.items == null) {
                        folderItem.items = new ArrayList<>();
                    }
                    folderItem.items.add(shortcutItem);
                }
            } else if (databaseItem.itemType == Constants.ITEM_TYPE_FOLDER) {
                FolderItem folderItem = new FolderItem();
                folderItem.id = databaseItem.id;
                folderItem.title = databaseItem.title;
                folderItem.container = databaseItem.container;
                folderItem.cell = databaseItem.cell;
                folderItem.screenId = databaseItem.screenId;
                foldersIndex.put(Long.parseLong(folderItem.id), mLauncherItems.size());
                mLauncherItems.add(folderItem);
            }
        }

        if (foldersIndex.size() > 0) {
            for (int i = 0; i < foldersIndex.size(); i++) {
                FolderItem folderItem =
                        (FolderItem) mLauncherItems.get(foldersIndex.get(foldersIndex.keyAt(i)));
                if(folderItem.items == null){
                    DatabaseManager.getManager(mContext).removeLauncherItem(folderItem.id);
                    mLauncherItems.remove(foldersIndex.get(foldersIndex.keyAt(i)));
                }else {
                    folderItem.icon = new GraphicsUtil(mContext).generateFolderIcon(mContext, folderItem);
                }
            }
        }

        applicationItems.removeAll(mDatabaseItems);
        mLauncherItems.addAll(applicationItems);
    }

    private ShortcutItem prepareShortcutForNougat(LauncherItem databaseItem) {
        ShortcutItem shortcutItem = new ShortcutItem();
        shortcutItem.id = databaseItem.id;
        shortcutItem.packageName = databaseItem.packageName;
        shortcutItem.title = databaseItem.title.toString();
        shortcutItem.icon_blob = databaseItem.icon_blob;
        Bitmap bitmap = BitmapFactory.decodeByteArray(databaseItem.icon_blob, 0,
                databaseItem.icon_blob.length);
        shortcutItem.icon = new BitmapDrawable(mContext.getResources(), bitmap);
        shortcutItem.launchIntent = databaseItem.getIntent();
        shortcutItem.launchIntentUri = databaseItem.launchIntentUri;
        shortcutItem.container = databaseItem.container;
        shortcutItem.screenId = databaseItem.screenId;
        shortcutItem.cell = databaseItem.cell;
        return shortcutItem;
    }

    private ShortcutItem prepareShortcutForOreo(LauncherItem databaseItem) {
        ShortcutInfoCompat info = mShortcutInfoCompats.get(databaseItem.id);
        if (info == null) {
            Log.d(TAG, "prepareShortcutForOreo() called with: databaseItem = [" + databaseItem + "]");
            return null;
        }

        ShortcutItem shortcutItem = new ShortcutItem();
        shortcutItem.id = info.getId();
        shortcutItem.packageName = info.getPackage();
        shortcutItem.title = info.getShortLabel().toString();
        Drawable icon = DeepShortcutManager.getInstance(mContext).getShortcutIconDrawable(info,
                mContext.getResources().getDisplayMetrics().densityDpi);
        shortcutItem.icon = BlissLauncher.getApplication(
                mContext).getIconsHandler().convertIcon(icon);
        shortcutItem.launchIntent = info.makeIntent();
        shortcutItem.container = databaseItem.container;
        shortcutItem.screenId = databaseItem.screenId;
        shortcutItem.cell = databaseItem.cell;
        return shortcutItem;
    }

    private void prepareDefaultLauncherItems() {
        mLauncherItems = new ArrayList<>();
        List<LauncherItem> pinnedItems = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("http:")),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        };
        for (int i = 0; i < intents.length; i++) {
            String packageName = AppUtils.getPackageNameForIntent(intents[i], pm);
            LauncherApps launcherApps = (LauncherApps) mContext.getSystemService(
                    Context.LAUNCHER_APPS_SERVICE);
            List<LauncherActivityInfo> list = launcherApps.getActivityList(packageName,
                    Process.myUserHandle());
            ApplicationItem applicationItem = mApplicationItems.get(list.get(
                    0).getComponentName().flattenToString());
            applicationItem.container = Constants.CONTAINER_HOTSEAT;
            applicationItem.cell = i;
            pinnedItems.add(applicationItem);
        }

        for (Map.Entry<String, ApplicationItem> stringApplicationItemEntry : mApplicationItems
                .entrySet()) {
            if (!pinnedItems.contains(stringApplicationItemEntry.getValue())) {
                mLauncherItems.add(stringApplicationItemEntry.getValue());
            }
        }

        Collections.sort(mLauncherItems, (app1, app2) -> {
            Collator collator = Collator.getInstance();
            return collator.compare(app1.title.toString(), app2.title.toString());
        });

        mLauncherItems.addAll(pinnedItems);
    }
}
