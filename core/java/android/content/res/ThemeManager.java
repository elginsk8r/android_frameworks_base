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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for the theme component service, which applies theme packages (overlays, icon packs,
 * fonts, wallpapers, sounds and boot animations) on top of the overlay manager.
 *
 * <p>This is unrelated to {@link android.app.ThemeManager}, which manages the dynamic color
 * theme; the two are complementary.
 *
 * @hide
 */
@SystemService(Context.THEME_COMPONENT_SERVICE)
public class ThemeManager {
    private static final String TAG = ThemeManager.class.getSimpleName();

    private final Context mContext;
    private final IThemeService mService;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Set<ThemeChangeListener> mChangeListeners = new ArraySet<>();

    /** @hide */
    public ThemeManager(@NonNull Context context, @NonNull IThemeService service) {
        mContext = context;
        mService = service;
    }

    private final IThemeChangeListener mThemeChangeListener = new IThemeChangeListener.Stub() {
        @Override
        public void onProgress(final int progress) {
            mHandler.post(() -> {
                for (ThemeChangeListener listener : getChangeListeners()) {
                    listener.onProgress(progress);
                }
            });
        }

        @Override
        public void onFinish(final boolean isSuccess) {
            mHandler.post(() -> {
                for (ThemeChangeListener listener : getChangeListeners()) {
                    listener.onFinish(isSuccess);
                }
            });
        }
    };

    // Listeners may unregister themselves when notified, notify a copy
    private List<ThemeChangeListener> getChangeListeners() {
        synchronized (mChangeListeners) {
            return new ArrayList<>(mChangeListeners);
        }
    }

    /**
     * Register a {@link ThemeChangeListener} to be notified about theme change progress.
     * @param listener {@link ThemeChangeListener} to register
     */
    public void registerThemeChangeListener(@NonNull ThemeChangeListener listener) {
        synchronized (mChangeListeners) {
            if (mChangeListeners.contains(listener)) {
                throw new IllegalArgumentException("Listener already registered");
            }
            if (mChangeListeners.isEmpty()) {
                try {
                    mService.requestThemeChangeUpdates(mThemeChangeListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mChangeListeners.add(listener);
        }
    }

    /**
     * Unregister a {@link ThemeChangeListener}
     * @param listener {@link ThemeChangeListener} to unregister
     */
    public void unregisterThemeChangeListener(@NonNull ThemeChangeListener listener) {
        synchronized (mChangeListeners) {
            if (mChangeListeners.remove(listener) && mChangeListeners.isEmpty()) {
                try {
                    mService.removeUpdates(mThemeChangeListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Apply the given components of a theme for the calling user.
     */
    public void requestThemeChange(@NonNull String pkgName, @NonNull List<String> components) {
        requestThemeChange(pkgName, components, true);
    }

    public void requestThemeChange(@NonNull String pkgName, @NonNull List<String> components,
            boolean removePerAppThemes) {
        final ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder();
        for (String component : components) {
            builder.setComponent(component, pkgName);
        }
        requestThemeChange(builder.build(), removePerAppThemes);
    }

    public void requestThemeChange(@NonNull Map<String, String> componentMap) {
        requestThemeChange(componentMap, true);
    }

    public void requestThemeChange(@NonNull Map<String, String> componentMap,
            boolean removePerAppThemes) {
        final ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder();
        for (Map.Entry<String, String> entry : componentMap.entrySet()) {
            builder.setComponent(entry.getKey(), entry.getValue());
        }
        requestThemeChange(builder.build(), removePerAppThemes);
    }

    public void requestThemeChange(@NonNull ThemeChangeRequest request,
            boolean removePerAppThemes) {
        try {
            mService.requestThemeChange(request, removePerAppThemes, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply the theme configured as the device default, or reset to the system if none is set.
     */
    public void applyDefaultTheme() {
        try {
            mService.applyDefaultTheme(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean isThemeApplying() {
        try {
            return mService.isThemeApplying();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getProgress() {
        try {
            return mService.getProgress();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return the overlay, icon and font theme mapping for the calling user.
     */
    @NonNull
    public ThemeConfig getThemeConfig() {
        try {
            final ThemeConfig config = mService.getThemeConfig(mContext.getUserId());
            return config != null ? config : ThemeConfig.getSystemTheme();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return a map of theme component to the theme package applied for it, for the calling
     *         user. Components that use the system default are absent.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public Map<String, String> getAppliedComponents() {
        try {
            final Map<String, String> components =
                    mService.getAppliedComponents(mContext.getUserId());
            return components != null ? components : Collections.emptyMap();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public long getLastThemeChangeTime() {
        try {
            return mService.getLastThemeChangeTime(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public ThemeChangeRequest.RequestType getLastThemeChangeRequestType() {
        try {
            final int type = mService.getLastThemeChangeRequestType(mContext.getUserId());
            final ThemeChangeRequest.RequestType[] types = ThemeChangeRequest.RequestType.values();
            return type >= 0 && type < types.length ? types[type] : null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public interface ThemeChangeListener {
        void onProgress(int progress);
        void onFinish(boolean isSuccess);
    }
}
