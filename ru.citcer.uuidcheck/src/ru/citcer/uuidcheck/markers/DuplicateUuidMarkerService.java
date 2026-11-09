/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.markers;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.DuplicateKind;
import ru.citcer.uuidcheck.core.FileIndexEntry;
import ru.citcer.uuidcheck.core.IndexChange;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IntraFileMode;
import ru.citcer.uuidcheck.core.IUuidIndexListener;
import ru.citcer.uuidcheck.core.UuidIndexService;
import ru.citcer.uuidcheck.core.UuidPreferences;
import ru.citcer.uuidcheck.scan.UuidOccurrence;

/** Создание/удаление маркеров Problems view по данным индекса. */
public final class DuplicateUuidMarkerService implements IUuidIndexListener {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final UuidIndexService service;
    private final UuidPreferences preferences;
    private final DuplicateAnalyzer analyzer = new DuplicateAnalyzer();
    private final Map<IProject, Set<String>> knownDuplicates = new ConcurrentHashMap<>();

    public DuplicateUuidMarkerService(UuidIndexService service, UuidPreferences preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    @Override
    public void indexChanged(IndexChange change) {
        IProject project = change.project();
        if (project == null || !project.isAccessible()) {
            if (project != null) {
                deleteAll(project);
                knownDuplicates.remove(project);
            }
            return;
        }
        IndexSnapshot snapshot = service.snapshot(project);
        if (snapshot == null) {
            deleteAll(project);
            knownDuplicates.remove(project);
            return;
        }
        List<DuplicateGroup> groups = analyzer.analyze(snapshot, preferences.intraFileMode(project));
        boolean full = change.fullRescan() || groups.size() > preferences.markersMax();
        update(project, snapshot, groups, full ? null : change.changedFiles());
        reportNewDuplicates(project, groups);
    }

    /** Полный пересчёт маркеров проекта. */
    public void refreshProject(IProject project) {
        IndexSnapshot snapshot = service.snapshot(project);
        if (snapshot == null) {
            deleteAll(project);
            return;
        }
        List<DuplicateGroup> groups = analyzer.analyze(snapshot, preferences.intraFileMode(project));
        update(project, snapshot, groups, null);
    }

    private void update(IProject project, IndexSnapshot snapshot,
                        List<DuplicateGroup> groups, Set<IPath> affectedFiles) {
        if (affectedFiles == null) {
            deleteAll(project);
        } else {
            for (IPath path : affectedFiles) {
                IFile file = project.getFile(path);
                if (file.exists()) {
                    deleteOn(file);
                }
            }
        }

        int max = preferences.markersMax();
        int created = 0;
        for (DuplicateGroup group : groups) {
            if (created >= max) {
                addSummaryMarker(project, groups.size() - created);
                break;
            }
            created++;
            if (affectedFiles != null && !touches(group, affectedFiles)) {
                continue;
            }
            createForGroup(project, group);
        }

        if (preferences.reportParseErrors()) {
            createParseErrorMarkers(project, snapshot, affectedFiles);
        }
    }

    private static boolean touches(DuplicateGroup group, Set<IPath> affectedFiles) {
        for (UuidOccurrence occurrence : group.occurrences()) {
            if (affectedFiles.contains(occurrence.file())) {
                return true;
            }
        }
        return false;
    }

    private void createForGroup(IProject project, DuplicateGroup group) {
        int severity = severity(project, group);
        for (UuidOccurrence occurrence : group.occurrences()) {
            IFile file = project.getFile(occurrence.file());
            if (!file.exists()) {
                continue;
            }
            try {
                IMarker marker = file.createMarker(DuplicateUuidMarker.TYPE);
                marker.setAttribute(IMarker.MESSAGE, message(group, occurrence));
                marker.setAttribute(IMarker.SEVERITY, severity);
                marker.setAttribute(IMarker.LINE_NUMBER, Math.max(1, occurrence.line()));
                marker.setAttribute(IMarker.CHAR_START, Math.max(0, occurrence.charStart()));
                marker.setAttribute(IMarker.CHAR_END,
                        Math.max(occurrence.charStart() + 1, occurrence.charEnd()));
                marker.setAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
                marker.setAttribute(DuplicateUuidMarker.ATTR_UUID, group.uuid());
                marker.setAttribute(DuplicateUuidMarker.ATTR_ELEMENT_TYPE, occurrence.elementType());
                marker.setAttribute(DuplicateUuidMarker.ATTR_KIND, group.kind().name());
                marker.setAttribute(DuplicateUuidMarker.ATTR_DUPLICATE_FILES, otherFiles(group, occurrence));
            } catch (CoreException e) {
                UuidCheckPlugin.logError("Не удалось создать маркер в " + occurrence.file(), e);
            }
        }
    }

    private void createParseErrorMarkers(IProject project, IndexSnapshot snapshot,
                                         Set<IPath> affectedFiles) {
        for (Map.Entry<IPath, FileIndexEntry> entry : snapshot.byFile().entrySet()) {
            FileIndexEntry fileEntry = entry.getValue();
            if (!fileEntry.failed()) {
                continue;
            }
            if (affectedFiles != null && !affectedFiles.contains(entry.getKey())) {
                continue;
            }
            IFile file = project.getFile(entry.getKey());
            if (!file.exists()) {
                continue;
            }
            try {
                IMarker marker = file.createMarker(DuplicateUuidMarker.TYPE);
                marker.setAttribute(IMarker.MESSAGE,
                        "Файл не разобран: " + fileEntry.failure());
                marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
                marker.setAttribute(IMarker.LINE_NUMBER, 1);
                marker.setAttribute(DuplicateUuidMarker.ATTR_KIND, "PARSE_ERROR");
            } catch (CoreException e) {
                UuidCheckPlugin.logError("Не удалось создать маркер ошибки разбора " + entry.getKey(), e);
            }
        }
    }

    private int severity(IProject project, DuplicateGroup group) {
        if (group.kind() == DuplicateKind.CROSS_FILE) {
            return preferences.severityCross();
        }
        IntraFileMode mode = preferences.intraFileMode(project);
        return mode == IntraFileMode.ERROR ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING;
    }

    private String message(DuplicateGroup group, UuidOccurrence occurrence) {
        String message;
        if (group.kind() == DuplicateKind.CROSS_FILE) {
            message = Messages.MarkerMessage
                    .replace("{0}", group.uuid())
                    .replace("{1}", Integer.toString(group.fileCount()))
                    .replace("{2}", otherFiles(group, occurrence));
        } else {
            message = Messages.MarkerIntraMessage
                    .replace("{0}", group.uuid())
                    .replace("{1}", Integer.toString(group.occurrenceCount()))
                    .replace("{2}", occurrence.fileName());
        }
        return message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
    }

    private static String otherFiles(DuplicateGroup group, UuidOccurrence occurrence) {
        Set<String> others = new LinkedHashSet<>();
        for (UuidOccurrence other : group.occurrences()) {
            if (!other.file().equals(occurrence.file())) {
                others.add(other.file() + ":" + other.line());
            }
        }
        List<String> list = new ArrayList<>(others);
        return String.join(", ", list);
    }

    private void addSummaryMarker(IProject project, int remaining) {
        try {
            IMarker marker = project.createMarker(DuplicateUuidMarker.TYPE);
            marker.setAttribute(IMarker.MESSAGE,
                    "Показаны не все дубли: ещё групп — " + remaining + ". Откройте view «Дубли UUID».");
            marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
        } catch (CoreException e) {
            UuidCheckPlugin.logError("Не удалось создать сводный маркер", e);
        }
    }

    /** Диалог о появившихся новых дублях (опция, по умолчанию выключена). */
    private void reportNewDuplicates(IProject project, List<DuplicateGroup> groups) {
        Set<String> current = new HashSet<>();
        for (DuplicateGroup group : groups) {
            if (group.kind() == DuplicateKind.CROSS_FILE) {
                current.add(group.uuid());
            }
        }
        Set<String> previous = knownDuplicates.put(project, current);
        if (previous == null || !preferences.dialogOnNewDuplicates()) {
            return;
        }
        Set<String> added = new HashSet<>(current);
        added.removeAll(previous);
        if (added.isEmpty()) {
            return;
        }
        String message = MessageFormat.format(Messages.DialogNewDuplicatesAdded,
                project.getName(), added.size());
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            MessageDialog.openInformation(window == null ? null : window.getShell(),
                    Messages.DialogNewDuplicatesTitle, message);
        });
    }

    private void deleteAll(IProject project) {
        try {
            if (project.exists()) {
                project.deleteMarkers(DuplicateUuidMarker.TYPE, true, IResource.DEPTH_INFINITE);
            }
        } catch (CoreException e) {
            UuidCheckPlugin.logError("Не удалось удалить маркеры проекта", e);
        }
    }

    private void deleteOn(IFile file) {
        try {
            file.deleteMarkers(DuplicateUuidMarker.TYPE, true, IResource.DEPTH_ZERO);
        } catch (CoreException e) {
            UuidCheckPlugin.logError("Не удалось удалить маркеры файла " + file.getProjectRelativePath(), e);
        }
    }

    /** Проверка, что workspace доступен (используется из stop()). */
    public static boolean workspaceAvailable() {
        try {
            return ResourcesPlugin.getWorkspace() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
