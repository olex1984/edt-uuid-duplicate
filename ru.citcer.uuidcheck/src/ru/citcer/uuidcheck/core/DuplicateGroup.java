/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/** Группа дублей: один UUID и все его вхождения. */
public record DuplicateGroup(String uuid, DuplicateKind kind,
                             List<UuidOccurrence> occurrences, Set<IPath> files) {

    public int fileCount() {
        return files.size();
    }

    public int occurrenceCount() {
        return occurrences.size();
    }
}
