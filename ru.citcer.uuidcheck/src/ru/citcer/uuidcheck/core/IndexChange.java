/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

/**
 * Событие обновления индекса.
 *
 * @param affectedUuids затронутые UUID (пусто при полном скане)
 * @param changedFiles   затронутые файлы (пусто при полном скане)
 * @param fullRescan     true, если индекс проекта пересобран целиком
 */
public record IndexChange(IProject project, Set<String> affectedUuids,
                          Set<IPath> changedFiles, boolean fullRescan) {
}
