/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * Вычисляет группы дублей по снимку индекса.
 *
 * <p>Межфайловый дубль имеет приоритет: если UUID встречается в ≥2 файлах,
 * внутрифайловые повторы по нему отдельной группой не выводятся.</p>
 */
public final class DuplicateAnalyzer {

    public List<DuplicateGroup> analyze(IndexSnapshot snapshot, IntraFileMode intraMode) {
        boolean includeIntra = intraMode != IntraFileMode.IGNORE;
        List<DuplicateGroup> groups = new ArrayList<>();

        for (Map.Entry<String, List<UuidOccurrence>> entry : snapshot.byUuid().entrySet()) {
            String uuid = entry.getKey();
            List<UuidOccurrence> occurrences = entry.getValue();
            if (occurrences.isEmpty()) {
                continue;
            }
            Set<IPath> files = new TreeSet<>(Comparator.comparing(IPath::toString));
            for (UuidOccurrence occurrence : occurrences) {
                files.add(occurrence.file());
            }
            if (files.size() >= 2) {
                groups.add(new DuplicateGroup(uuid, DuplicateKind.CROSS_FILE,
                        sorted(occurrences), files));
            } else if (includeIntra) {
                Map<IPath, List<UuidOccurrence>> byFile = new TreeMap<>(Comparator.comparing(IPath::toString));
                for (UuidOccurrence occurrence : occurrences) {
                    byFile.computeIfAbsent(occurrence.file(), k -> new ArrayList<>()).add(occurrence);
                }
                for (Map.Entry<IPath, List<UuidOccurrence>> fileEntry : byFile.entrySet()) {
                    if (fileEntry.getValue().size() > 1) {
                        Set<IPath> single = new LinkedHashSet<>();
                        single.add(fileEntry.getKey());
                        groups.add(new DuplicateGroup(uuid, DuplicateKind.INTRA_FILE,
                                sorted(fileEntry.getValue()), single));
                    }
                }
            }
        }

        groups.sort((a, b) -> a.uuid().compareTo(b.uuid()));
        return groups;
    }

    /** Только межфайловые дубли (для сверки с эталоном). */
    public List<DuplicateGroup> analyzeCrossFile(IndexSnapshot snapshot) {
        return analyze(snapshot, IntraFileMode.IGNORE);
    }

    private static List<UuidOccurrence> sorted(List<UuidOccurrence> occurrences) {
        List<UuidOccurrence> copy = new ArrayList<>(occurrences);
        copy.sort((a, b) -> {
            int cmp = a.file().toString().compareTo(b.file().toString());
            return cmp != 0 ? cmp : Integer.compare(a.line(), b.line());
        });
        return List.copyOf(copy);
    }
}
