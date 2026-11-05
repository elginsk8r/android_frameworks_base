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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ThemeUtils;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.graphics.palette.CelebiQuantizer;
import com.android.internal.graphics.palette.Palette;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads an ADW/Nova style icon pack ({@code appfilter.xml}) and renders themed app icons.
 *
 * <p>Icons that the pack maps explicitly are taken from the pack. Other icons are composed from
 * the original icon using the pack's {@code iconback}, {@code iconmask}, {@code iconupon},
 * {@code scale}, {@code rotate}, {@code translate}, {@code filter} and {@code paletteback}
 * directives, as the cm-13.0 theme engine did.
 */
final class IconPackHelper {
    private static final String TAG = "IconPackHelper";

    private static final String ICON_MASK_TAG = "iconmask";
    private static final String ICON_BACK_TAG = "iconback";
    private static final String ICON_UPON_TAG = "iconupon";
    private static final String ICON_SCALE_TAG = "scale";
    private static final String ICON_ROTATE_TAG = "rotate";
    private static final String ICON_TRANSLATE_TAG = "translate";
    private static final String ICON_PALETTIZED_BACK_TAG = "paletteback";
    private static final String ITEM_TAG = "item";
    private static final String IMG_ATTR = "img";
    private static final String SWATCH_TYPE_ATTR = "swatchType";
    private static final String DEFAULT_SWATCH_COLOR_ATTR = "defaultSwatchColor";
    private static final String ANGLE_ATTR = "angle";
    private static final String ANGLE_VARIANCE = "plusMinus";
    private static final String TRANSLATE_X_ATTR = "xOffset";
    private static final String TRANSLATE_Y_ATTR = "yOffset";
    private static final int NUM_PALETTE_COLORS = 32;
    private static final float DEFAULT_SCALE = 1.0f;

    enum SwatchType {
        None,
        Vibrant,
        VibrantLight,
        VibrantDark,
        Muted,
        MutedLight,
        MutedDark
    }

    private final String mPackageName;
    private final Resources mResources;
    private final float mDensity;
    // Density -> drawable directory of the icon pack embedded in a cm-13.0 theme's assets, or
    // null if the pack is a regular resource based one
    private final SparseArray<String> mLegacyDrawableDirs;

    // Keys are lower case "package/class" for activities and "package" for whole apps
    private final Map<String, String> mIconMap = new ArrayMap<>();

    private final List<String> mIconBacks = new ArrayList<>();
    private String mIconMask;
    private String mIconUpon;
    private float mIconScale = DEFAULT_SCALE;
    private float mIconRotation;
    private float mIconRotationVariance;
    private float mIconTranslationX;
    private float mIconTranslationY;
    private String mIconPaletteBack;
    private SwatchType mSwatchType = SwatchType.None;
    private final List<Integer> mDefaultSwatchColors = new ArrayList<>();
    private final ColorFilterUtils.Builder mFilterBuilder = new ColorFilterUtils.Builder();
    private ColorMatrix mColorFilter;

    /**
     * @param context a context for the user the icon pack is loaded for
     * @param packageName the icon pack package
     */
    IconPackHelper(@NonNull Context context, @NonNull String packageName)
            throws PackageManager.NameNotFoundException {
        mPackageName = packageName;
        mResources = context.getPackageManager().getResourcesForApplication(packageName);
        mDensity = context.getResources().getDisplayMetrics().density;
        mLegacyDrawableDirs = getLegacyDrawableDirs(mResources);
        loadIconPack();
        mColorFilter = mFilterBuilder.build();
    }

    @NonNull
    String getPackageName() {
        return mPackageName;
    }

    /** @return whether the pack defines any mapped icon or composition directive. */
    boolean isEmpty() {
        return mIconMap.isEmpty() && !shouldComposeIcons();
    }

    /** @return whether icons the pack does not map should be composed. */
    boolean shouldComposeIcons() {
        return !mIconBacks.isEmpty()
                || mIconMask != null
                || mIconUpon != null
                || mColorFilter != null
                || mIconPaletteBack != null
                || mIconRotation != 0
                || mIconRotationVariance != 0
                || mIconTranslationX != 0
                || mIconTranslationY != 0
                || mIconScale != DEFAULT_SCALE;
    }

