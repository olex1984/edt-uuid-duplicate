/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.UuidPreferences;

/** Настройки уровня рабочего пространства. */
public final class UuidCheckPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public UuidCheckPreferencePage() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, UuidCheckPlugin.PLUGIN_ID));
        setDescription("Параметры поиска дубликатов UUID в проектах 1С:EDT.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // страница не зависит от конкретного окна workbench
    }

    @Override
    protected void createFieldEditors() {
        Composite parent = getFieldEditorParent();
        addField(new BooleanFieldEditor(UuidPreferences.SCAN_ENABLED, Messages.PrefsEnabled, parent));
        addField(new StringFieldEditor(UuidPreferences.SCAN_EXTENSIONS, Messages.PrefsExtensions, parent));
        addField(new StringFieldEditor(UuidPreferences.SCAN_EXCLUDE_DIRS, Messages.PrefsExcludeDirs, parent));
        addField(new BooleanFieldEditor(UuidPreferences.SCAN_INCLUDE_LINKED, Messages.PrefsIncludeLinked, parent));
        addField(new IntegerFieldEditor(UuidPreferences.SCAN_DEBOUNCE_MS, Messages.PrefsDebounce, parent));
        addField(new BooleanFieldEditor(UuidPreferences.SCAN_ON_STARTUP, Messages.PrefsOnStartup, parent));
        addField(new IntegerFieldEditor(UuidPreferences.SCAN_DIRTY_THRESHOLD, Messages.PrefsDirtyThreshold, parent));
        addField(new BooleanFieldEditor(UuidPreferences.SCAN_REPORT_PARSE_ERRORS, Messages.PrefsReportParseErrors, parent));

        addField(new ComboFieldEditor(UuidPreferences.SEVERITY_CROSS, Messages.PrefsSeverityCross,
                new String[][] {
                        {Messages.SeverityWarning, "WARNING"},
                        {Messages.SeverityError, "ERROR"}
                }, parent));
        addField(new IntegerFieldEditor(UuidPreferences.MARKERS_MAX, Messages.PrefsMarkersMax, parent));
        addField(new ComboFieldEditor(UuidPreferences.INTRA_FILE_MODE, Messages.PrefsIntraMode,
                new String[][] {
                        {Messages.IntraIgnore, "IGNORE"},
                        {Messages.IntraWarn, "WARN"},
                        {Messages.IntraError, "ERROR"}
                }, parent));

        addField(new BooleanFieldEditor(UuidPreferences.EXPORT_CSV_BOM, Messages.PrefsCsvBom, parent));
        addField(new StringFieldEditor(UuidPreferences.EXPORT_CSV_SEPARATOR, Messages.PrefsCsvSeparator,
                parent));
        addField(new BooleanFieldEditor(UuidPreferences.EXPORT_REPORT_WHEN_EMPTY,
                Messages.PrefsReportWhenEmpty, parent));
        addField(new BooleanFieldEditor(UuidPreferences.DIALOG_ON_NEW_DUPLICATES, Messages.PrefsDialog, parent));
    }
}
