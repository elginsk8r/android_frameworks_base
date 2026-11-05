/*
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
import android.content.om.OverlayIdentifier;
import android.content.pm.ThemeUtils;
import android.content.res.Resources;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.Xml;

import com.android.server.om.OverlayManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Compiles the uncompiled overlay resources of cm-13.0 theme packages
 * ({@code assets/overlays/<target>/res}) into overlays with aapt2, through the overlay manager.
 *
 * <p>cm-13.0 compiled these with a patched aapt that also resolved references to resources of
 * the target app and of the theme's shared "common" resources. The sources are adjusted to what
 * a regular overlay supports: common resources are merged into each overlay, every values
 * resource is compiled on its own and resources aapt2 rejects, typically ones referencing
 * private resources of an app, are dropped.
 */
final class LegacyOverlayCompiler {
    private static final String TAG = "LegacyOverlayCompiler";

    private static final String ANDROID_PACKAGE = "android";
    private static final String RES_PREFIX = "res/";
    private static final int MAX_ATTEMPTS = 16;

    private static final Pattern VALUES_FILE = Pattern.compile("res/values[^/]*/[^/]+\\.xml");
    // Resources of the shared "common" directory of a cm-13.0 theme were a separate package,
    // referenced as @*common:type/name. They are compiled into each overlay under this prefix,
    // so they can neither collide with nor overlay resources of the target.
    private static final String COMMON_PREFIX = "common__";
    private static final String COMMON_PACKAGE = "common";
    // Resource references: sigil, private marker, package, create marker, type and name
    private static final Pattern REFERENCE = Pattern.compile(
            "([@?])(\\*?)(?:([A-Za-z0-9_.]+):)?(\\+?)([a-z-]+)/([A-Za-z0-9_.]+)");
    // aapt2 has no private marker for attribute references, which the aapt of cm-13.0 accepted
    private static final Pattern PRIVATE_ATTRIBUTE = Pattern.compile("\\?\\*(?=[A-Za-z0-9_.]+:)");
    // Source files named in aapt2 diagnostics, relative to the compile directory
    private static final Pattern DIAGNOSTIC_SOURCE =
            Pattern.compile("/compile-[^/]+/(res/[^:\\s]+)");

    private final OverlayManagerInternal mOverlayManager;
    private final File mWorkDir;

    LegacyOverlayCompiler(@NonNull OverlayManagerInternal overlayManager, @NonNull File workDir) {
        mOverlayManager = overlayManager;
        mWorkDir = workDir;
    }

    /** @return the overlay directories of a cm-13.0 theme package, excluding "common". */
    @NonNull
    static List<String> getTargets(@NonNull String themeApk) {
        final Set<String> targets = new ArraySet<>();
        final String prefix = "assets/" + ThemeUtils.LEGACY_OVERLAYS_PATH + "/";
        try (ZipFile zip = new ZipFile(themeApk)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                final int end = name.indexOf('/', prefix.length());
                if (end > prefix.length()
                        && name.startsWith("/" + RES_PREFIX, end)) {
                    targets.add(name.substring(prefix.length(), end));
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "Unable to read " + themeApk, e);
        }
        targets.remove(ThemeUtils.LEGACY_COMMON_TARGET);
        return new ArrayList<>(targets);
    }

    /**
     * Compiles and registers the overlay for one target directory of a cm-13.0 theme.
     *
     * @param owningPackage the owner of the registered overlay
     * @param overlayName the name of the registered overlay
     * @param themeApk the path of the theme package
     * @param legacyTarget the directory in assets/overlays to compile
     * @return the registered overlay, or null if nothing could be compiled
     */
    @Nullable
    OverlayIdentifier compile(@NonNull String owningPackage, @NonNull String overlayName,
            @NonNull String themeApk, @NonNull String legacyTarget) {
        final String target = ThemeUtils.getLegacyOverlayTargetPackage(legacyTarget);
        final Map<String, byte[]> sources;
        try {
            sources = readSources(themeApk, legacyTarget, target);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Unable to read overlay sources of " + legacyTarget + " in " + themeApk,
                    e);
            return null;
        }

