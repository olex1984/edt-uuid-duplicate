/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

/** Фасад над индексами проектов. */
public interface IUuidIndexService {

    /** Снимок индекса проекта или {@code null}, если проект не индексирован. */
    IndexSnapshot snapshot(IProject project);

    /** Снимки всех проиндексированных проектов. */
    List<IndexSnapshot> snapshots();

    /** Блокирующее сканирование; вызывать только из job'а. */
    void rescan(IProject project, boolean ignoreCache, IProgressMonitor monitor);

    /** Асинхронный запрос сканирования через планировщик. */
    void requestScan(IProject project, boolean ignoreCache);

    /** Убрать проект из индекса (закрытие/удаление). */
    void removeProject(IProject project);

    void addListener(IUuidIndexListener listener);

    void removeListener(IUuidIndexListener listener);

    Set<IProject> indexedProjects();

    /** Сохранить кэш на диск (по завершении работы). */
    void flushCache();

    void shutdown();
}
