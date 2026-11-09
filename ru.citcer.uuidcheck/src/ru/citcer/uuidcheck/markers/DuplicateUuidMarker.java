/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.markers;

import ru.citcer.uuidcheck.UuidCheckPlugin;

/** Константы типа маркера и его атрибутов (см. plugin.xml). */
public final class DuplicateUuidMarker {

    public static final String TYPE = UuidCheckPlugin.PLUGIN_ID + ".duplicateUuidMarker";
    public static final String ATTR_UUID = "uuid";
    public static final String ATTR_ELEMENT_TYPE = "elementType";
    public static final String ATTR_DUPLICATE_FILES = "duplicateFiles";
    public static final String ATTR_KIND = "kind";

    private DuplicateUuidMarker() {
    }
}
