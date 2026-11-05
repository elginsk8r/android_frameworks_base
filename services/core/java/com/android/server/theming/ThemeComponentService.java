/*
 * Copyright (C) 2014-2016 The CyanogenMod Project
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.theming;

import static android.content.pm.ThemeUtils.MODIFIES_ICONS;
import static android.content.pm.ThemeUtils.MODIFIES_LAUNCHER;
import static android.content.pm.ThemeUtils.MODIFIES_LOCKSCREEN;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ThemeUtils;
import android.content.res.IThemeChangeListener;
import android.content.res.IThemeService;
import android.content.res.Resources;
import android.content.res.ThemeChangeRequest;
import android.content.res.ThemeConfig;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.om.OverlayManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Companion to {@link ThemeManagerService} that applies theme packages, the successor of the
 * cm-13.0 ThemeService.
 *
 * <p>Everything is delegated to existing platform mechanisms where one exists:
 * <ul>
 *     <li>App icon packs are turned into one fabricated overlay per app replacing its icon, so
 *     the regular resource stack and launchers pick them up.</li>
 *     <li>Wallpapers go through the {@link WallpaperManager}.</li>
 * </ul>
 */
public final class ThemeComponentService extends SystemService {
    private static final String TAG = "ThemeComponentService";
    private static final boolean DEBUG = false;

    private static final String ANDROID_PACKAGE = "android";

    // Fabricated icon overlays are owned by the system and named per user and target, as
    // fabricated overlays are registered for all users.
    private static final String ICON_OVERLAY_OWNER = ANDROID_PACKAGE;
    private static final String ICON_OVERLAY_PREFIX = "theme_icons_u";
    // Icons are rendered for xxxhdpi and scaled down by the resource system as needed.
    private static final String ICON_OVERLAY_CONFIG = "xxxhdpi";
    private static final int ICON_DENSITY = DisplayMetrics.DENSITY_XXXHIGH;
    private static final int ICON_SIZE_DP = 48;

    private static final File THEME_DIR = new File(Environment.getDataSystemDirectory(), "theme");
    private static final String STATE_FILE = "theme_components.json";

    // Give the overlay manager time to pick up overlay packages before reacting to them.
    private static final long PACKAGE_CHANGE_DELAY_MS = 2000;

    private final Context mContext;
    private final Handler mHandler;
    private OverlayManagerInternal mOverlayManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final RemoteCallbackList<IThemeChangeListener> mClients = new RemoteCallbackList<>();
    private volatile boolean mIsThemeApplying;
    private volatile int mProgress;

    public ThemeComponentService(@NonNull Context context) {
        super(context);
        mContext = context;
        HandlerThread worker = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        worker.start();
        mHandler = new Handler(worker.getLooper());
    }

