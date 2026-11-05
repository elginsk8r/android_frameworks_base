/*
 * Copyright (C) 2015 The CyanogenMod Project
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

import static android.content.pm.ThemeUtils.MODIFIES_ALARMS;
import static android.content.pm.ThemeUtils.MODIFIES_BOOT_ANIM;
import static android.content.pm.ThemeUtils.MODIFIES_ICONS;
import static android.content.pm.ThemeUtils.MODIFIES_LAUNCHER;
import static android.content.pm.ThemeUtils.MODIFIES_LOCKSCREEN;
import static android.content.pm.ThemeUtils.MODIFIES_NOTIFICATIONS;
import static android.content.pm.ThemeUtils.MODIFIES_RINGTONES;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes a set of theme components to apply.
 *
 * <p>A component mapped to {@link ThemeConfig#SYSTEM_DEFAULT} or an empty string is reset to the
 * system default.
 *
 * @hide
 */
public final class ThemeChangeRequest implements Parcelable {
    public static final int DEFAULT_WALLPAPER_ID = -1;

    private final Map<String, String> mThemeComponents = new HashMap<>();
    private final RequestType mRequestType;
    private final long mWallpaperId;

    public String getIconsThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_ICONS);
    }

    public String getBootanimationThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_BOOT_ANIM);
    }

    public String getWallpaperThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_LAUNCHER);
    }

    public String getLockWallpaperThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_LOCKSCREEN);
    }

    public String getAlarmThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_ALARMS);
    }

    public String getNotificationThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_NOTIFICATIONS);
    }

    public String getRingtoneThemePackageName() {
        return getThemePackageNameForComponent(MODIFIES_RINGTONES);
    }

    public Map<String, String> getThemeComponentsMap() {
        return Collections.unmodifiableMap(mThemeComponents);
    }

    public long getWallpaperId() {
        return mWallpaperId;
    }

    public int getNumChangesRequested() {
        return mThemeComponents.size();
    }

    public RequestType getRequestType() {
        return mRequestType;
    }

    private String getThemePackageNameForComponent(String componentName) {
        return mThemeComponents.get(componentName);
    }

    private ThemeChangeRequest(Map<String, String> components, RequestType requestType,
            long wallpaperId) {
        if (components != null) {
            mThemeComponents.putAll(components);
        }
        mRequestType = requestType;
        mWallpaperId = wallpaperId;
    }

    private ThemeChangeRequest(Parcel source) {
        int numComponents = source.readInt();
        for (int i = 0; i < numComponents; i++) {
            mThemeComponents.put(source.readString(), source.readString());
        }
        final int requestType = source.readInt();
        mRequestType = requestType >= 0 && requestType < RequestType.values().length
                ? RequestType.values()[requestType] : RequestType.USER_REQUEST;
        mWallpaperId = source.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mThemeComponents.size());
        for (Map.Entry<String, String> entry : mThemeComponents.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeString(entry.getValue());
        }
        dest.writeInt(mRequestType.ordinal());
        dest.writeLong(mWallpaperId);
    }

    @Override
    public String toString() {
        return "ThemeChangeRequest{components=" + mThemeComponents
                + ", type=" + mRequestType
                + ", wallpaperId=" + mWallpaperId + "}";
    }

    public static final Parcelable.Creator<ThemeChangeRequest> CREATOR =
            new Parcelable.Creator<ThemeChangeRequest>() {
                @Override
                public ThemeChangeRequest createFromParcel(Parcel source) {
                    return new ThemeChangeRequest(source);
                }

                @Override
                public ThemeChangeRequest[] newArray(int size) {
                    return new ThemeChangeRequest[size];
                }
            };

    public enum RequestType {
        USER_REQUEST,
        USER_REQUEST_MIXNMATCH,
        THEME_UPDATED,
        THEME_REMOVED,
        THEME_RESET
    }

    public static class Builder {
        private final Map<String, String> mThemeComponents = new HashMap<>();
        private RequestType mRequestType = RequestType.USER_REQUEST;
        private long mWallpaperId = DEFAULT_WALLPAPER_ID;

        public Builder() {}

        public Builder(ThemeConfig themeConfig) {
            if (themeConfig != null) {
                buildChangeRequestFromThemeConfig(themeConfig);
            }
        }

        public Builder setIcons(String pkgName) {
            return setComponent(MODIFIES_ICONS, pkgName);
        }

        public Builder setBootanimation(String pkgName) {
            return setComponent(MODIFIES_BOOT_ANIM, pkgName);
        }

        public Builder setWallpaper(String pkgName) {
            return setComponent(MODIFIES_LAUNCHER, pkgName);
        }

        // Used in the case that more than one wallpaper exists for a given pkg name
        public Builder setWallpaperId(long id) {
            mWallpaperId = id;
            return this;
        }

        public Builder setLockWallpaper(String pkgName) {
            return setComponent(MODIFIES_LOCKSCREEN, pkgName);
        }

        public Builder setAlarm(String pkgName) {
            return setComponent(MODIFIES_ALARMS, pkgName);
        }

        public Builder setNotification(String pkgName) {
            return setComponent(MODIFIES_NOTIFICATIONS, pkgName);
        }

        public Builder setRingtone(String pkgName) {
            return setComponent(MODIFIES_RINGTONES, pkgName);
        }

        public Builder setComponent(String component, String pkgName) {
            if (pkgName != null) {
                mThemeComponents.put(component, pkgName);
            } else {
                mThemeComponents.remove(component);
            }
            return this;
        }

        public Builder setRequestType(RequestType requestType) {
            mRequestType = requestType != null ? requestType : RequestType.USER_REQUEST;
            return this;
        }

        public ThemeChangeRequest build() {
            return new ThemeChangeRequest(mThemeComponents, mRequestType, mWallpaperId);
        }

        private void buildChangeRequestFromThemeConfig(ThemeConfig themeConfig) {
            if (themeConfig.getIconPackPkgName() != null) {
                this.setIcons(themeConfig.getIconPackPkgName());
            }
        }
    }
}