    /**
     * @return the icon the pack maps to the given activity, falling back to the icon it maps to
     *         the activity's package, or null if the pack does not map either.
     */
    @Nullable
    Drawable getMappedIcon(@NonNull ComponentName activity, int density) {
        String drawable = mIconMap.get(flatten(activity.getPackageName(),
                activity.getClassName()));
        if (drawable == null) {
            drawable = mIconMap.get(flatten(activity.getPackageName(), null));
        }
        return drawable != null ? loadDrawable(drawable, density) : null;
    }

    /**
     * Renders the themed icon for an activity.
     *
     * @param activity the activity whose icon is themed
     * @param original the activity's original icon, used when the pack composes icons
     * @param size the edge length of the resulting bitmap in pixels
     * @param density the density to load pack drawables for
     * @return the themed icon, or null if the pack neither maps nor composes this icon
     */
    @Nullable
    Bitmap createIcon(@NonNull ComponentName activity, @Nullable Drawable original, int size,
            int density) {
        final Drawable mapped = getMappedIcon(activity, density);
        if (mapped != null) {
            return drawToBitmap(mapped, size);
        }
        if (original == null || !shouldComposeIcons()) {
            return null;
        }
        return composeIcon(activity, original, size, density);
    }

    private Bitmap composeIcon(ComponentName activity, Drawable icon, int size, int density) {
        // Use a stable seed so a given app gets the same back and rotation every time the
        // pack is applied.
        final int seed = activity.flattenToShortString().hashCode() & 0x7fffffff;

        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG,
                Paint.FILTER_BITMAP_FLAG));

        // Scale, translate and rotate the original
        final Bitmap original = drawToBitmap(icon, size);
        final float half = size / 2f;
        float angle = mIconRotation;
        if (mIconRotationVariance != 0) {
            final float random = (seed % 1000) / 1000f;
            angle += (random * mIconRotationVariance * 2) - mIconRotationVariance;
        }
        canvas.save();
        canvas.scale(mIconScale, mIconScale, half, half);
        canvas.translate(mIconTranslationX, mIconTranslationY);
        canvas.rotate(angle, half, half);
        final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (mColorFilter != null) {
            iconPaint.setColorFilter(new ColorMatrixColorFilter(mColorFilter));
        }
        canvas.drawBitmap(original, 0, 0, iconPaint);
        canvas.restore();

        // Mask off the original if iconMask is not null
        final Drawable mask = mIconMask != null ? loadDrawable(mIconMask, density) : null;
        if (mask != null) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            canvas.drawBitmap(drawToBitmap(mask, size), 0, 0, paint);
        }

        // Draw the iconBack behind the (scaled and masked) original
        String backName = null;
        int backTintColor = 0;
        if (mSwatchType != SwatchType.None && mIconPaletteBack != null) {
            backName = mIconPaletteBack;
            final int defaultColor = mDefaultSwatchColors.isEmpty() ? 0
                    : mDefaultSwatchColors.get(seed % mDefaultSwatchColors.size());
            backTintColor = getSwatchColor(original, mSwatchType, defaultColor);
        } else if (!mIconBacks.isEmpty()) {
            backName = mIconBacks.get(seed % mIconBacks.size());
        }
        final Drawable back = backName != null ? loadDrawable(backName, density) : null;
        if (back != null) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
            if (backTintColor != 0) {
                paint.setColorFilter(new PorterDuffColorFilter(backTintColor,
                        PorterDuff.Mode.MULTIPLY));
            }
            canvas.drawBitmap(drawToBitmap(back, size), 0, 0, paint);
        }

        // Finally draw the foreground if one was supplied
        final Drawable upon = mIconUpon != null ? loadDrawable(mIconUpon, density) : null;
        if (upon != null) {
            upon.setBounds(0, 0, size, size);
            upon.draw(canvas);
        }

        original.recycle();
        return bitmap;
    }

    @Nullable
    private Drawable loadDrawable(@NonNull String name, int density) {
        if (mLegacyDrawableDirs != null) {
            return loadLegacyDrawable(name, density);
        }
        final int resId = mResources.getIdentifier(name, "drawable", mPackageName);
        if (resId == 0) {
            return null;
        }
        try {
            return mResources.getDrawableForDensity(resId, density, null);
        } catch (Resources.NotFoundException e) {
            Slog.w(TAG, "Unable to load " + name + " from " + mPackageName, e);
            return null;
        }
    }

    @NonNull
    private static Bitmap drawToBitmap(@NonNull Drawable drawable, int size) {
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    private static String flatten(@NonNull String packageName, @Nullable String className) {
        return TextUtils.isEmpty(className)
                ? packageName.toLowerCase(Locale.ROOT)
                : (packageName + "/" + className).toLowerCase(Locale.ROOT);
    }

    /**
     * Loads a drawable of an icon pack embedded in a cm-13.0 theme, picking the closest density
     * at or above the requested one.
     */
    @Nullable
    private Drawable loadLegacyDrawable(@NonNull String name, int density) {
        final int start = mLegacyDrawableDirs.indexOfKey(density) >= 0
                ? mLegacyDrawableDirs.indexOfKey(density)
                : -mLegacyDrawableDirs.indexOfKey(density) - 1;
        // Try the best match first, then higher densities, then lower ones
        for (int i = 0; i < mLegacyDrawableDirs.size(); i++) {
            final int index = start + i < mLegacyDrawableDirs.size()
                    ? start + i : mLegacyDrawableDirs.size() - 1 - i;
            if (index < 0) {
                break;
            }
            final String dir = mLegacyDrawableDirs.valueAt(index);
            try (InputStream in = mResources.getAssets().open(dir + "/" + name + ".png")) {
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inDensity = mLegacyDrawableDirs.keyAt(index);
                options.inTargetDensity = density;
                final Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
                if (bitmap != null) {
                    return new BitmapDrawable(mResources, bitmap);
                }
            } catch (IOException e) {
                // Not in this density
            }
        }
        return null;
    }

    /** @return density -> asset directory of the drawables of an embedded cm-13.0 icon pack. */
    @Nullable
    private static SparseArray<String> getLegacyDrawableDirs(@NonNull Resources res) {
        final String[] dirs;
        try {
            dirs = res.getAssets().list(ThemeUtils.LEGACY_ICONS_PATH);
        } catch (IOException e) {
            return null;
        }
        if (dirs == null || dirs.length == 0) {
            return null;
        }
        final SparseArray<String> drawableDirs = new SparseArray<>();
        for (String dir : dirs) {
            if (!dir.equals("drawable") && !dir.startsWith("drawable-")) {
                continue;
            }
            int density = DisplayMetrics.DENSITY_DEFAULT;
            for (String qualifier : dir.split("-")) {
                final int qualifierDensity = getDensity(qualifier);
                if (qualifierDensity != 0) {
                    density = qualifierDensity;
                }
            }
            drawableDirs.put(density, ThemeUtils.LEGACY_ICONS_PATH + "/" + dir);
        }
        return drawableDirs.size() > 0 ? drawableDirs : null;
    }

    private static int getDensity(@NonNull String qualifier) {
        switch (qualifier) {
            case "ldpi":
                return DisplayMetrics.DENSITY_LOW;
            case "mdpi":
                return DisplayMetrics.DENSITY_MEDIUM;
            case "tvdpi":
                return DisplayMetrics.DENSITY_TV;
            case "hdpi":
                return DisplayMetrics.DENSITY_HIGH;
            case "xhdpi":
                return DisplayMetrics.DENSITY_XHIGH;
            case "xxhdpi":
                return DisplayMetrics.DENSITY_XXHIGH;
            case "xxxhdpi":
                return DisplayMetrics.DENSITY_XXXHIGH;
            default:
                return 0;
        }
    }

    private void loadIconPack() {
        XmlPullParser parser = null;
        InputStream inputStream = null;
        try {
            inputStream = mResources.getAssets().open(mLegacyDrawableDirs != null
                    ? ThemeUtils.LEGACY_APPFILTER_ASSET : ThemeUtils.APPFILTER_ASSET);
            parser = Xml.newPullParser();
            parser.setInput(inputStream, "UTF-8");
        } catch (Exception e) {
            // Fall back to parsing the xml resource
            final int resId = mResources.getIdentifier("appfilter", "xml", mPackageName);
            if (resId != 0) {
                parser = mResources.getXml(resId);
            }
        }

        if (parser != null) {
            try {
                loadResourcesFromXmlParser(parser);
                return;
            } catch (XmlPullParserException | IOException e) {
                Slog.w(TAG, "Unable to parse appfilter of " + mPackageName, e);
            } finally {
                if (parser instanceof XmlResourceParser) {
                    ((XmlResourceParser) parser).close();
                } else if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        // Application uses a different theme format (most likely launcher pro)
        int arrayId = mResources.getIdentifier("theme_iconpack", "array", mPackageName);
        if (arrayId == 0) {
            arrayId = mResources.getIdentifier("icon_pack", "array", mPackageName);
        }
        if (arrayId == 0) {
            return;
        }

        for (String icon : mResources.getStringArray(arrayId)) {
            if (TextUtils.isEmpty(icon)) {
                continue;
            }
            final String entry = icon.replaceAll("_", ".");
            mIconMap.put(flatten(entry, null), icon);

            final int activityIndex = entry.lastIndexOf('.');
            if (activityIndex <= 0 || activityIndex == entry.length() - 1) {
                continue;
            }
            final String iconPackage = entry.substring(0, activityIndex);
            mIconMap.put(flatten(iconPackage, entry), icon);
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        do {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }

            final String tag = parser.getName();
            if (parseComposedIconComponent(parser, tag)) {
                continue;
            }
            if (ColorFilterUtils.parseIconFilter(parser, mFilterBuilder)) {
                continue;
            }
            if (ICON_SCALE_TAG.equalsIgnoreCase(tag)) {
                String factor = parser.getAttributeValue(null, "factor");
                if (factor == null && parser.getAttributeCount() == 1) {
                    factor = parser.getAttributeValue(0);
                }
                mIconScale = parseFloat(factor, DEFAULT_SCALE);
                continue;
            }
            if (ICON_ROTATE_TAG.equalsIgnoreCase(tag)) {
                mIconRotation = parseFloat(parser.getAttributeValue(null, ANGLE_ATTR), 0);
                mIconRotationVariance =
                        parseFloat(parser.getAttributeValue(null, ANGLE_VARIANCE), 0);
                continue;
            }
            if (ICON_TRANSLATE_TAG.equalsIgnoreCase(tag)) {
                mIconTranslationX = parseFloat(
                        parser.getAttributeValue(null, TRANSLATE_X_ATTR), 0) * mDensity;
                mIconTranslationY = parseFloat(
                        parser.getAttributeValue(null, TRANSLATE_Y_ATTR), 0) * mDensity;
                continue;
            }
            if (!ITEM_TAG.equalsIgnoreCase(tag)) {
                continue;
            }

            String component = parser.getAttributeValue(null, "component");
            final String drawable = parser.getAttributeValue(null, "drawable");

            // Validate component/drawable exist and the format of component
            if (TextUtils.isEmpty(component) || TextUtils.isEmpty(drawable)
                    || !component.startsWith("ComponentInfo{") || !component.endsWith("}")
                    || component.length() < 16) {
                continue;
            }

            component = component.substring(14, component.length() - 1);
            if (!component.contains("/")) {
                // Package icon reference
                mIconMap.put(flatten(component, null), drawable);
            } else {
                final ComponentName name = ComponentName.unflattenFromString(component);
                if (name != null) {
                    mIconMap.put(flatten(name.getPackageName(), name.getClassName()), drawable);
                }
            }
        } while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT);
    }

    private boolean parseComposedIconComponent(XmlPullParser parser, String tag) {
        if (!ICON_MASK_TAG.equalsIgnoreCase(tag) && !ICON_BACK_TAG.equalsIgnoreCase(tag)
                && !ICON_UPON_TAG.equalsIgnoreCase(tag)
                && !ICON_PALETTIZED_BACK_TAG.equalsIgnoreCase(tag)) {
            return false;
        }
        if (parser.getAttributeCount() < 1) {
            return false;
        }

        if (ICON_BACK_TAG.equalsIgnoreCase(tag)) {
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                mIconBacks.add(parser.getAttributeValue(i));
            }
        } else if (ICON_PALETTIZED_BACK_TAG.equalsIgnoreCase(tag)) {
            parsePalettizedBackground(parser);
        } else if (ICON_MASK_TAG.equalsIgnoreCase(tag)) {
            mIconMask = parser.getAttributeValue(0);
        } else {
            mIconUpon = parser.getAttributeValue(0);
        }
        return true;
    }

    private void parsePalettizedBackground(XmlPullParser parser) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            final String name = parser.getAttributeName(i);
            final String value = parser.getAttributeValue(i);
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) {
                continue;
            }
            if (IMG_ATTR.equalsIgnoreCase(name)) {
                mIconPaletteBack = value;
            } else if (SWATCH_TYPE_ATTR.equalsIgnoreCase(name)) {
                for (SwatchType type : SwatchType.values()) {
                    if (type.name().equalsIgnoreCase(value)) {
                        mSwatchType = type;
                        break;
                    }
                }
            } else if (name.startsWith(DEFAULT_SWATCH_COLOR_ATTR)) {
                try {
                    // ensure alpha is always 0xff
                    mDefaultSwatchColors.add(Color.parseColor(value) | 0xff000000);
                } catch (IllegalArgumentException e) {
                    Slog.w(TAG, "Invalid color format " + value);
                }
            }
        }
    }

    private static float parseFloat(@Nullable String value, float defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Picks the swatch of the requested type from the icon, using the same lightness and
     * saturation targets as the AndroidX palette library that cm-13.0 relied on.
     */
    private static int getSwatchColor(Bitmap bitmap, SwatchType type, int defaultColor) {
        final float minSaturation;
        final float maxSaturation;
        final float targetSaturation;
        final float minLightness;
        final float maxLightness;
        final float targetLightness;
        switch (type) {
            case Vibrant:
                minSaturation = 0.35f; maxSaturation = 1f; targetSaturation = 1f;
                minLightness = 0.3f; maxLightness = 0.7f; targetLightness = 0.5f;
                break;
            case VibrantLight:
                minSaturation = 0.35f; maxSaturation = 1f; targetSaturation = 1f;
                minLightness = 0.55f; maxLightness = 1f; targetLightness = 0.74f;
                break;
            case VibrantDark:
                minSaturation = 0.35f; maxSaturation = 1f; targetSaturation = 1f;
                minLightness = 0f; maxLightness = 0.45f; targetLightness = 0.26f;
                break;
            case Muted:
                minSaturation = 0f; maxSaturation = 0.4f; targetSaturation = 0.3f;
                minLightness = 0.3f; maxLightness = 0.7f; targetLightness = 0.5f;
                break;
            case MutedLight:
                minSaturation = 0f; maxSaturation = 0.4f; targetSaturation = 0.3f;
                minLightness = 0.55f; maxLightness = 1f; targetLightness = 0.74f;
                break;
            case MutedDark:
                minSaturation = 0f; maxSaturation = 0.4f; targetSaturation = 0.3f;
                minLightness = 0f; maxLightness = 0.45f; targetLightness = 0.26f;
                break;
            default:
                return defaultColor;
        }

        final List<Palette.Swatch> swatches = Palette.from(bitmap, new CelebiQuantizer())
                .maximumColorCount(NUM_PALETTE_COLORS)
                .generate()
                .getSwatches();
        int maxPopulation = 1;
        for (Palette.Swatch swatch : swatches) {
            maxPopulation = Math.max(maxPopulation, swatch.getPopulation());
        }

        final float[] hsl = new float[3];
        Palette.Swatch best = null;
        float bestScore = -1;
        for (Palette.Swatch swatch : swatches) {
            if (Color.alpha(swatch.getInt()) == 0) {
                continue;
            }
            ColorUtils.colorToHSL(swatch.getInt(), hsl);
            if (hsl[1] < minSaturation || hsl[1] > maxSaturation
                    || hsl[2] < minLightness || hsl[2] > maxLightness) {
                continue;
            }
            final float score = 0.24f * (1f - Math.abs(hsl[1] - targetSaturation))
                    + 0.52f * (1f - Math.abs(hsl[2] - targetLightness))
                    + 0.24f * (swatch.getPopulation() / (float) maxPopulation);
            if (score > bestScore) {
                bestScore = score;
                best = swatch;
            }
        }
        return best != null ? (best.getInt() | 0xff000000) : defaultColor;
    }

    static final class ColorFilterUtils {
        private static final String TAG_FILTER = "filter";
        private static final String FILTER_HUE = "hue";
        private static final String FILTER_SATURATION = "saturation";
        private static final String FILTER_INVERT = "invert";
        private static final String FILTER_BRIGHTNESS = "brightness";
        private static final String FILTER_CONTRAST = "contrast";
        private static final String FILTER_ALPHA = "alpha";
        private static final String FILTER_TINT = "tint";

        private static final int MIN_HUE = -180;
        private static final int MAX_HUE = 180;
        private static final int MIN_SATURATION = 0;
        private static final int MAX_SATURATION = 200;
        private static final int MIN_BRIGHTNESS = 0;
        private static final int MAX_BRIGHTNESS = 200;
        private static final int MIN_CONTRAST = -100;
        private static final int MAX_CONTRAST = 100;
        private static final int MIN_ALPHA = 0;
        private static final int MAX_ALPHA = 100;

        static boolean parseIconFilter(XmlPullParser parser, Builder builder)
                throws IOException, XmlPullParserException {
            if (!TAG_FILTER.equals(parser.getName())) return false;

            final String attr = parser.getAttributeValue(null, "name");
            String content = parser.nextText();
            if (attr != null && content != null && content.length() > 0) {
                content = content.trim();
                if (FILTER_HUE.equalsIgnoreCase(attr)) {
                    builder.hue(clampValue(getInt(content, 0), MIN_HUE, MAX_HUE));
                } else if (FILTER_SATURATION.equalsIgnoreCase(attr)) {
                    builder.saturate(clampValue(getInt(content, 100),
                            MIN_SATURATION, MAX_SATURATION));
                } else if (FILTER_INVERT.equalsIgnoreCase(attr)) {
                    if ("true".equalsIgnoreCase(content)) {
                        builder.invertColors();
                    }
                } else if (FILTER_BRIGHTNESS.equalsIgnoreCase(attr)) {
                    builder.brightness(clampValue(getInt(content, 100),
                            MIN_BRIGHTNESS, MAX_BRIGHTNESS));
                } else if (FILTER_CONTRAST.equalsIgnoreCase(attr)) {
                    builder.contrast(clampValue(getInt(content, 0),
                            MIN_CONTRAST, MAX_CONTRAST));
                } else if (FILTER_ALPHA.equalsIgnoreCase(attr)) {
                    builder.alpha(clampValue(getInt(content, 100), MIN_ALPHA, MAX_ALPHA));
                } else if (FILTER_TINT.equalsIgnoreCase(attr)) {
                    try {
                        builder.tint(Color.parseColor(content));
                    } catch (IllegalArgumentException e) {
                        Slog.w(TAG, "Cannot apply tint, invalid argument: " + content);
                    }
                }
            }
            return true;
        }

        private static int getInt(String value, int defaultValue) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private static int clampValue(int value, int min, int max) {
            return Math.min(max, Math.max(min, value));
        }

        /**
         * See the following links for reference
         * http://groups.google.com/group/android-developers/browse_thread/thread/9e215c83c3819953
         * http://gskinner.com/blog/archives/2007/12/colormatrix_cla.html
         */
        static ColorMatrix adjustHue(float value) {
            ColorMatrix cm = new ColorMatrix();
            value = value / 180 * (float) Math.PI;
            if (value != 0) {
                float cosVal = (float) Math.cos(value);
                float sinVal = (float) Math.sin(value);
                float lumR = 0.213f;
                float lumG = 0.715f;
                float lumB = 0.072f;
                float[] mat = new float[]{
                        lumR + cosVal * (1 - lumR) + sinVal * (-lumR),
                        lumG + cosVal * (-lumG) + sinVal * (-lumG),
                        lumB + cosVal * (-lumB) + sinVal * (1 - lumB), 0, 0,
                        lumR + cosVal * (-lumR) + sinVal * (0.143f),
                        lumG + cosVal * (1 - lumG) + sinVal * (0.140f),
                        lumB + cosVal * (-lumB) + sinVal * (-0.283f), 0, 0,
                        lumR + cosVal * (-lumR) + sinVal * (-(1 - lumR)),
                        lumG + cosVal * (-lumG) + sinVal * (lumG),
                        lumB + cosVal * (1 - lumB) + sinVal * (lumB), 0, 0,
                        0, 0, 0, 1, 0,
                        0, 0, 0, 0, 1};
                cm.set(mat);
            }
            return cm;
        }

        static ColorMatrix adjustSaturation(float saturation) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(saturation / 100);
            return cm;
        }

        static ColorMatrix invertColors() {
            float[] matrix = {
                    -1, 0, 0, 0, 255, //red
                    0, -1, 0, 0, 255, //green
                    0, 0, -1, 0, 255, //blue
                    0, 0, 0, 1, 0 //alpha
            };
            return new ColorMatrix(matrix);
        }

        static ColorMatrix adjustBrightness(float brightness) {
            brightness = brightness / 100;
            ColorMatrix cm = new ColorMatrix();
            cm.setScale(brightness, brightness, brightness, 1);
            return cm;
        }

        static ColorMatrix adjustContrast(float contrast) {
            contrast = contrast / 100 + 1;
            float o = (-0.5f * contrast + 0.5f) * 255;
            float[] matrix = {
                    contrast, 0, 0, 0, o, //red
                    0, contrast, 0, 0, o, //green
                    0, 0, contrast, 0, o, //blue
                    0, 0, 0, 1, 0 //alpha
            };
            return new ColorMatrix(matrix);
        }

        static ColorMatrix adjustAlpha(float alpha) {
            ColorMatrix cm = new ColorMatrix();
            cm.setScale(1, 1, 1, alpha / 100);
            return cm;
        }

        static ColorMatrix applyTint(int color) {
            float alpha = Color.alpha(color) / 255f;
            float red = Color.red(color) * alpha;
            float green = Color.green(color) * alpha;
            float blue = Color.blue(color) * alpha;

            float[] matrix = {
                    1, 0, 0, 0, red, //red
                    0, 1, 0, 0, green, //green
                    0, 0, 1, 0, blue, //blue
                    0, 0, 0, 1, 0 //alpha
            };
            return new ColorMatrix(matrix);
        }

        static final class Builder {
            private final List<ColorMatrix> mMatrixList = new ArrayList<>();

            Builder hue(float value) {
                mMatrixList.add(adjustHue(value));
                return this;
            }

            Builder saturate(float saturation) {
                mMatrixList.add(adjustSaturation(saturation));
                return this;
            }

            Builder brightness(float brightness) {
                mMatrixList.add(adjustBrightness(brightness));
                return this;
            }

            Builder contrast(float contrast) {
                mMatrixList.add(adjustContrast(contrast));
                return this;
            }

            Builder alpha(float alpha) {
                mMatrixList.add(adjustAlpha(alpha));
                return this;
            }

            Builder invertColors() {
                mMatrixList.add(ColorFilterUtils.invertColors());
                return this;
            }

            Builder tint(int color) {
                mMatrixList.add(applyTint(color));
                return this;
            }

            @Nullable
            ColorMatrix build() {
                if (mMatrixList.isEmpty()) return null;

                ColorMatrix colorMatrix = new ColorMatrix();
                for (ColorMatrix cm : mMatrixList) {
                    colorMatrix.postConcat(cm);
                }
                return colorMatrix;
            }
        }
    }
}
