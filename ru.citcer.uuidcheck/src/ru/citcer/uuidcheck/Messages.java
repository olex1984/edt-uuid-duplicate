/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck;

import org.eclipse.osgi.util.NLS;

/** NLS-строки интерфейса. Файл — {@code messages.properties} в корне бандла. */
public final class Messages extends NLS {

    private static final String BUNDLE_NAME = "ru.citcer.uuidcheck.messages";

    public static String ViewColumnUuid;
    public static String ViewColumnFiles;
    public static String ViewColumnOccurrences;
    public static String ViewColumnKind;
    public static String ViewColumnLocations;
    public static String ViewColumnProject;
    public static String ViewEmptyText;
    public static String ViewFilterHint;
    public static String KindCrossFile;
    public static String KindIntraFile;
    public static String MarkerMessage;
    public static String MarkerIntraMessage;
    public static String ActionOpen;
    public static String ActionCopyUuid;
    public static String ActionExportCsv;
    public static String ActionExportReport;
    public static String ActionExpandAll;
    public static String ActionCollapseAll;
    public static String ActionOpenFile;
    public static String ScanJobName;
    public static String ErrorTitle;
    public static String ErrorNoProject;
    public static String ErrorNotEdt;
    public static String ErrorExport;
    public static String ExportCsvTitle;
    public static String ExportReportTitle;
    public static String ExportDone;
    public static String DialogNewDuplicatesTitle;
    public static String DialogNewDuplicatesMessage;
    public static String DialogNewDuplicatesAdded;
    public static String PrefsScanGroup;
    public static String PrefsEnabled;
    public static String PrefsExtensions;
    public static String PrefsExcludeDirs;
    public static String PrefsIncludeLinked;
    public static String PrefsDebounce;
    public static String PrefsOnStartup;
    public static String PrefsDirtyThreshold;
    public static String PrefsReportParseErrors;
    public static String PrefsGroupsMarkers;
    public static String PrefsSeverityCross;
    public static String PrefsMarkersMax;
    public static String PrefsIntraMode;
    public static String PrefsExportGroup;
    public static String PrefsCsvBom;
    public static String PrefsCsvSeparator;
    public static String PrefsReportWhenEmpty;
    public static String PrefsDialog;
    public static String IntraIgnore;
    public static String IntraWarn;
    public static String IntraError;
    public static String SeverityWarning;
    public static String SeverityError;
    public static String PropertyEnabled;
    public static String PropertyExtensions;
    public static String PropertyIntraMode;
    public static String StatusIndexed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
