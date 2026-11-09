package ru.citcer.uuidcheck.diag;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;

import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IntraFileMode;
import ru.citcer.uuidcheck.core.IUuidIndexService;

/** Headless-проверка: разрешение бандла, активация, скан проекта. */
public final class DiagApp implements IApplication {

    @Override
    public Object start(IApplicationContext context) {
        Bundle bundle = Platform.getBundle("ru.citcer.uuidcheck");
        System.out.println("DIAG bundle=" + (bundle == null ? "MISSING" : "present state=" + bundle.getState()));
        if (bundle == null) {
            return Integer.valueOf(2);
        }
        try {
            bundle.start(Bundle.START_TRANSIENT);
            System.out.println("DIAG start=OK state=" + bundle.getState());
        } catch (Exception e) {
            e.printStackTrace();
            return Integer.valueOf(3);
        }
        try {
            Class<?> messages = bundle.loadClass("ru.citcer.uuidcheck.Messages");
            Object value = messages.getField("PrefsEnabled").get(null);
            System.out.println("DIAG nls=" + value);
        } catch (Throwable t) {
            System.out.println("DIAG nls=FAIL " + t);
        }
        try {
            bundle.loadClass("ru.citcer.uuidcheck.ui.DuplicateUuidView");
            System.out.println("DIAG view-load=OK");
        } catch (Throwable t) {
            System.out.println("DIAG view-class=FAIL " + t);
        }
        try {
            Class<?> messages = bundle.loadClass("ru.citcer.uuidcheck.Messages");
            System.out.println("DIAG nls-expand=" + messages.getField("ActionExpandAll").get(null));
        } catch (Throwable t) {
            System.out.println("DIAG nls-expand=FAIL " + t);
        }
        try {
            Class<?> page = bundle.loadClass("ru.citcer.uuidcheck.ui.UuidCheckPreferencePage");
            Class<?> iface = bundle.loadClass("org.eclipse.ui.IWorkbenchPreferencePage");
            System.out.println("DIAG prefs-assignable=" + iface.isAssignableFrom(page));
        } catch (Throwable t) {
            System.out.println("DIAG prefs-assignable=FAIL " + t);
        }
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("TestProject");
            if (!project.exists()) {
                org.eclipse.core.resources.IProjectDescription description =
                        ResourcesPlugin.getWorkspace().newProjectDescription("TestProject");
                project.create(description, new NullProgressMonitor());
            }
            if (!project.isOpen()) {
                project.open(new NullProgressMonitor());
            }
            project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
            System.out.println("DIAG project accessible=" + project.isAccessible());
            IUuidIndexService service = UuidCheckPlugin.getIndexService();
            System.out.println("DIAG service=" + (service == null ? "null" : "ok"));
            if (service == null || !project.isAccessible()) {
                return Integer.valueOf(4);
            }
            service.rescan(project, true, new NullProgressMonitor());
            IndexSnapshot snapshot = service.snapshot(project);
            int groups = new DuplicateAnalyzer().analyze(snapshot, IntraFileMode.WARN).size();
            IMarker[] markers = project.findMarkers("ru.citcer.uuidcheck.duplicateUuidMarker",
                    true, IResource.DEPTH_INFINITE);
            System.out.println("DIAG markers=" + markers.length);
            System.out.println("DIAG files=" + snapshot.fileCount()
                    + " unique=" + snapshot.uniqueUuidCount()
                    + " failed=" + snapshot.failedFiles()
                    + " groups=" + groups);
            boolean pass = snapshot.fileCount() == 9 && snapshot.uniqueUuidCount() == 17 && groups == 2;
            System.out.println("DIAG RESULT=" + (pass ? "PASS" : "FAIL"));
            return Integer.valueOf(pass ? 0 : 5);
        } catch (Throwable t) {
            t.printStackTrace();
            return Integer.valueOf(6);
        }
    }

    @Override
    public void stop() {
    }
}
