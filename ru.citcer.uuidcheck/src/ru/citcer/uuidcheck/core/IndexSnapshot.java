/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/** Неизменяемый снимок индекса проекта: единственное, что видят UI и экспорт. */
public final class IndexSnapshot {

    private final IProject project;
    private final long generation;
    private final Map<String, List<UuidOccurrence>> byUuid;
    private final Map<IPath, FileIndexEntry> byFile;

    IndexSnapshot(IProject project, long generation,
                  Map<String, List<UuidOccurrence>> byUuid,
                  Map<IPath, FileIndexEntry> byFile) {
        this.project = project;
        this.generation = generation;
        Map<String, List<UuidOccurrence>> uuids = new LinkedHashMap<>();
        byUuid.forEach((key, value) -> uuids.put(key, List.copyOf(value)));
        this.byUuid = Collections.unmodifiableMap(uuids);
        this.byFile = Collections.unmodifiableMap(new LinkedHashMap<>(byFile));
    }

    public IProject project() {
        return project;
    }

    public long generation() {
        return generation;
    }

    public Map<String, List<UuidOccurrence>> byUuid() {
        return byUuid;
    }

    public Map<IPath, FileIndexEntry> byFile() {
        return byFile;
    }

    public List<UuidOccurrence> occurrences(String uuid) {
        return byUuid.getOrDefault(uuid, List.of());
    }

    public Set<IPath> files(String uuid) {
        Set<IPath> result = new TreeSet<>(Comparator.comparing(IPath::toString));
        for (UuidOccurrence occurrence : occurrences(uuid)) {
            result.add(occurrence.file());
        }
        return result;
    }

    public int uniqueUuidCount() {
        return byUuid.size();
    }

    public int fileCount() {
        return byFile.size();
    }

    public int occurrenceCount() {
        int total = 0;
        for (List<UuidOccurrence> list : byUuid.values()) {
            total += list.size();
        }
        return total;
    }

    public int failedFiles() {
        int failed = 0;
        for (FileIndexEntry entry : byFile.values()) {
            if (entry.failed()) {
                failed++;
            }
        }
        return failed;
    }

    /** Число файлов, в которых найден хотя бы один UUID (как в эталонной реализации). */
    public int filesWithUuids() {
        int found = 0;
        for (FileIndexEntry entry : byFile.values()) {
            if (!entry.occurrences().isEmpty()) {
                found++;
            }
        }
        return found;
    }

    public int countByExtension(String extension) {
        String suffix = "." + extension.toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        for (IPath path : byFile.keySet()) {
            if (path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }

    /** Все вхождения с сортировкой по файлу и строке. */
    public List<UuidOccurrence> allOccurrencesSorted() {
        List<UuidOccurrence> all = new ArrayList<>();
        byUuid.values().forEach(all::addAll);
        all.sort((a, b) -> {
            int cmp = a.file().toString().compareTo(b.file().toString());
            return cmp != 0 ? cmp : Integer.compare(a.line(), b.line());
        });
        return all;
    }
}
