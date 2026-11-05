/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Portions copyright (C) 2014, T-Mobile USA, Inc.
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
package android.content.res;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The Theme Configuration allows lookup of a theme element (fonts, icon, overlay) for a given
 * application. If there isn't a particular theme designated to an app, it will fallback on the
 * default theme. If there isn't a default theme then it will simply fallback to the system.
 *
 * @hide
 */
public class ThemeConfig implements Parcelable {
    public static final String TAG = ThemeConfig.class.getSimpleName();
    public static final String SYSTEM_DEFAULT = "system";

    /**
     * Special package name for theming the navbar separate from the rest of SystemUI
     */
    public static final String SYSTEMUI_NAVBAR_PKG = "com.android.systemui.navbar";
    public static final String SYSTEMUI_STATUS_BAR_PKG = "com.android.systemui";

    // Key for any app which does not have a specific theme applied
    public static final String KEY_DEFAULT_PKG = "default";
    private static final ThemeConfig sSystemConfig = new ThemeConfig(Collections.emptyMap());
    private static final AppTheme sSystemAppTheme =
            new AppTheme(SYSTEM_DEFAULT, SYSTEM_DEFAULT, SYSTEM_DEFAULT);

    // Maps pkgname to theme (ex com.angry.birds -> red theme)
    protected final Map<String, AppTheme> mThemes = new ArrayMap<>();

    public ThemeConfig(Map<String, AppTheme> appThemes) {
        mThemes.putAll(appThemes);
    }

    public String getOverlayPkgName() {
        return getDefaultTheme().mOverlayPkgName;
    }

    public String getOverlayForStatusBar() {
        return getOverlayPkgNameForApp(SYSTEMUI_STATUS_BAR_PKG);
    }

    public String getOverlayForNavBar() {
        return getOverlayPkgNameForApp(SYSTEMUI_NAVBAR_PKG);
    }

    public String getOverlayPkgNameForApp(String appPkgName) {
        return getThemeFor(appPkgName).mOverlayPkgName;
    }

    public String getIconPackPkgName() {
        return getDefaultTheme().mIconPkgName;
    }

    public String getIconPackPkgNameForApp(String appPkgName) {
        return getThemeFor(appPkgName).mIconPkgName;
    }

    public String getFontPkgName() {
        return getDefaultTheme().mFontPkgName;
    }

    public String getFontPkgNameForApp(String appPkgName) {
        return getThemeFor(appPkgName).mFontPkgName;
    }

    public Map<String, AppTheme> getAppThemes() {
        return Collections.unmodifiableMap(mThemes);
    }

    private AppTheme getThemeFor(String pkgName) {
        AppTheme theme = mThemes.get(pkgName);
        if (theme == null) theme = getDefaultTheme();
        return theme;
    }

    private AppTheme getDefaultTheme() {
        AppTheme theme = mThemes.get(KEY_DEFAULT_PKG);
        if (theme == null) theme = sSystemAppTheme;
        return theme;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (object instanceof ThemeConfig) {
            return mThemes.equals(((ThemeConfig) object).mThemes);
        }
        return false;
    }

    @Override
    public String toString() {
        return "themes:" + mThemes;
    }

    @Override
    public int hashCode() {
        return 31 * 17 + mThemes.hashCode();
    }

    public String toJson() {
        return JsonSerializer.toJson(this);
    }

    public static ThemeConfig fromJson(String json) {
        return JsonSerializer.fromJson(json);
    }

    /**
     * Represents the system framework theme, perceived by the system as there
     * being no theme applied.
     */
    public static ThemeConfig getSystemTheme() {
        return sSystemConfig;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(JsonSerializer.toJson(this));
    }

    public static final Parcelable.Creator<ThemeConfig> CREATOR =
            new Parcelable.Creator<ThemeConfig>() {
        public ThemeConfig createFromParcel(Parcel source) {
            ThemeConfig config = JsonSerializer.fromJson(source.readString());
            return config != null ? config : sSystemConfig;
        }

        public ThemeConfig[] newArray(int size) {
            return new ThemeConfig[size];
        }
    };

    public static class AppTheme {
        // If any field is modified or added here be sure to change the serializer accordingly
        final String mOverlayPkgName;
        final String mIconPkgName;
        final String mFontPkgName;

        public AppTheme(String overlayPkgName, String iconPkgName, String fontPkgName) {
            mOverlayPkgName = overlayPkgName;
            mIconPkgName = iconPkgName;
            mFontPkgName = fontPkgName;
        }

        public String getIconPackPkgName() {
            return mIconPkgName;
        }

        public String getOverlayPkgName() {
            return mOverlayPkgName;
        }

        public String getFontPackPkgName() {
            return mFontPkgName;
        }

        @Override
        public int hashCode() {
            return Objects.hash(TextUtils.emptyIfNull(mOverlayPkgName),
                    TextUtils.emptyIfNull(mIconPkgName), TextUtils.emptyIfNull(mFontPkgName));
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) {
                return true;
            }
            if (object instanceof AppTheme) {
                AppTheme o = (AppTheme) object;
                return TextUtils.emptyIfNull(mOverlayPkgName).equals(
                                TextUtils.emptyIfNull(o.mOverlayPkgName))
                        && TextUtils.emptyIfNull(mIconPkgName).equals(
                                TextUtils.emptyIfNull(o.mIconPkgName))
                        && TextUtils.emptyIfNull(mFontPkgName).equals(
                                TextUtils.emptyIfNull(o.mFontPkgName));
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            if (mOverlayPkgName != null) {
                result.append("overlay:");
                result.append(mOverlayPkgName);
            }
            if (!TextUtils.isEmpty(mIconPkgName)) {
                result.append(", iconPack:");
                result.append(mIconPkgName);
            }
            if (!TextUtils.isEmpty(mFontPkgName)) {
                result.append(", fontPkg:");
                result.append(mFontPkgName);
            }
            return result.toString();
        }
    }

