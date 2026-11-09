/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import ru.citcer.uuidcheck.UuidCheckPlugin;

/** Чтение настроек: project scope → instance scope → значение по умолчанию. */
public final class UuidPreferences {

    public static final String SCAN_ENABLED = "scan.enabled";
    public static final String SCAN_EXTENSIONS = "scan.extensions";
    public static final String SCAN_EXCLUDE_DIRS = "scan.excludeDirs";
    public static final String SCAN_INCLUDE_LINKED = "scan.includeLinked";
    public static final String SCAN_DEBOUNCE_MS = "scan.debounceMs";
    public static final String SCAN_ON_STARTUP = "scan.onStartup";
    public static final String SCAN_DIRTY_THRESHOLD = "scan.dirtyFileThreshold";
    public static final String SCAN_REPORT_PARSE_ERRORS = "scan.reportParseErrors";
    public static final String SEVERITY_CROSS = "severity.crossFile";
    public static final String MARKERS_MAX = "markers.max";
    public static final String INTRA_FILE_MODE = "intraFile.mode";
    public static final String EXPORT_CSV_BOM = "export.csvBom";
    public static final String EXPORT_CSV_SEPARATOR = "export.csvSeparator";
    public static final String EXPORT_REPORT_WHEN_EMPTY = "export.reportWhenEmpty";
    public static final String DIALOG_ON_NEW_DUPLICATES = "dialog.onNewDuplicates";

    public static final String DEFAULT_EXTENSIONS = "mdo,xml";
    public static final String DEFAULT_EXCLUDE_DIRS = ".git,.settings,out,build,bin,target,node_modules,.idea";

    public boolean enabled(IProject project) {
        return getBoolean(project, SCAN_ENABLED, true);
    }

    public Set<String> extensions(IProject project) {
        return parseSet(getString(project, SCAN_EXTENSIONS, DEFAULT_EXTENSIONS));
    }

    public Set<String> excludeDirs() {
        return parseSet(getString(null, SCAN_EXCLUDE_DIRS, DEFAULT_EXCLUDE_DIRS));
    }

    public boolean includeLinked() {
        return getBoolean(null, SCAN_INCLUDE_LINKED, false);
    }

    public int debounceMs() {
        return getInt(null, SCAN_DEBOUNCE_MS, 750);
    }

    public boolean onStartup() {
        return getBoolean(null, SCAN_ON_STARTUP, true);
    }

    public int dirtyFileThreshold() {
        return getInt(null, SCAN_DIRTY_THRESHOLD, 200);
    }

    public boolean reportParseErrors() {
        return getBoolean(null, SCAN_REPORT_PARSE_ERRORS, false);
    }

    public int severityCross() {
        return "ERROR".equalsIgnoreCase(getString(null, SEVERITY_CROSS, "WARNING"))
                ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING;
    }

    public int markersMax() {
        return getInt(null, MARKERS_MAX, 500);
    }

    public IntraFileMode intraFileMode(IProject project) {
        return IntraFileMode.parse(getString(project, INTRA_FILE_MODE, IntraFileMode.WARN.name()));
    }

    public boolean csvBom() {
        return getBoolean(null, EXPORT_CSV_BOM, false);
    }

    public char csvSeparator() {
        String value = getString(null, EXPORT_CSV_SEPARATOR, ";");
        return value.isEmpty() ? ';' : value.charAt(0);
    }

    public boolean reportWhenEmpty() {
        return getBoolean(null, EXPORT_REPORT_WHEN_EMPTY, false);
    }

    public boolean dialogOnNewDuplicates() {
        return getBoolean(null, DIALOG_ON_NEW_DUPLICATES, false);
    }

    public String getString(IProject project, String key, String defaultValue) {
        if (project != null) {
            String value = projectNode(project).get(key, null);
            if (value != null) {
                return value;
            }
        }
        return instanceNode().get(key, defaultValue);
    }

    public boolean getBoolean(IProject project, String key, boolean defaultValue) {
        return Boolean.parseBoolean(getString(project, key, Boolean.toString(defaultValue)));
    }

    public int getInt(IProject project, String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(project, key, Integer.toString(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void setProjectString(IProject project, String key, String value) {
        try {
            projectNode(project).put(key, value);
            projectNode(project).flush();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            UuidCheckPlugin.logError("Не удалось сохранить настройки проекта", e);
        }
    }

    private static IEclipsePreferences projectNode(IProject project) {
        return new ProjectScope(project).getNode(UuidCheckPlugin.PLUGIN_ID);
    }

    private static IEclipsePreferences instanceNode() {
        return InstanceScope.INSTANCE.getNode(UuidCheckPlugin.PLUGIN_ID);
    }

    private static Set<String> parseSet(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
