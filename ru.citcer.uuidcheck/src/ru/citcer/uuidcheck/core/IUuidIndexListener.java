/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

/** Слушатель изменений индекса; вызывается вне UI-потока. */
@FunctionalInterface
public interface IUuidIndexListener {
    void indexChanged(IndexChange change);
}
