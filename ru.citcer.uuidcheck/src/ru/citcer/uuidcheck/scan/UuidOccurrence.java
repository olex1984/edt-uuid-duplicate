/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.scan;

import org.eclipse.core.runtime.IPath;

/**
 * Вхождение UUID в конкретном файле проекта.
 *
 * @param file           проектно-относительный путь ('/' в качестве разделителя)
 * @param uuid           UUID в нижнем регистре
 * @param elementType    localName элемента, например {@code Catalog}
 * @param qualifiedName  qName элемента, например {@code mdclass:Catalog}
 * @param line           1-based номер строки
 * @param charStart      смещение от начала документа
 * @param charEnd        смещение конца элемента
 */
public record UuidOccurrence(IPath file, String uuid, String elementType, String qualifiedName,
                             int line, int charStart, int charEnd) {

    public String fileName() {
        return file == null ? "" : file.toString();
    }
}
