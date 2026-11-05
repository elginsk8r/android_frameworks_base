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

package android.content.res;

import android.content.res.IThemeChangeListener;
import android.content.res.ThemeChangeRequest;
import android.content.res.ThemeConfig;

/** @hide */
interface IThemeService {
    oneway void requestThemeChangeUpdates(in IThemeChangeListener listener);
    oneway void removeUpdates(in IThemeChangeListener listener);

    oneway void requestThemeChange(in ThemeChangeRequest request, boolean removePerAppThemes,
            int userId);
    oneway void applyDefaultTheme(int userId);
    boolean isThemeApplying();
    int getProgress();

    ThemeConfig getThemeConfig(int userId);
    Map getAppliedComponents(int userId);

    long getLastThemeChangeTime(int userId);
    int getLastThemeChangeRequestType(int userId);
}
