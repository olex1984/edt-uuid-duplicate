/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import ru.citcer.uuidcheck.Messages;

/** Debounce-планировщик: один фоновый поток, один отложенный job на проект. */
public final class ScanScheduler {

    private final IUuidIndexService service;
    private final UuidPreferences preferences;
    private final ScheduledExecutorService executor;
    private final Map<IProject, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public ScanScheduler(IUuidIndexService service, UuidPreferences preferences) {
        this.service = service;
        this.preferences = preferences;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "uuidcheck-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Отменяет предыдущий запрос проекта и планирует новый через debounce. */
    public void request(IProject project, boolean ignoreCache) {
        if (project == null) {
            return;
        }
        ScheduledFuture<?> previous = pending.remove(project);
        if (previous != null) {
            previous.cancel(false);
        }
        long delay = Math.max(0, preferences.debounceMs());
        ScheduledFuture<?> future = executor.schedule(
                () -> scheduleJob(project, ignoreCache), delay, TimeUnit.MILLISECONDS);
        pending.put(project, future);
    }

    public void cancel(IProject project) {
        ScheduledFuture<?> future = pending.remove(project);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void scheduleJob(IProject project, boolean ignoreCache) {
        pending.remove(project);
        if (project == null || !project.isAccessible()) {
            return;
        }
        String name = MessageFormat.format(Messages.ScanJobName, project.getName());
        WorkspaceJob job = new WorkspaceJob(name) {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) {
                try {
                    service.rescan(project, ignoreCache, monitor);
                } catch (RuntimeException e) {
                    return new Status(IStatus.ERROR, "ru.citcer.uuidcheck",
                            "Ошибка сканирования проекта " + project.getName(), e);
                }
                return Status.OK_STATUS;
            }
        };
        job.setRule(project);
        job.setUser(true);
        job.schedule();
    }

    public void shutdown() {
        executor.shutdownNow();
        pending.clear();
    }
}