    @Override
    public void onStart() {
        mOverlayManager = LocalServices.getService(OverlayManagerInternal.class);
        publishBinderService(Context.THEME_COMPONENT_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiverForAllUsers(mPackageReceiver, filter, null, mHandler);

            mContext.registerReceiverForAllUsers(mUserRemovedReceiver,
                    new IntentFilter(Intent.ACTION_USER_REMOVED), null, mHandler);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Theme application

    private void doApplyTheme(@UserIdInt int userId, @NonNull ThemeChangeRequest request) {
        mProgress = 0;
        if (request.getNumChangesRequested() == 0) {
            postFinish(userId, true, request, 0);
            return;
        }
        mIsThemeApplying = true;
        incrementProgress(5);

        final UserState state = getUserState(userId);
        final UserState newState = state.copy();
        final Map<String, String> requested = request.getThemeComponentsMap();
        newState.components.putAll(requested);

        final int progressIncrement = 90 / Math.max(1, request.getNumChangesRequested());
        boolean success = true;
        // Components that could not be applied keep the theme they had
        final Set<String> failed = new ArraySet<>();

        if (requested.containsKey(MODIFIES_ICONS)) {
            if (!applyIconPack(userId, requested.get(MODIFIES_ICONS), null)) {
                failed.add(MODIFIES_ICONS);
            }
            incrementProgress(progressIncrement);
        }

        if (requested.containsKey(MODIFIES_LAUNCHER)) {
            if (!updateWallpaper(userId, requested.get(MODIFIES_LAUNCHER),
                    WallpaperManager.FLAG_SYSTEM, ThemeUtils.WALLPAPER_PATH,
                    request.getWallpaperId())) {
                failed.add(MODIFIES_LAUNCHER);
            }
            incrementProgress(progressIncrement);
        }

        if (requested.containsKey(MODIFIES_LOCKSCREEN)) {
            if (!updateWallpaper(userId, requested.get(MODIFIES_LOCKSCREEN),
                    WallpaperManager.FLAG_LOCK, ThemeUtils.LOCKSCREEN_WALLPAPER_PATH,
                    ThemeChangeRequest.DEFAULT_WALLPAPER_ID)) {
                failed.add(MODIFIES_LOCKSCREEN);
            }
            incrementProgress(progressIncrement);
        }

        for (String component : failed) {
            newState.restore(state, component);
        }
        for (String component : requested.keySet()) {
            if (!failed.contains(component)) {
                newState.setMarker(component, getComponentMarker(userId, component));
            }
        }
        success &= failed.isEmpty();

        newState.lastChangeTime = System.currentTimeMillis();
        newState.lastRequestType = request.getRequestType().ordinal();
        synchronized (mLock) {
            mUserStates.put(userId, newState);
            newState.write(userId);
        }

        postFinish(userId, success, request, newState.lastChangeTime);
        mIsThemeApplying = false;
    }

    private void doApplyDefaultTheme(@UserIdInt int userId) {
        final Resources res = mContext.getResources();
        final String defaultTheme = res.getString(R.string.config_defaultThemePackage);
        final String themePkg = !TextUtils.isEmpty(defaultTheme)
                && getApplicationInfo(userId, defaultTheme) != null
                ? defaultTheme : ThemeConfig.SYSTEM_DEFAULT;
        List<String> components = Arrays.asList(
                res.getStringArray(R.array.config_defaultThemeComponents));
        if (components.isEmpty() || ThemeConfig.SYSTEM_DEFAULT.equals(themePkg)) {
            components = ThemeUtils.getAllComponents();
        }

        final ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder()
                .setRequestType(ThemeChangeRequest.RequestType.THEME_RESET);
        for (String component : components) {
            builder.setComponent(component, themePkg);
        }
        doApplyTheme(userId, builder.build());
    }

    /**
     * Replaces app icons with those of an icon pack by registering one fabricated overlay per
     * app that overrides its icon resources.
     *
     * @param iconPkg the icon pack, or the system default to remove themed icons
     * @param onlyPackages limit the update to these apps, or null to update all apps
     */
    private boolean applyIconPack(@UserIdInt int userId, @Nullable String iconPkg,
            @Nullable Set<String> onlyPackages) {
        final String prefix = ICON_OVERLAY_PREFIX + userId + "_";
        final Map<String, OverlayIdentifier> existing = new ArrayMap<>();
        try {
            for (List<OverlayInfo> overlays :
                    mOverlayManager.getService().getAllOverlays(userId).values()) {
                for (OverlayInfo overlay : overlays) {
                    if (overlay.isFabricated()
                            && ICON_OVERLAY_OWNER.equals(overlay.packageName)
                            && overlay.getOverlayName() != null
                            && overlay.getOverlayName().startsWith(prefix)
                            && (onlyPackages == null
                                    || onlyPackages.contains(overlay.targetPackageName))) {
                        existing.put(overlay.targetPackageName, overlay.getOverlayIdentifier());
                    }
                }
            }
        } catch (RemoteException e) {
            // Local call, cannot happen
            return false;
        }

        final OverlayManagerTransaction.Builder transaction =
                new OverlayManagerTransaction.Builder();
        final List<ParcelFileDescriptor> fds = new ArrayList<>();
        final File iconDir = new File(THEME_DIR, "icons_u" + userId);
        boolean success = true;

        try {
            if (!ThemeUtils.isSystemDefault(iconPkg)) {
                final Context userContext = getContextForUser(userId);
                final IconPackHelper helper;
                try {
                    helper = new IconPackHelper(userContext, iconPkg);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Icon pack " + iconPkg + " not found");
                    return false;
                }

                iconDir.mkdirs();
                FileUtils.deleteContents(iconDir);
                for (Map.Entry<String, List<ResolveInfo>> entry :
                        getLauncherActivities(userContext, onlyPackages).entrySet()) {
                    final String target = entry.getKey();
                    final FabricatedOverlay overlay = createIconOverlay(userContext, helper,
                            prefix, target, entry.getValue(), iconDir, fds);
                    if (overlay != null) {
                        transaction.registerFabricatedOverlay(overlay);
                        transaction.setEnabled(overlay.getIdentifier(), true, userId);
                        existing.remove(target);
                    }
                }
            }

            for (OverlayIdentifier stale : existing.values()) {
                transaction.unregisterFabricatedOverlay(stale);
            }
            success = commit(transaction);
        } finally {
            for (ParcelFileDescriptor fd : fds) {
                try {
                    fd.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            FileUtils.deleteContents(iconDir);
        }
        return success;
    }

    @Nullable
    private FabricatedOverlay createIconOverlay(@NonNull Context userContext,
            @NonNull IconPackHelper helper, @NonNull String prefix, @NonNull String target,
            @NonNull List<ResolveInfo> activities, @NonNull File iconDir,
            @NonNull List<ParcelFileDescriptor> fds) {
        final PackageManager pm = userContext.getPackageManager();
        final Resources targetRes;
        try {
            targetRes = pm.getResourcesForApplication(
                    activities.get(0).activityInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        final int size = ICON_SIZE_DP * ICON_DENSITY / DisplayMetrics.DENSITY_DEFAULT;
        final Set<Integer> themed = new ArraySet<>();
        FabricatedOverlay overlay = null;
        for (ResolveInfo ri : activities) {
            final int iconRes = ri.activityInfo.getIconResource();
            if (iconRes == 0 || themed.contains(iconRes)) {
                continue;
            }
            final String resName;
            final Drawable original;
            try {
                // Only resources of the app itself can be overlaid per app; apps using the
                // framework's default icon keep it.
                if (!target.equals(targetRes.getResourcePackageName(iconRes))) {
                    continue;
                }
                resName = targetRes.getResourceName(iconRes);
                original = targetRes.getDrawableForDensity(iconRes, ICON_DENSITY, null);
            } catch (Resources.NotFoundException e) {
                continue;
            }

            final ComponentName activity = new ComponentName(target, ri.activityInfo.name);
            final Bitmap icon = helper.createIcon(activity, original, size, ICON_DENSITY);
            if (icon == null) {
                continue;
            }
            final File file = new File(iconDir, target + "_" + iconRes + ".png");
            try (OutputStream out = new FileOutputStream(file)) {
                icon.compress(Bitmap.CompressFormat.PNG, 100, out);
                final ParcelFileDescriptor fd =
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                fds.add(fd);
                if (overlay == null) {
                    overlay = new FabricatedOverlay.Builder(ICON_OVERLAY_OWNER,
                            prefix + target.replaceAll("[^A-Za-z0-9_]", "_"), target).build();
                }
                overlay.setResourceValue(resName, fd, ICON_OVERLAY_CONFIG);
                themed.add(iconRes);
            } catch (IOException e) {
                Slog.w(TAG, "Unable to write themed icon for " + activity, e);
            } finally {
                icon.recycle();
            }
        }
        return overlay;
    }

    /** @return launcher activities grouped by package. */
    @NonNull
    private static Map<String, List<ResolveInfo>> getLauncherActivities(
            @NonNull Context userContext, @Nullable Set<String> onlyPackages) {
        final Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, List<ResolveInfo>> activities = new ArrayMap<>();
        if (onlyPackages != null && onlyPackages.size() == 1) {
            intent.setPackage(onlyPackages.iterator().next());
        }
        for (ResolveInfo ri : userContext.getPackageManager().queryIntentActivities(intent,
                PackageManager.ResolveInfoFlags.of(0))) {
            final String pkg = ri.activityInfo.packageName;
            if (ANDROID_PACKAGE.equals(pkg)
                    || (onlyPackages != null && !onlyPackages.contains(pkg))) {
                continue;
            }
            activities.computeIfAbsent(pkg, k -> new ArrayList<>()).add(ri);
        }
        return activities;
    }

    private boolean commit(@NonNull OverlayManagerTransaction.Builder transaction) {
        try {
            mOverlayManager.commit(transaction.build());
            return true;
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            Slog.e(TAG, "Unable to commit overlay transaction", e);
            return false;
        }
    }

    private boolean updateWallpaper(@UserIdInt int userId, @Nullable String pkgName, int which,
            @NonNull String assetDir, long wallpaperId) {
        final WallpaperManager wm = getContextForUser(userId)
                .getSystemService(WallpaperManager.class);
        try {
            if (ThemeUtils.isSystemDefault(pkgName)) {
                wm.clear(which);
                return true;
            }
            final Context themeContext = getThemeContext(userId, pkgName);
            final String[] assets = themeContext.getAssets().list(assetDir);
            if (assets == null || assets.length == 0) {
                Slog.w(TAG, pkgName + " has no " + assetDir);
                return false;
            }
            Arrays.sort(assets);
            final int index = wallpaperId >= 0 && wallpaperId < assets.length
                    ? (int) wallpaperId : 0;
            try (InputStream in = themeContext.getAssets().open(
                    assetDir + File.separator + assets[index])) {
                wm.setStream(in, null, true, which);
            }
            return true;
        } catch (IOException | PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Unable to set wallpaper from " + pkgName, e);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Package and user changes

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Uri data = intent.getData();
            final String pkgName = data != null ? data.getSchemeSpecificPart() : null;
            final int userId = getSendingUserId();
            if (pkgName == null || userId == UserHandle.USER_ALL) {
                return;
            }
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                if (!replacing) {
                    onPackageRemoved(userId, pkgName);
                }
            } else {
                mHandler.postDelayed(() -> onPackageAdded(userId, pkgName),
                        PACKAGE_CHANGE_DELAY_MS);
            }
        }
    };

    private final BroadcastReceiver mUserRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                return;
            }
            // Fabricated overlays outlive users, remove this user's icons
            applyIconPack(userId, ThemeConfig.SYSTEM_DEFAULT, null);
            synchronized (mLock) {
                mUserStates.remove(userId);
            }
        }
    };

    private void onPackageAdded(@UserIdInt int userId, @NonNull String pkgName) {
        final UserState state = getUserState(userId);
        final ApplicationInfo ai = getApplicationInfo(userId, pkgName);
        if (ai == null) {
            return;
        }

        final String iconPack = state.components.get(MODIFIES_ICONS);
        final boolean iconPackActive = !ThemeUtils.isSystemDefault(iconPack);
        if (pkgName.equals(iconPack)) {
            // The applied icon pack was updated
            applyIconPack(userId, iconPack, null);
            sendThemeChangedBroadcast(userId, Collections.singletonList(MODIFIES_ICONS),
                    ThemeChangeRequest.RequestType.THEME_UPDATED, System.currentTimeMillis());
            return;
        }
        if (iconPackActive) {
            applyIconPack(userId, iconPack, Collections.singleton(pkgName));
        }
    }

    private void onPackageRemoved(@UserIdInt int userId, @NonNull String pkgName) {
        final UserState state = getUserState(userId);
        final ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder()
                .setRequestType(ThemeChangeRequest.RequestType.THEME_REMOVED);
        for (Map.Entry<String, String> entry : state.components.entrySet()) {
            if (pkgName.equals(entry.getValue())) {
                builder.setComponent(entry.getKey(), ThemeConfig.SYSTEM_DEFAULT);
            }
        }
        final ThemeChangeRequest request = builder.build();
        if (request.getNumChangesRequested() > 0) {
            mHandler.post(() -> doApplyTheme(userId, request));
        } else if (!ThemeUtils.isSystemDefault(state.components.get(MODIFIES_ICONS))) {
            // Drop the icon overlay of the removed app
            mHandler.post(() -> applyIconPack(userId, ThemeConfig.SYSTEM_DEFAULT,
                    Collections.singleton(pkgName)));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    @NonNull
    private Context getContextForUser(@UserIdInt int userId) {
        return mContext.createContextAsUser(UserHandle.of(userId), 0);
    }

    @NonNull
    private PackageManager getPackageManagerForUser(@UserIdInt int userId) {
        return getContextForUser(userId).getPackageManager();
    }

    @NonNull
    private Context getThemeContext(@UserIdInt int userId, @NonNull String pkgName)
            throws PackageManager.NameNotFoundException {
        return mContext.createPackageContextAsUser(pkgName, 0, UserHandle.of(userId));
    }

    @Nullable
    private ApplicationInfo getApplicationInfo(@UserIdInt int userId, @NonNull String pkgName) {
        try {
            return getPackageManagerForUser(userId).getApplicationInfo(pkgName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @NonNull
    private UserState getUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            UserState state = mUserStates.get(userId);
            if (state == null) {
                state = UserState.read(userId);
                mUserStates.put(userId, state);
            }
            return state;
        }
    }

    /**
     * @return what tells whether a component the service applied is still in place, or null if
     *         that is checked otherwise, see {@link #isComponentApplied}
     */
    @Nullable
    private String getComponentMarker(@UserIdInt int userId, @NonNull String component) {
        switch (component) {
            case MODIFIES_LAUNCHER:
                return getWallpaperMarker(userId, WallpaperManager.FLAG_SYSTEM);
            case MODIFIES_LOCKSCREEN:
                return getWallpaperMarker(userId, WallpaperManager.FLAG_LOCK);
            default:
                return null;
        }
    }

    @NonNull
    private String getWallpaperMarker(@UserIdInt int userId, int which) {
        return String.valueOf(getContextForUser(userId).getSystemService(WallpaperManager.class)
                .getWallpaperIdForUser(which, userId));
    }

    /**
     * @return whether a component the service applied for a user is still in place rather than
     *         changed elsewhere since
     */
    private boolean isComponentApplied(@UserIdInt int userId, @NonNull UserState state,
            @NonNull String component) {
        switch (component) {
            case MODIFIES_ICONS:
                return areIconOverlaysEnabled(userId);
            case MODIFIES_LAUNCHER:
            case MODIFIES_LOCKSCREEN:
                return Objects.equals(state.markers.get(component),
                        getComponentMarker(userId, component));
            default:
                return true;
        }
    }

    /** @return whether the icon overlays of a user are all still enabled. */
    private boolean areIconOverlaysEnabled(@UserIdInt int userId) {
        final String prefix = ICON_OVERLAY_PREFIX + userId + "_";
        try {
            for (List<OverlayInfo> overlays :
                    mOverlayManager.getService().getAllOverlays(userId).values()) {
                for (OverlayInfo overlay : overlays) {
                    if (overlay.isFabricated()
                            && ICON_OVERLAY_OWNER.equals(overlay.packageName)
                            && overlay.getOverlayName() != null
                            && overlay.getOverlayName().startsWith(prefix)
                            && !overlay.isEnabled()) {
                        return false;
                    }
                }
            }
        } catch (RemoteException e) {
            // Local call, cannot happen
        }
        return true;
    }

    private void incrementProgress(int increment) {
        mProgress = Math.min(100, mProgress + increment);
        final int progress = mProgress;
        final int n = mClients.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mClients.getBroadcastItem(i).onProgress(progress);
            } catch (RemoteException e) {
                // The callback list takes care of dead clients
            }
        }
        mClients.finishBroadcast();
    }

    private void postFinish(@UserIdInt int userId, boolean isSuccess,
            @NonNull ThemeChangeRequest request, long updateTime) {
        mProgress = 0;
        final int n = mClients.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mClients.getBroadcastItem(i).onFinish(isSuccess);
            } catch (RemoteException e) {
                // The callback list takes care of dead clients
            }
        }
        mClients.finishBroadcast();

        final List<String> components = new ArrayList<>(request.getThemeComponentsMap().keySet());
        if (!components.isEmpty()) {
            sendThemeChangedBroadcast(userId, components, request.getRequestType(), updateTime);
        }
    }

    private void sendThemeChangedBroadcast(@UserIdInt int userId, @NonNull List<String> components,
            @NonNull ThemeChangeRequest.RequestType requestType, long updateTime) {
        final Intent intent = new Intent(ThemeUtils.ACTION_THEME_CHANGED);
        intent.putStringArrayListExtra(ThemeUtils.EXTRA_COMPONENTS, new ArrayList<>(components));
        intent.putExtra(ThemeUtils.EXTRA_REQUEST_TYPE, requestType.ordinal());
        intent.putExtra(ThemeUtils.EXTRA_UPDATE_TIME, updateTime);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
    }

    // ------------------------------------------------------------------------------------------
    // Binder interface

    private final IThemeService.Stub mService = new IThemeService.Stub() {
        @Override
        public void requestThemeChangeUpdates(IThemeChangeListener listener) {
            enforceModifyPermission();
            mClients.register(listener);
        }

        @Override
        public void removeUpdates(IThemeChangeListener listener) {
            enforceModifyPermission();
            mClients.unregister(listener);
        }

        @Override
        public void requestThemeChange(ThemeChangeRequest request, int userId) {
            enforceModifyPermission();
            final int resolvedUserId = handleIncomingUser(userId, "requestThemeChange");
            mHandler.post(() -> doApplyTheme(resolvedUserId, request));
        }

        @Override
        public void applyDefaultTheme(int userId) {
            enforceModifyPermission();
            final int resolvedUserId = handleIncomingUser(userId, "applyDefaultTheme");
            mHandler.post(() -> doApplyDefaultTheme(resolvedUserId));
        }

        @Override
        public boolean isThemeApplying() {
            return mIsThemeApplying;
        }

        @Override
        public int getProgress() {
            return mProgress;
        }

        @Override
        public ThemeConfig getThemeConfig(int userId) {
            final int resolvedUserId = handleIncomingUser(userId, "getThemeConfig");
            final long token = Binder.clearCallingIdentity();
            try {
                final UserState state = getUserState(resolvedUserId);
                return state.toThemeConfig(component -> isComponentApplied(resolvedUserId, state,
                        component));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Map getAppliedComponents(int userId) {
            final int resolvedUserId = handleIncomingUser(userId, "getAppliedComponents");
            final long token = Binder.clearCallingIdentity();
            try {
                final UserState state = getUserState(resolvedUserId);
                final Map<String, String> components = new ArrayMap<>();
                for (Map.Entry<String, String> entry : state.components.entrySet()) {
                    if (!ThemeUtils.isSystemDefault(entry.getValue()) && isComponentApplied(
                            resolvedUserId, state, entry.getKey())) {
                        components.put(entry.getKey(), entry.getValue());
                    }
                }
                return components;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public long getLastThemeChangeTime(int userId) {
            return getUserState(handleIncomingUser(userId, "getLastThemeChangeTime"))
                    .lastChangeTime;
        }

        @Override
        public int getLastThemeChangeRequestType(int userId) {
            return getUserState(handleIncomingUser(userId, "getLastThemeChangeRequestType"))
                    .lastRequestType;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
                return;
            }
            pw.println("ThemeComponentService:");
            pw.println("  applying=" + mIsThemeApplying + " progress=" + mProgress);
            synchronized (mLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    final UserState state = mUserStates.valueAt(i);
                    pw.println("  user " + mUserStates.keyAt(i) + ":");
                    pw.println("    components=" + state.components);
                    pw.println("    markers=" + state.markers);
                    pw.println("    lastChangeTime=" + state.lastChangeTime
                            + " lastRequestType=" + state.lastRequestType);
                }
            }
        }

        private void enforceModifyPermission() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CHANGE_OVERLAY_PACKAGES,
                    TAG);
        }

        private int handleIncomingUser(int userId, String name) {
            return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false /* allowAll */,
                    true /* requireFull */, name, null);
        }
    };

