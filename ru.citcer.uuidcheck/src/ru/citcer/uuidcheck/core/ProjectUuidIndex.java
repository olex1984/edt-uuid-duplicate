/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * Изменяемый индекс одного проекта: {@code uuid → вхождения} и
 * {@code файл → состояние}. Не потокобезопасен: живёт под write-lock сервиса.
 */
public final class ProjectUuidIndex {

    private final Map<String, List<UuidOccurrence>> byUuid = new HashMap<>();
    private final Map<IPath, FileIndexEntry> byFile = new HashMap<>();

    /** Заменяет состояние файла, возвращает затронутые UUID. */
    public Set<String> replaceFile(IPath path, FileIndexEntry entry) {
        Set<String> affected = new HashSet<>();
        FileIndexEntry old = byFile.get(path);
        if (old != null) {
            for (UuidOccurrence occurrence : old.occurrences()) {
                affected.add(occurrence.uuid());
                removeOccurrence(occurrence.uuid(), path);
            }
        }
        byFile.put(path, entry);
        for (UuidOccurrence occurrence : entry.occurrences()) {
            byUuid.computeIfAbsent(occurrence.uuid(), k -> new ArrayList<>()).add(occurrence);
            affected.add(occurrence.uuid());
        }
        return affected;
    }

    /** Удаляет файл из индекса, возвращает затронутые UUID. */
    public Set<String> removeFile(IPath path) {
        Set<String> affected = new HashSet<>();
        FileIndexEntry old = byFile.remove(path);
        if (old != null) {
            for (UuidOccurrence occurrence : old.occurrences()) {
                affected.add(occurrence.uuid());
                removeOccurrence(occurrence.uuid(), path);
            }
        }
        return affected;
    }

    private void removeOccurrence(String uuid, IPath path) {
        List<UuidOccurrence> list = byUuid.get(uuid);
        if (list == null) {
            return;
        }
        list.removeIf(occurrence -> occurrence.file().equals(path));
        if (list.isEmpty()) {
            byUuid.remove(uuid);
        }
    }

    public Map<String, List<UuidOccurrence>> byUuid() {
        return byUuid;
    }

    public Map<IPath, FileIndexEntry> byFile() {
        return byFile;
    }

    public IndexSnapshot snapshot(IProject project, long generation) {
        return new IndexSnapshot(project, generation, byUuid, byFile);
    }
}
