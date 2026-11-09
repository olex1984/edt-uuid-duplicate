/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.part.ViewPart;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.DuplicateKind;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IUuidIndexListener;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.UuidPreferences;
import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * View «Дубли UUID»: дерево групп. Строка группы раскрывается в список
 * всех файлов с этим UUID (двойной клик по группе — развернуть/свернуть,
 * двойной клик по файлу — открыть его на нужной строке).
 */
public final class DuplicateUuidView extends ViewPart {

    public static final String ID = UuidCheckPlugin.VIEW_ID;

    private static final int COL_PROJECT = 0;
    private static final int COL_UUID = 1;
    private static final int COL_FILES = 2;
    private static final int COL_OCCURRENCES = 3;
    private static final int COL_KIND = 4;
    private static final int COL_LOCATIONS = 5;

    /** Группа дублей (верхний уровень дерева). */
    private static final class Row {
        final IProject project;
        final String uuid;
        final DuplicateKind kind;
        final List<UuidOccurrence> occurrences;
        final List<OccurrenceNode> children = new ArrayList<>();

        Row(IProject project, String uuid, DuplicateKind kind, List<UuidOccurrence> occurrences) {
            this.project = project;
            this.uuid = uuid;
            this.kind = kind;
            this.occurrences = occurrences;
            for (UuidOccurrence occurrence : occurrences) {
                children.add(new OccurrenceNode(this, occurrence));
            }
        }

        String projectName() {
            return project == null ? "" : project.getName();
        }

        String locations() {
            List<String> parts = new ArrayList<>();
            int shown = 0;
            for (UuidOccurrence occurrence : occurrences) {
                if (shown >= 3) {
                    parts.add("… (" + occurrences.size() + ")");
                    break;
                }
                parts.add(occurrence.file() + ":" + occurrence.line());
                shown++;
            }
            return String.join("; ", parts);
        }
    }

    /** Вхождение внутри группы (лист дерева). */
    private static final class OccurrenceNode {
        final Row parent;
        final UuidOccurrence occurrence;

        OccurrenceNode(Row parent, UuidOccurrence occurrence) {
            this.parent = parent;
            this.occurrence = occurrence;
        }

        String location() {
            StringBuilder sb = new StringBuilder();
            sb.append(occurrence.file()).append(':').append(occurrence.line());
            if (occurrence.elementType() != null && !occurrence.elementType().isEmpty()) {
                sb.append(" (").append(occurrence.elementType()).append(')');
            }
            return sb.toString();
        }
    }

    private final DuplicateAnalyzer analyzer = new DuplicateAnalyzer();
    private final List<Row> rows = new ArrayList<>();

    private TreeViewer viewer;
    private Text filterText;
    private Label statusLabel;
    private IUuidIndexListener listener;

    private Action openAction;
    private Action copyUuidAction;
    private Action exportCsvAction;
    private Action exportReportAction;
    private Action expandAllAction;
    private Action collapseAllAction;

    private int sortColumn = COL_UUID;
    private boolean sortAscending = true;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        filterText = new Text(parent, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        filterText.setMessage(Messages.ViewFilterHint);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(event -> {
            if (viewer != null) {
                viewer.refresh();
            }
        });

        viewer = new TreeViewer(parent, SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
        Tree tree = viewer.getTree();
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumns(tree);
        viewer.setContentProvider(new TreeContentProvider());
        viewer.setComparator(createComparator());
        viewer.addFilter(new ViewerFilter() {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element) {
                if (element instanceof OccurrenceNode) {
                    return true;
                }
                return matches((Row) element);
            }
        });
        viewer.addDoubleClickListener(event -> handleDoubleClick());
        viewer.addSelectionChangedListener(event -> updateActions());

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        createActions();
        hookContextMenu();
        contributeToolbar();

        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        if (service != null) {
            listener = change -> Display.getDefault().asyncExec(this::reload);
            service.addListener(listener);
        }
        getSite().setSelectionProvider(viewer);
        reload();
    }

