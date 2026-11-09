/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.DefaultScope;

import ru.citcer.uuidcheck.core.IntraFileMode;
import ru.citcer.uuidcheck.core.UuidPreferences;

/** Значения по умолчанию (instance scope). */
public final class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(UuidCheckPlugin.PLUGIN_ID);
        node.put(UuidPreferences.SCAN_ENABLED, Boolean.TRUE.toString());
        node.put(UuidPreferences.SCAN_EXTENSIONS, UuidPreferences.DEFAULT_EXTENSIONS);
        node.put(UuidPreferences.SCAN_EXCLUDE_DIRS, UuidPreferences.DEFAULT_EXCLUDE_DIRS);
        node.put(UuidPreferences.SCAN_INCLUDE_LINKED, Boolean.FALSE.toString());
        node.put(UuidPreferences.SCAN_DEBOUNCE_MS, "750");
        node.put(UuidPreferences.SCAN_ON_STARTUP, Boolean.TRUE.toString());
        node.put(UuidPreferences.SCAN_DIRTY_THRESHOLD, "200");
        node.put(UuidPreferences.SCAN_REPORT_PARSE_ERRORS, Boolean.FALSE.toString());
        node.put(UuidPreferences.SEVERITY_CROSS, "WARNING");
        node.put(UuidPreferences.MARKERS_MAX, "500");
        node.put(UuidPreferences.INTRA_FILE_MODE, IntraFileMode.WARN.name());
        node.put(UuidPreferences.EXPORT_CSV_BOM, Boolean.FALSE.toString());
        node.put(UuidPreferences.EXPORT_CSV_SEPARATOR, ";");
        node.put(UuidPreferences.EXPORT_REPORT_WHEN_EMPTY, Boolean.FALSE.toString());
        node.put(UuidPreferences.DIALOG_ON_NEW_DUPLICATES, Boolean.FALSE.toString());
    }
}
