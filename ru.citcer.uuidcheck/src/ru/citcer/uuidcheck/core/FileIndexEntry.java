/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.List;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * Состояние одного проиндексированного файла.
 *
 * @param modificationStamp stamp файла на момент сканирования
 * @param size              размер файла на момент сканирования
 * @param occurrences       найденные вхождения (пусто для failed/cancelled)
 * @param failure           текст ошибки разбора или {@code null}
 */
public record FileIndexEntry(long modificationStamp, long size,
                             List<UuidOccurrence> occurrences, String failure) {

    public boolean failed() {
        return failure != null;
    }

    public boolean isEmpty() {
        return occurrences.isEmpty();
    }
}
