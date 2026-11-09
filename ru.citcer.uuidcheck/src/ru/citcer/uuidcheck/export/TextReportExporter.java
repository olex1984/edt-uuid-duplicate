/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.DuplicateKind;
import ru.citcer.uuidcheck.core.IndexSnapshot;

/**
 * Текстовый отчёт в эталонном формате (1:1 по строкам и эмодзи).
 * Эмодзи заданы {@code u}-escape, чтобы не зависеть от кодировки исходников.
 */
public final class TextReportExporter {

    private static final String LINE = "\u2501".repeat(40);

    public String build(IndexSnapshot snapshot, List<DuplicateGroup> groups) {
        List<DuplicateGroup> cross = new ArrayList<>();
        for (DuplicateGroup group : groups) {
            if (group.kind() == DuplicateKind.CROSS_FILE) {
                cross.add(group);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCCA СТАТИСТИКА POSTHANDLINGS\n");
        sb.append(LINE).append('\n');
        sb.append("\uD83D\uDCC1 Всего файлов:       ").append(snapshot.fileCount()).append('\n');
        sb.append("\uD83D\uDCC4 XML файлов:         ").append(snapshot.countByExtension("xml")).append('\n');
        sb.append("\uD83D\uDCC4 MDO файлов:          ").append(snapshot.countByExtension("mdo")).append('\n');
        sb.append("\uD83D\uDD11 Всего уникальных:    ").append(snapshot.uniqueUuidCount()).append('\n');
        sb.append("\uD83D\uDD25 Дублирующихся:       ").append(cross.size()).append('\n');
        sb.append('\n');
        sb.append("\uD83D\uDD25 ДУБЛИРУЮЩИЕ UUID\n");
        sb.append(LINE).append('\n');
        for (DuplicateGroup group : cross) {
            sb.append('\n').append(group.uuid()).append(":\n");
            List<IPath> paths = new ArrayList<>(group.files());
            paths.sort((a, b) -> a.toString().compareTo(b.toString()));
            for (IPath path : paths) {
                sb.append("  \uD83D\uDCC4 ").append(path).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Пишет отчёт. При отсутствии дублей и {@code reportWhenEmpty == false}
     * файл не создаётся (как в скрипте); при {@code true} пишется пустой файл.
     */
    public void export(IPath out, IndexSnapshot snapshot, List<DuplicateGroup> groups,
                       boolean reportWhenEmpty) throws IOException {
        List<DuplicateGroup> cross = new ArrayList<>();
        for (DuplicateGroup group : groups) {
            if (group.kind() == DuplicateKind.CROSS_FILE) {
                cross.add(group);
            }
        }
        if (cross.isEmpty()) {
            if (reportWhenEmpty) {
                Files.writeString(out.toFile().toPath(), "", StandardCharsets.UTF_8);
            }
            return;
        }
        Files.writeString(out.toFile().toPath(), build(snapshot, cross), StandardCharsets.UTF_8);
    }
}