        mWorkDir.mkdirs();
        final File zip = new File(mWorkDir, overlayName + ".zip");
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS && !sources.isEmpty(); attempt++) {
                writeZip(zip, sources);
                try (ParcelFileDescriptor fd =
                        ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    return mOverlayManager.registerCompiledOverlay(owningPackage, overlayName,
                            target, fd);
                } catch (IllegalStateException e) {
                    final Set<String> rejected = getRejectedSources(e.getMessage());
                    rejected.retainAll(sources.keySet());
                    if (rejected.isEmpty()) {
                        Slog.e(TAG, "Unable to compile " + legacyTarget + " of " + themeApk, e);
                        return null;
                    }
                    Slog.w(TAG, "Dropping resources of " + legacyTarget + " aapt2 rejected: "
                            + rejected);
                    sources.keySet().removeAll(rejected);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to compile " + legacyTarget + " of " + themeApk, e);
        } finally {
            zip.delete();
        }
        return null;
    }

    @NonNull
    private static Set<String> getRejectedSources(@Nullable String diagnostics) {
        final Set<String> sources = new ArraySet<>();
        if (diagnostics == null) {
            return sources;
        }
        final Matcher matcher = DIAGNOSTIC_SOURCE.matcher(diagnostics);
        while (matcher.find()) {
            sources.add(matcher.group(1));
        }
        return sources;
    }

    private static void writeZip(@NonNull File file, @NonNull Map<String, byte[]> sources)
            throws IOException {
        try (FileOutputStream out = new FileOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        FileUtils.setPermissions(file, 0600, -1, -1);
    }

    /**
     * Reads the resources of a target directory and the common resources, as entries of a res/
     * tree.
     */
    @NonNull
    private static Map<String, byte[]> readSources(@NonNull String themeApk,
            @NonNull String legacyTarget, @NonNull String target)
            throws IOException, XmlPullParserException {
        final Map<String, byte[]> commonFiles = new ArrayMap<>();
        final Map<String, byte[]> targetFiles = new ArrayMap<>();
        try (ZipFile zip = new ZipFile(themeApk)) {
            readDirectory(zip, ThemeUtils.LEGACY_COMMON_TARGET, commonFiles);
            readDirectory(zip, legacyTarget, targetFiles);
        }

        final Scope common = new Scope(target, true);
        for (Map.Entry<String, byte[]> entry : commonFiles.entrySet()) {
            if (VALUES_FILE.matcher(entry.getKey()).matches()) {
                collectResources(entry.getValue(), common.resources, common.styles);
            } else {
                common.resources.add(getFileResource(entry.getKey()));
            }
        }
        final Scope overlay = new Scope(target, false);
        for (Map.Entry<String, byte[]> entry : targetFiles.entrySet()) {
            if (VALUES_FILE.matcher(entry.getKey()).matches()) {
                collectResources(entry.getValue(), null, overlay.styles);
            }
        }

        final Map<String, byte[]> sources = new ArrayMap<>();
        addSources(targetFiles, overlay, sources);
        addSources(commonFiles, common, sources);
        return sources;
    }

    private static void addSources(@NonNull Map<String, byte[]> files, @NonNull Scope scope,
            @NonNull Map<String, byte[]> sources) throws IOException, XmlPullParserException {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (VALUES_FILE.matcher(path).matches()) {
                splitValues(path, entry.getValue(), scope, sources);
                continue;
            }
            if (scope.isCommon) {
                // res/<type>/<name>.<ext> -> res/<type>/common__<name>.<ext>
                final int slash = path.lastIndexOf('/');
                path = path.substring(0, slash + 1) + COMMON_PREFIX + path.substring(slash + 1);
            }
            if (path.endsWith(".xml")) {
                sources.put(path, scope.rewriteReferences(
                        new String(entry.getValue(), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8));
            } else {
                sources.put(path, entry.getValue());
            }
        }
    }

    /** @return "type/name" of the resource defined by a file. */
    @NonNull
    private static String getFileResource(@NonNull String path) {
        // res/<type>[-<qualifiers>]/<name>.<ext>
        final String[] parts = path.split("/");
        final String type = parts[1].split("-")[0];
        final String name = parts[parts.length - 1].split("\\.")[0];
        return type + "/" + name;
    }

    private static void readDirectory(@NonNull ZipFile zip, @NonNull String legacyTarget,
            @NonNull Map<String, byte[]> files) throws IOException {
        final String prefix = "assets/" + ThemeUtils.LEGACY_OVERLAYS_PATH + "/" + legacyTarget
                + "/" + RES_PREFIX;
        final Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            final String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(prefix) || name.contains("..")) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                files.put(RES_PREFIX + name.substring(prefix.length()), in.readAllBytes());
            }
        }
    }

    /**
     * Collects the resources a values file defines.
     *
     * @param resources receives "type/name" of every resource, if not null
     * @param styles receives the names of the styles
     */
    private static void collectResources(@NonNull byte[] data, @Nullable Set<String> resources,
            @NonNull Set<String> styles) throws IOException, XmlPullParserException {
        final XmlPullParser parser = newParser(data);
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG || parser.getDepth() != 2) {
                continue;
            }
            final String name = parser.getAttributeValue(null, "name");
            if (name == null) {
                continue;
            }
            if ("style".equals(parser.getName())) {
                styles.add(name);
            }
            if (resources != null) {
                resources.add(getValueType(parser) + "/" + name);
            }
        }
    }

    @NonNull
    private static String getValueType(@NonNull XmlPullParser parser) {
        final String tag = parser.getName();
        switch (tag) {
            case "item":
                final String type = parser.getAttributeValue(null, "type");
                return type != null ? type : tag;
            case "string-array":
            case "integer-array":
                return "array";
            case "declare-styleable":
                return "styleable";
            default:
                return tag;
        }
    }

    /**
     * Splits a values file into one file per resource, so a resource aapt2 rejects can be
     * dropped without losing the rest of the file.
     */
    private static void splitValues(@NonNull String path, @NonNull byte[] data,
            @NonNull Scope scope, @NonNull Map<String, byte[]> sources)
            throws IOException, XmlPullParserException {
        final String base = path.substring(0, path.length() - ".xml".length())
                + (scope.isCommon ? "_" + COMMON_PACKAGE : "");
        final XmlPullParser parser = newParser(data);

        // Keep the namespace declarations of <resources>, e.g. xliff
        StringBuilder rootAttributes = null;
        int index = 0;
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            if (parser.getDepth() == 1) {
                rootAttributes = new StringBuilder();
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    appendAttribute(rootAttributes, parser.getAttributeName(i),
                            parser.getAttributeValue(i));
                }
                continue;
            }
            if (parser.getDepth() != 2) {
                continue;
            }

            final StringBuilder resource = new StringBuilder();
            if (!copyResource(parser, scope, resource)) {
                continue;
            }
            final String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources"
                    + (rootAttributes != null ? rootAttributes : "") + ">\n"
                    + resource + "\n</resources>\n";
            sources.put(base + "_" + index++ + ".xml", xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Copies the resource element the parser is at, and its children.
     *
     * @return false if the resource cannot be compiled as an overlay and is skipped
     */
    private static boolean copyResource(@NonNull XmlPullParser parser, @NonNull Scope scope,
            @NonNull StringBuilder out) throws IOException, XmlPullParserException {
        final int depth = parser.getDepth();
        String parent = null;
        if ("style".equals(parser.getName()) && parser.getAttributeValue(null, "parent") == null) {
            parent = getImplicitParent(parser.getAttributeValue(null, "name"), scope);
            if (parent == null) {
                // The style inherits from a style of the target app, which an overlay cannot
                // reference. Keep the app's own style rather than replacing it with one that
                // lacks everything it inherits.
                skip(parser);
                return false;
            }
        }

        int type = XmlPullParser.START_TAG;
        do {
            switch (type) {
                case XmlPullParser.START_TAG:
                    out.append('<').append(parser.getName());
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        final String name = parser.getAttributeName(i);
                        final String value = parser.getAttributeValue(i);
                        if (parser.getDepth() == depth && "name".equals(name)) {
                            appendAttribute(out, name, scope.getResourceName(value));
                        } else {
                            appendAttribute(out, name, scope.rewriteReferences(value));
                        }
                    }
                    if (parent != null && parser.getDepth() == depth) {
                        appendAttribute(out, "parent", parent);
                    }
                    out.append('>');
                    break;
                case XmlPullParser.END_TAG:
                    out.append("</").append(parser.getName()).append('>');
                    break;
                case XmlPullParser.TEXT:
                    out.append(escape(scope.rewriteReferences(parser.getText()), false));
                    break;
                default:
                    break;
            }
            if (type == XmlPullParser.END_TAG && parser.getDepth() == depth) {
                break;
            }
            type = parser.next();
        } while (type != XmlPullParser.END_DOCUMENT);
        return true;
    }

    /**
     * @return the explicit parent for a style relying on the parent implied by its dotted name,
     *         "" if it has none, or null if the parent cannot be referenced from an overlay
     */
    @Nullable
    private static String getImplicitParent(@Nullable String name, @NonNull Scope scope) {
        // Common styles are new resources of the overlay rather than replacements of the
        // target's, so not inheriting anything is fine for them.
        final boolean isFramework = ANDROID_PACKAGE.equals(scope.target);
        final int dot = name != null ? name.lastIndexOf('.') : -1;
        if (dot <= 0) {
            // An app's style may inherit from a parent we cannot see, only framework styles
            // without a dotted name are known to have none.
            return isFramework || scope.isCommon ? "" : null;
        }
        final String implicitParent = name.substring(0, dot);
        if (scope.styles.contains(implicitParent)) {
            return "@style/" + scope.getResourceName(implicitParent);
        }
        if (isFramework && Resources.getSystem().getIdentifier(
                implicitParent, "style", ANDROID_PACKAGE) != 0) {
            return "@*android:style/" + implicitParent;
        }
        return scope.isCommon ? "" : null;
    }

    private static void skip(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.END_TAG && parser.getDepth() == depth) {
                return;
            }
        }
    }

    private static void appendAttribute(@NonNull StringBuilder out, @NonNull String name,
            @NonNull String value) {
        out.append(' ').append(name).append("=\"").append(escape(value, true)).append('"');
    }

    @NonNull
    private static String escape(@NonNull String value, boolean attribute) {
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append(attribute ? "&quot;" : "\"");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    @NonNull
    private static XmlPullParser newParser(@NonNull byte[] data) throws XmlPullParserException {
        final XmlPullParser parser = Xml.newPullParser();
        // Keep prefixed names such as xliff:g and xmlns declarations as they are
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new ByteArrayInputStream(data), null);
        return parser;
    }

    /** Where a resource file of a cm-13.0 theme comes from: the target or the common directory. */
    private static final class Scope {
        final String target;
        final boolean isCommon;
        // "type/name" of the common resources
        final Set<String> resources = new ArraySet<>();
        // Names of the styles defined in this scope
        final Set<String> styles = new ArraySet<>();

        Scope(@NonNull String target, boolean isCommon) {
            this.target = target;
            this.isCommon = isCommon;
        }

        /** @return the name a resource defined in this scope is compiled as */
        @NonNull
        String getResourceName(@NonNull String name) {
            return isCommon ? COMMON_PREFIX + name : name;
        }

        /** Rewrites references to common resources, and private attribute references. */
        @NonNull
        String rewriteReferences(@NonNull String value) {
            final String text = PRIVATE_ATTRIBUTE.matcher(value).replaceAll("?");
            final Matcher matcher = REFERENCE.matcher(text);
            final StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                final String pkg = matcher.group(3);
                final boolean create = !matcher.group(4).isEmpty();
                final String type = matcher.group(5);
                final String name = matcher.group(6);
                // Common resources refer to each other without a package
                final boolean isCommonReference = COMMON_PACKAGE.equals(pkg)
                        || (pkg == null && isCommon && resources.contains(type + "/" + name));
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        isCommonReference && !create
                                ? matcher.group(1) + type + "/" + COMMON_PREFIX + name
                                : matcher.group(0)));
            }
            matcher.appendTail(out);
            return out.toString();
        }
    }
}
