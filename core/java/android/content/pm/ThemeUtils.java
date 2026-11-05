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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.res.ThemeConfig;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Constants and helpers shared by the theme component service and its clients.
 *
 * <p>A theme is made up of a <em>theme package</em> plus any number of runtime resource overlay
 * (RRO) packages. The theme package identifies itself with
 * {@code <meta-data android:name="org.lineageos.theme.name" android:value="..."/>} (the legacy
 * {@code org.cyanogenmod.theme.name} tag is also accepted) and carries non-overlay components as
 * assets:
 * <ul>
 *     <li>{@code assets/wallpapers/*} - home screen wallpaper(s)</li>
 *     <li>{@code assets/lockscreen/*} - lock screen wallpaper</li>
 *     <li>{@code assets/ringtones/*}, {@code assets/notifications/*}, {@code assets/alarms/*}</li>
 *     <li>{@code assets/bootanimation/bootanimation.zip}</li>
 *     <li>{@code assets/appfilter.xml} or {@code res/xml/appfilter.xml} - icon pack</li>
 * </ul>
 * An RRO belongs to a theme when it declares
 * {@code <meta-data android:name="org.lineageos.theme.parent" android:value="<theme package>"/>}
 * or when its package name is {@code <theme package>.<anything>}.
 *
 * @hide
 */
public final class ThemeUtils {
    private ThemeUtils() {}

    /** Meta-data tag identifying a theme package and holding its display name. */
    public static final String META_TAG_NAME = "org.lineageos.theme.name";
    /** Meta-data tag holding the theme author. */
    public static final String META_TAG_AUTHOR = "org.lineageos.theme.author";
    /** Meta-data tag linking an overlay package to its theme package. */
    public static final String META_TAG_PARENT = "org.lineageos.theme.parent";
    /** Legacy CyanogenMod theme meta-data tags. */
    public static final String LEGACY_META_TAG_NAME = "org.cyanogenmod.theme.name";
    public static final String LEGACY_META_TAG_AUTHOR = "org.cyanogenmod.theme.author";

    /** Overlay category for SystemUI overlays that only theme the navigation bar. */
    public static final String OVERLAY_CATEGORY_NAVBAR = "org.lineageos.theme.navbar";

    // Theme components. The values match the cm-13.0 ThemesContract so that
    // existing theme choosers can keep using them.
    public static final String MODIFIES_LAUNCHER = "mods_homescreen";
    public static final String MODIFIES_LOCKSCREEN = "mods_lockscreen";
    public static final String MODIFIES_ICONS = "mods_icons";
    public static final String MODIFIES_BOOT_ANIM = "mods_bootanim";
    public static final String MODIFIES_NOTIFICATIONS = "mods_notifications";
    public static final String MODIFIES_ALARMS = "mods_alarms";
    public static final String MODIFIES_RINGTONES = "mods_ringtones";
    public static final String MODIFIES_OVERLAYS = "mods_overlays";
    public static final String MODIFIES_STATUS_BAR = "mods_status_bar";
    public static final String MODIFIES_NAVIGATION_BAR = "mods_navigation_bar";

    private static final List<String> ALL_COMPONENTS = Collections.unmodifiableList(Arrays.asList(
            MODIFIES_LAUNCHER, MODIFIES_LOCKSCREEN, MODIFIES_ICONS, MODIFIES_BOOT_ANIM,
            MODIFIES_NOTIFICATIONS, MODIFIES_ALARMS, MODIFIES_RINGTONES, MODIFIES_OVERLAYS,
            MODIFIES_STATUS_BAR, MODIFIES_NAVIGATION_BAR));

    // Asset paths inside a theme package
    public static final String WALLPAPER_PATH = "wallpapers";
    public static final String LOCKSCREEN_WALLPAPER_PATH = "lockscreen";
    public static final String RINGTONES_PATH = "ringtones";
    public static final String NOTIFICATIONS_PATH = "notifications";
    public static final String ALARMS_PATH = "alarms";
    public static final String BOOTANIMATION_PATH = "bootanimation/bootanimation.zip";
    public static final String APPFILTER_ASSET = "appfilter.xml";

    // Asset layout of cm-13.0 theme packages, which embed their icon pack as uncompiled
    // resources.
    public static final String LEGACY_ICONS_PATH = "icons/res";
    public static final String LEGACY_APPFILTER_ASSET = LEGACY_ICONS_PATH + "/xml/appfilter.xml";

    /** Broadcast sent to all users after a theme change has been applied. */
    public static final String ACTION_THEME_CHANGED = "org.lineageos.intent.action.THEME_CHANGED";
    public static final String EXTRA_COMPONENTS = "components";
    public static final String EXTRA_REQUEST_TYPE = "request_type";
    public static final String EXTRA_UPDATE_TIME = "update_time";

    // Actions in manifests which identify legacy icon packs
    private static final String[] LEGACY_ICON_PACK_ACTIONS = new String[] {
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.novalauncher.THEME"
    };

    // Categories in manifests which identify legacy icon packs
    private static final String[] LEGACY_ICON_PACK_CATEGORIES = new String[] {
            "com.fede.launcher.THEME_ICONPACK",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME"
    };

    /** @return every theme component known to the system. */
    public static List<String> getAllComponents() {
        return ALL_COMPONENTS;
    }

    /**
     * @return whether the given value represents "no theme", i.e. the system default, for a
     *         component.
     */
    public static boolean isSystemDefault(@Nullable String pkgName) {
        return TextUtils.isEmpty(pkgName) || ThemeConfig.SYSTEM_DEFAULT.equals(pkgName);
    }

    /**
     * @param appInfo application info retrieved with {@link PackageManager#GET_META_DATA}
     * @return whether the package is a theme package
     */
    public static boolean isThemeApk(@Nullable ApplicationInfo appInfo) {
        return getThemeName(appInfo) != null;
    }

    /**
     * @param appInfo application info retrieved with {@link PackageManager#GET_META_DATA}
     * @return the display name of the theme, or null if the package is not a theme
     */
    @Nullable
    public static String getThemeName(@Nullable ApplicationInfo appInfo) {
        final Bundle metaData = appInfo != null ? appInfo.metaData : null;
        if (metaData == null) {
            return null;
        }
        final String name = metaData.getString(META_TAG_NAME);
        return name != null ? name : metaData.getString(LEGACY_META_TAG_NAME);
    }

    /**
     * @param appInfo application info retrieved with {@link PackageManager#GET_META_DATA}
     * @return the theme package an overlay package declares as its parent, or null
     */
    @Nullable
    public static String getParentTheme(@Nullable ApplicationInfo appInfo) {
        final Bundle metaData = appInfo != null ? appInfo.metaData : null;
        return metaData != null ? metaData.getString(META_TAG_PARENT) : null;
    }

    /**
     * Convenience method to determine if a theme component is a per app theme and not a standard
     * component.
     */
    public static boolean isPerAppThemeComponent(@NonNull String component) {
        return !(ThemeConfig.KEY_DEFAULT_PKG.equals(component)
                || ThemeConfig.SYSTEMUI_STATUS_BAR_PKG.equals(component)
                || ThemeConfig.SYSTEMUI_NAVBAR_PKG.equals(component));
    }

    /** @return intents resolving to activities of legacy (launcher) icon packs. */
    @NonNull
    public static Intent[] getLegacyIconPackIntents() {
        final Intent[] intents = new Intent[LEGACY_ICON_PACK_ACTIONS.length
                + LEGACY_ICON_PACK_CATEGORIES.length];
        int i = 0;
        for (String action : LEGACY_ICON_PACK_ACTIONS) {
            intents[i++] = new Intent(action);
        }
        for (String category : LEGACY_ICON_PACK_CATEGORIES) {
            intents[i++] = new Intent(Intent.ACTION_MAIN).addCategory(category);
        }
        return intents;
    }
}
