/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.UuidPreferences;
import ru.citcer.uuidcheck.export.CsvExporter;
import ru.citcer.uuidcheck.export.TextReportExporter;

/** Общая логика экспорта для команд и view. */
final class ExportSupport {

    private static final String CSV_NAME = "uuid_duplicates_posthandlings.csv";
    private static final String REPORT_NAME = "uuid_report.txt";

    private ExportSupport() {
    }

    static void exportCsv(Shell shell, IProject project) {
        ExportData data = prepare(project);
        if (data == null) {
            return;
        }
        String path = chooseFile(shell, Messages.ExportCsvTitle, CSV_NAME, "*.csv");
        if (path == null) {
            return;
        }
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        try {
            new CsvExporter().export(new Path(path), data.groups(),
                    new CsvExporter.Options()
                            .bom(preferences.csvBom())
                            .separator(preferences.csvSeparator()));
            info(shell, MessageFormat.format(Messages.ExportDone, path));
        } catch (IOException e) {
            error(shell, MessageFormat.format(Messages.ErrorExport, e.getMessage()));
        }
    }

    static void exportReport(Shell shell, IProject project) {
        ExportData data = prepare(project);
        if (data == null) {
            return;
        }
        String path = chooseFile(shell, Messages.ExportReportTitle, REPORT_NAME, "*.txt");
        if (path == null) {
            return;
        }
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        try {
            new TextReportExporter().export(new Path(path), data.snapshot(), data.groups(),
                    preferences.reportWhenEmpty());
            info(shell, MessageFormat.format(Messages.ExportDone, path));
        } catch (IOException e) {
            error(shell, MessageFormat.format(Messages.ErrorExport, e.getMessage()));
        }
    }

    private record ExportData(IndexSnapshot snapshot, List<DuplicateGroup> groups) {
    }

    private static ExportData prepare(IProject project) {
        if (project == null) {
            UuidCheckPlugin.showInformation(Messages.ErrorNoProject);
            return null;
        }
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        IndexSnapshot snapshot = service == null ? null : service.snapshot(project);
        if (snapshot == null) {
            UuidCheckPlugin.showInformation(Messages.ErrorNoProject);
            return null;
        }
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        List<DuplicateGroup> groups = new DuplicateAnalyzer()
                .analyze(snapshot, preferences.intraFileMode(project));
        return new ExportData(snapshot, groups);
    }

    private static String chooseFile(Shell shell, String title, String defaultName, String pattern) {
        if (shell == null) {
            return null;
        }
        FileDialog dialog = new FileDialog(shell, SWT.SAVE);
        dialog.setText(title);
        dialog.setFileName(defaultName);
        dialog.setFilterExtensions(new String[] {pattern});
        return dialog.open();
    }

    private static void info(Shell shell, String message) {
        if (shell == null) {
            UuidCheckPlugin.showInformation(message);
        } else {
            MessageDialog.openInformation(shell, Messages.ErrorTitle, message);
        }
    }

    private static void error(Shell shell, String message) {
        if (shell == null) {
            UuidCheckPlugin.showInformation(message);
        } else {
            MessageDialog.openError(shell, Messages.ErrorTitle, message);
        }
    }
}
