/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

/** Политика для внутрифайловых повторов. */
public enum IntraFileMode {
    IGNORE, WARN, ERROR;

    public static IntraFileMode parse(String value) {
        if (value == null) {
            return WARN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return WARN;
        }
    }
}
