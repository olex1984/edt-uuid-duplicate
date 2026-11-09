/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.DuplicateKind;
import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * CSV в эталонном формате: UTF-8, разделитель {@code ;}, заголовок
 * {@code UUID;Файл}, {@code \r\n}, сортировка по UUID и пути.
 */
public final class CsvExporter {

    /** Опции формата. */
    public static final class Options {
        private boolean bom;
        private char separator = ';';
        private boolean extended;

        public Options bom(boolean value) {
            this.bom = value;
            return this;
        }

        public Options separator(char value) {
            this.separator = value;
            return this;
        }

        public Options extended(boolean value) {
            this.extended = value;
            return this;
        }
    }

    /** Пишет CSV; группы INTRA_FILE не попадают (паритет с эталоном). */
    public void export(IPath out, List<DuplicateGroup> groups, Options options) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (options.bom) {
            sb.append('\uFEFF');
        }
        sb.append(field("UUID", options.separator)).append(options.separator)
                .append(field("Файл", options.separator));
        if (options.extended) {
            sb.append(options.separator).append(field("Строка", options.separator))
                    .append(options.separator).append(field("Элемент", options.separator));
        }
        sb.append("\r\n");

        for (DuplicateGroup group : groups) {
            if (group.kind() != DuplicateKind.CROSS_FILE) {
                continue;
            }
            Map<IPath, UuidOccurrence> firstByFile = new LinkedHashMap<>();
            for (UuidOccurrence occurrence : group.occurrences()) {
                firstByFile.merge(occurrence.file(), occurrence,
                        (a, b) -> a.line() <= b.line() ? a : b);
            }
            List<IPath> paths = new ArrayList<>(group.files());
            paths.sort((a, b) -> a.toString().compareTo(b.toString()));
            for (IPath path : paths) {
                UuidOccurrence occurrence = firstByFile.get(path);
                sb.append(field(group.uuid(), options.separator)).append(options.separator)
                        .append(field(path.toString(), options.separator));
                if (options.extended) {
                    sb.append(options.separator).append(occurrence == null ? 0 : occurrence.line())
                            .append(options.separator)
                            .append(field(occurrence == null ? "" : occurrence.elementType(),
                                    options.separator));
                }
                sb.append("\r\n");
            }
        }
        Files.writeString(out.toFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String field(String value, char separator) {
        boolean quote = value.indexOf(separator) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