    // ------------------------------------------------------------------------------------------
    // Persistent per user state

    private static final class UserState {
        private static final String KEY_COMPONENTS = "components";
        private static final String KEY_MARKERS = "markers";
        private static final String KEY_LAST_CHANGE_TIME = "lastChangeTime";
        private static final String KEY_LAST_REQUEST_TYPE = "lastRequestType";

        // Component -> theme package; ThemeConfig.SYSTEM_DEFAULT for an explicit reset
        final Map<String, String> components = new ArrayMap<>();
        // Component -> what tells whether the component the service applied is still in place
        final Map<String, String> markers = new ArrayMap<>();
        long lastChangeTime;
        int lastRequestType;

        UserState copy() {
            final UserState copy = new UserState();
            copy.components.putAll(components);
            copy.markers.putAll(markers);
            copy.lastChangeTime = lastChangeTime;
            copy.lastRequestType = lastRequestType;
            return copy;
        }

        void setMarker(@NonNull String component, @Nullable String marker) {
            if (marker != null) {
                markers.put(component, marker);
            } else {
                markers.remove(component);
            }
        }

        /** Restores the theme a component had in {@code previous}. */
        void restore(@NonNull UserState previous, @NonNull String component) {
            if (previous.components.containsKey(component)) {
                components.put(component, previous.components.get(component));
            } else {
                components.remove(component);
            }
            setMarker(component, previous.markers.get(component));
        }