    public static class Builder {
        private final Map<String, String> mOverlays = new ArrayMap<>();
        private final Map<String, String> mIcons = new ArrayMap<>();
        private final Map<String, String> mFonts = new ArrayMap<>();

        public Builder() {}

        public Builder(ThemeConfig theme) {
            if (theme == null) {
                return;
            }
            for (Map.Entry<String, AppTheme> entry : theme.mThemes.entrySet()) {
                String key = entry.getKey();
                AppTheme appTheme = entry.getValue();
                putOrRemove(mFonts, key, appTheme.getFontPackPkgName());
                putOrRemove(mIcons, key, appTheme.getIconPackPkgName());
                putOrRemove(mOverlays, key, appTheme.getOverlayPkgName());
            }
        }

        public Builder defaultOverlay(String themePkgName) {
            return overlay(KEY_DEFAULT_PKG, themePkgName);
        }

        public Builder defaultFont(String themePkgName) {
            return font(KEY_DEFAULT_PKG, themePkgName);
        }

        public Builder defaultIcon(String themePkgName) {
            return icon(KEY_DEFAULT_PKG, themePkgName);
        }

        public Builder icon(String appPkgName, String themePkgName) {
            putOrRemove(mIcons, appPkgName, themePkgName);
            return this;
        }

        /**
         * For uniquely theming a specific app. ex. "Dialer gets red theme,
         * Calculator gets blue theme"
         */
        public Builder overlay(String appPkgName, String themePkgName) {
            putOrRemove(mOverlays, appPkgName, themePkgName);
            return this;
        }

        public Builder font(String appPkgName, String themePkgName) {
            putOrRemove(mFonts, appPkgName, themePkgName);
            return this;
        }

        public ThemeConfig build() {
            ArraySet<String> appPkgSet = new ArraySet<>();
            appPkgSet.addAll(mOverlays.keySet());
            appPkgSet.addAll(mIcons.keySet());
            appPkgSet.addAll(mFonts.keySet());

            Map<String, AppTheme> appThemes = new ArrayMap<>();
            for (String appPkgName : appPkgSet) {
                appThemes.put(appPkgName, new AppTheme(mOverlays.get(appPkgName),
                        mIcons.get(appPkgName), mFonts.get(appPkgName)));
            }
            return new ThemeConfig(appThemes);
        }

        private static void putOrRemove(Map<String, String> map, String key, String value) {
            if (value != null) {
                map.put(key, value);
            } else {
                map.remove(key);
            }
        }
    }

    public static class JsonSerializer {
        private static final String NAME_OVERLAY_PKG = "mOverlayPkgName";
        private static final String NAME_ICON_PKG = "mIconPkgName";
        private static final String NAME_FONT_PKG = "mFontPkgName";

        public static String toJson(ThemeConfig theme) {
            StringWriter writer = new StringWriter();
            try (JsonWriter jsonWriter = new JsonWriter(writer)) {
                writeTheme(jsonWriter, theme);
            } catch (IOException e) {
                Log.e(TAG, "Could not write theme mapping", e);
                return null;
            }
            return writer.toString();
        }

        private static void writeTheme(JsonWriter writer, ThemeConfig theme)
                throws IOException {
            writer.beginObject();
            for (Map.Entry<String, AppTheme> entry : theme.mThemes.entrySet()) {
                writer.name(entry.getKey());
                writeAppTheme(writer, entry.getValue());
            }
            writer.endObject();
        }

        private static void writeAppTheme(JsonWriter writer, AppTheme appTheme)
                throws IOException {
            writer.beginObject();
            writer.name(NAME_OVERLAY_PKG).value(appTheme.mOverlayPkgName);
            writer.name(NAME_ICON_PKG).value(appTheme.mIconPkgName);
            writer.name(NAME_FONT_PKG).value(appTheme.mFontPkgName);
            writer.endObject();
        }

        public static ThemeConfig fromJson(String json) {
            if (json == null) return null;
            Map<String, AppTheme> map = new ArrayMap<>();
            try (JsonReader jsonReader = new JsonReader(new StringReader(json))) {
                jsonReader.beginObject();
                while (jsonReader.hasNext()) {
                    String appPkgName = jsonReader.nextName();
                    map.put(appPkgName, readAppTheme(jsonReader));
                }
                jsonReader.endObject();
            } catch (Exception e) {
                Log.e(TAG, "Could not parse ThemeConfig from: " + json, e);
            }
            return new ThemeConfig(map);
        }

        private static AppTheme readAppTheme(JsonReader reader) throws IOException {
            String overlay = null;
            String icon = null;
            String font = null;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (NAME_OVERLAY_PKG.equals(name) && reader.peek() != JsonToken.NULL) {
                    overlay = reader.nextString();
                } else if (NAME_ICON_PKG.equals(name) && reader.peek() != JsonToken.NULL) {
                    icon = reader.nextString();
                } else if (NAME_FONT_PKG.equals(name) && reader.peek() != JsonToken.NULL) {
                    font = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();

            return new AppTheme(overlay, icon, font);
        }
    }
}
