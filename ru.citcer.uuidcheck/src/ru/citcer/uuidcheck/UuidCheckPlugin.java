/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.UuidIndexService;
import ru.citcer.uuidcheck.core.UuidPreferences;
import ru.citcer.uuidcheck.listener.UuidResourceChangeListener;
import ru.citcer.uuidcheck.markers.DuplicateUuidMarkerService;

/** Activator бандла. */
public final class UuidCheckPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "ru.citcer.uuidcheck";
    public static final String VIEW_ID = PLUGIN_ID + ".views.duplicates";

    private static UuidCheckPlugin instance;

    private UuidIndexService indexService;
    private DuplicateUuidMarkerService markerService;
    private UuidResourceChangeListener resourceListener;
    private UuidPreferences preferences;

    public UuidCheckPlugin() {
        super();
    }

    public static UuidCheckPlugin getDefault() {
        return instance;
    }

    public static IUuidIndexService getIndexService() {
        return instance == null ? null : instance.indexService;
    }

    public static DuplicateUuidMarkerService getMarkerService() {
        return instance == null ? null : instance.markerService;
    }

    public static UuidPreferences getPreferences() {
        return instance == null ? null : instance.preferences;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        preferences = new UuidPreferences();
        indexService = new UuidIndexService(this);
        markerService = new DuplicateUuidMarkerService(indexService, preferences);
        indexService.addListener(markerService);
        try {
            resourceListener = new UuidResourceChangeListener(indexService, preferences);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
                    IResourceChangeEvent.POST_CHANGE
                            | IResourceChangeEvent.PRE_CLOSE
                            | IResourceChangeEvent.PRE_DELETE);
        } catch (IllegalStateException e) {
            logError("Workspace недоступен при старте плагина", e);
            resourceListener = null;
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            if (resourceListener != null) {
                try {
                    ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
                } catch (IllegalStateException ignored) {
                    // workspace уже остановлен
                }
                resourceListener = null;
            }
            if (indexService != null) {
                if (markerService != null) {
                    indexService.removeListener(markerService);
                }
                indexService.shutdown();
                indexService = null;
            }
            markerService = null;
        } finally {
            instance = null;
            super.stop(context);
        }
    }

    /** Открывает view «Дубли UUID» (в UI-потоке). */
    public static void showView() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            try {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) {
                    return;
                }
                IWorkbenchPage page = window.getActivePage();
                if (page != null) {
                    page.showView(VIEW_ID);
                }
            } catch (PartInitException | IllegalStateException e) {
                logError("Не удалось открыть view «Дубли UUID»", e);
            }
        });
    }

    public static void showInformation(final String message) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> MessageDialog.openInformation(
                display.getActiveShell(), Messages.ErrorTitle, message));
    }

    public static void logError(String message, Throwable throwable) {
        ILog log = log();
        if (log != null) {
            log.log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
        }
    }

    public static void logWarning(String message) {
        ILog log = log();
        if (log != null) {
            log.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }
    }

    private static ILog log() {
        Bundle bundle = Platform.getBundle(PLUGIN_ID);
        return bundle == null ? null : Platform.getLog(bundle);
    }
}