        /**
         * @param isApplied tells whether a component is still in place
         */
        ThemeConfig toThemeConfig(@NonNull Predicate<String> isApplied) {
            return new ThemeConfig.Builder()
                    .defaultIcon(getApplied(MODIFIES_ICONS, isApplied))
                    .build();
        }

        @Nullable
        private String getApplied(@NonNull String component,
                @NonNull Predicate<String> isApplied) {
            return isApplied.test(component) ? components.get(component) : null;
        }

        private static AtomicFile getFile(@UserIdInt int userId) {
            return new AtomicFile(new File(Environment.getDataSystemDeDirectory(userId),
                    STATE_FILE));
        }

        static UserState read(@UserIdInt int userId) {
            final UserState state = new UserState();
            final AtomicFile file = getFile(userId);
            if (!file.exists()) {
                return state;
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(file.openRead(),
                    StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case KEY_COMPONENTS:
                            readMap(reader, state.components);
                            break;
                        case KEY_MARKERS:
                            readMap(reader, state.markers);
                            break;
                        case KEY_LAST_CHANGE_TIME:
                            state.lastChangeTime = reader.nextLong();
                            break;
                        case KEY_LAST_REQUEST_TYPE:
                            state.lastRequestType = reader.nextInt();
                            break;
                        default:
                            reader.skipValue();
                            break;
                    }
                }
                reader.endObject();
            } catch (IOException | IllegalStateException e) {
                Slog.e(TAG, "Unable to read theme state of user " + userId, e);
            }
            return state;
        }

        void write(@UserIdInt int userId) {
            final AtomicFile file = getFile(userId);
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                final JsonWriter writer = new JsonWriter(
                        new OutputStreamWriter(out, StandardCharsets.UTF_8));
                writer.beginObject();
                writer.name(KEY_COMPONENTS);
                writeMap(writer, components);
                writer.name(KEY_MARKERS);
                writeMap(writer, markers);
                writer.name(KEY_LAST_CHANGE_TIME).value(lastChangeTime);
                writer.name(KEY_LAST_REQUEST_TYPE).value(lastRequestType);
                writer.endObject();
                writer.flush();
                file.finishWrite(out);
            } catch (IOException e) {
                Slog.e(TAG, "Unable to write theme state of user " + userId, e);
                file.failWrite(out);
            }
        }

        private static void readMap(JsonReader reader, Map<String, String> map)
                throws IOException {
            reader.beginObject();
            while (reader.hasNext()) {
                map.put(reader.nextName(), reader.nextString());
            }
            reader.endObject();
        }

        private static void writeMap(JsonWriter writer, Map<String, String> map)
                throws IOException {
            writer.beginObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writer.name(entry.getKey()).value(entry.getValue());
            }
            writer.endObject();
        }
    }

}
