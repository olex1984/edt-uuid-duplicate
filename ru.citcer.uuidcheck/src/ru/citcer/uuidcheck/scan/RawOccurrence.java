/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.scan;

/**
 * Вхождение UUID, полученное парсером: ещё без привязки к {@code IFile}.
 */
public record RawOccurrence(String uuid, String elementType, String qualifiedName,
                            int line, int charStart, int charEnd) {
}