    private void createColumns(Tree tree) {
        String[] titles = {
                Messages.ViewColumnProject, Messages.ViewColumnUuid, Messages.ViewColumnFiles,
                Messages.ViewColumnOccurrences, Messages.ViewColumnKind, Messages.ViewColumnLocations
        };
        int[] widths = {120, 260, 70, 80, 110, 460};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(titles[i]);
            column.getColumn().setWidth(widths[i]);
            column.getColumn().addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent event) {
                    if (sortColumn == index) {
                        sortAscending = !sortAscending;
                    } else {
                        sortColumn = index;
                        sortAscending = true;
                    }
                    viewer.setComparator(createComparator());
                }
            });
            column.setLabelProvider(new ColumnLabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof OccurrenceNode node) {
                        return index == COL_LOCATIONS ? node.location() : "";
                    }
                    Row row = (Row) element;
                    return switch (index) {
                        case COL_PROJECT -> row.projectName();
                        case COL_UUID -> row.uuid;
                        case COL_FILES -> Integer.toString(row.occurrences.size() > 0
                                ? distinctFiles(row) : 0);
                        case COL_OCCURRENCES -> Integer.toString(row.occurrences.size());
                        case COL_KIND -> row.kind == DuplicateKind.CROSS_FILE
                                ? Messages.KindCrossFile : Messages.KindIntraFile;
                        default -> row.locations();
                    };
                }
            });
        }
    }

    private static int distinctFiles(Row row) {
        Set<String> files = new HashSet<>();
        for (UuidOccurrence occurrence : row.occurrences) {
            files.add(occurrence.file().toString());
        }
        return files.size();
    }

    private ViewerComparator createComparator() {
        return new ViewerComparator() {
            @Override
            public int compare(Viewer viewer, Object first, Object second) {
                if (!(first instanceof Row a) || !(second instanceof Row b)) {
                    return 0;
                }
                int result = switch (sortColumn) {
                    case COL_PROJECT -> a.projectName().compareTo(b.projectName());
                    case COL_FILES -> Integer.compare(distinctFiles(a), distinctFiles(b));
                    case COL_OCCURRENCES -> Integer.compare(a.occurrences.size(), b.occurrences.size());
                    case COL_KIND -> a.kind.compareTo(b.kind);
                    case COL_LOCATIONS -> a.locations().compareTo(b.locations());
                    default -> a.uuid.compareTo(b.uuid);
                };
                if (result == 0) {
                    result = a.uuid.compareTo(b.uuid);
                }
                return sortAscending ? result : -result;
            }
        };
    }

    private boolean matches(Row row) {
        if (filterText == null || filterText.getText().isEmpty()) {
            return true;
        }
        String query = filterText.getText().toLowerCase(Locale.ROOT);
        if (row.uuid.toLowerCase(Locale.ROOT).contains(query)
                || row.projectName().toLowerCase(Locale.ROOT).contains(query)
                || row.locations().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (OccurrenceNode node : row.children) {
            if (node.location().toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private void reload() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        Set<String> expanded = new HashSet<>();
        for (Object element : viewer.getExpandedElements()) {
            if (element instanceof Row row) {
                expanded.add(row.uuid);
            }
        }

        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        rows.clear();
        int scannedFiles = 0;
        int failedFiles = 0;
        if (service != null && preferences != null) {
            for (IndexSnapshot snapshot : service.snapshots()) {
                scannedFiles += snapshot.fileCount();
                failedFiles += snapshot.failedFiles();
                for (DuplicateGroup group : analyzer.analyze(snapshot,
                        preferences.intraFileMode(snapshot.project()))) {
                    rows.add(new Row(snapshot.project(), group.uuid(), group.kind(),
                            group.occurrences()));
                }
            }
        }
        rows.sort((a, b) -> a.uuid.compareTo(b.uuid));
        viewer.setInput(rows);
        for (Row row : rows) {
            if (expanded.contains(row.uuid)) {
                viewer.setExpandedState(row, true);
            }
        }
        viewer.refresh();

        if (statusLabel != null && !statusLabel.isDisposed()) {
            if (rows.isEmpty()) {
                statusLabel.setText(MessageFormat.format(Messages.ViewEmptyText,
                        scannedFiles, failedFiles));
            } else {
                statusLabel.setText(MessageFormat.format(Messages.StatusIndexed,
                        rows.size(), scannedFiles, failedFiles));
            }
        }
        updateActions();
    }

    private void handleDoubleClick() {
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        Object element = selection.isEmpty() ? null : selection.getFirstElement();
        if (element instanceof OccurrenceNode node) {
            openNode(node);
        } else if (element instanceof Row row) {
            viewer.setExpandedState(row, !viewer.getExpandedState(row));
        }
    }

    private void openNode(OccurrenceNode node) {
        if (node.parent.project == null) {
            return;
        }
        IFile file = node.parent.project.getFile(node.occurrence.file());
        UiUtils.open(file, node.occurrence.line(), node.occurrence.charStart(),
                node.occurrence.charEnd());
    }

    private void openSelected() {
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        Object element = selection.isEmpty() ? null : selection.getFirstElement();
        if (element instanceof OccurrenceNode node) {
            openNode(node);
        } else if (element instanceof Row row && !row.children.isEmpty()) {
            viewer.setExpandedState(row, true);
            openNode(row.children.get(0));
        }
    }

    private void copyUuid() {
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        Object element = selection.isEmpty() ? null : selection.getFirstElement();
        String uuid = element instanceof OccurrenceNode node ? node.parent.uuid
                : element instanceof Row row ? row.uuid : null;
        if (uuid == null) {
            return;
        }
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try {
            clipboard.setContents(new Object[] {uuid},
                    new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    private IProject currentProject() {
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        Object element = selection.isEmpty() ? null : selection.getFirstElement();
        IProject project = element instanceof OccurrenceNode node ? node.parent.project
                : element instanceof Row row ? row.project : null;
        return project != null ? project : ProjectSelection.firstIndexed();
    }

    private void createActions() {
        openAction = new Action(Messages.ActionOpenFile) {
            @Override
            public void run() {
                openSelected();
            }
        };
        copyUuidAction = new Action(Messages.ActionCopyUuid) {
            @Override
            public void run() {
                copyUuid();
            }
        };
        exportCsvAction = new Action(Messages.ActionExportCsv) {
            @Override
            public void run() {
                ExportSupport.exportCsv(getViewSite().getShell(), currentProject());
            }
        };
        exportReportAction = new Action(Messages.ActionExportReport) {
            @Override
            public void run() {
                ExportSupport.exportReport(getViewSite().getShell(), currentProject());
            }
        };
        expandAllAction = new Action(Messages.ActionExpandAll) {
            @Override
            public void run() {
                viewer.expandAll();
            }
        };
        collapseAllAction = new Action(Messages.ActionCollapseAll) {
            @Override
            public void run() {
                viewer.collapseAll();
            }
        };
    }

    private void hookContextMenu() {
        MenuManager menuManager = new MenuManager("#PopupMenu");
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(this::fillContextMenu);
        Menu menu = menuManager.createContextMenu(viewer.getTree());
        viewer.getTree().setMenu(menu);
        getSite().registerContextMenu(menuManager, viewer);
    }

    private void fillContextMenu(IMenuManager manager) {
        manager.add(openAction);
        manager.add(copyUuidAction);
        manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
        manager.add(expandAllAction);
        manager.add(collapseAllAction);
        manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
        manager.add(exportCsvAction);
        manager.add(exportReportAction);
    }

    private void contributeToolbar() {
        ToolBarManager toolbar = (ToolBarManager) getViewSite().getActionBars().getToolBarManager();
        toolbar.add(expandAllAction);
        toolbar.add(collapseAllAction);
        toolbar.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
        toolbar.add(exportCsvAction);
        toolbar.add(exportReportAction);
    }

    private void updateActions() {
        boolean hasSelection = !viewer.getSelection().isEmpty();
        openAction.setEnabled(hasSelection);
        copyUuidAction.setEnabled(hasSelection);
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        if (service != null && listener != null) {
            service.removeListener(listener);
        }
        super.dispose();
    }

    /** Провайдер дерева: группы → вхождения. */
    private static final class TreeContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            return ((List<?>) inputElement).toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            return parentElement instanceof Row row ? row.children.toArray() : new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return element instanceof OccurrenceNode node ? node.parent : null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof Row row && !row.children.isEmpty();
        }
    }
}
