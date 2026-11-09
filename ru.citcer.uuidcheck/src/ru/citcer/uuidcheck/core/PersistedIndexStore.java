/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import ru.citcer.uuidcheck.scan.UuidOccurrence;

/**
 * Кэш индекса в state location (замена {@code .uuid_manifest.json}).
 * Формат — приложение B плана; расширен до нескольких проектов
 * (массив {@code projects}), {@code schemaVersion} = 1.
 */
public final class PersistedIndexStore {

    public static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "uuid-index-v1.json";

    private final java.nio.file.Path file;

    public PersistedIndexStore(java.nio.file.Path stateDir) {
        this.file = stateDir.resolve(FILE_NAME);
    }

    public java.nio.file.Path file() {
        return file;
    }

    /** Читает кэш: имя проекта → (путь → состояние файла). */
    public Map<String, Map<IPath, FileIndexEntry>> load() throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("Повреждённый кэш индекса: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Object schema = root.get("schemaVersion");
        if (!(schema instanceof Number number) || number.intValue() != SCHEMA_VERSION) {
            return Map.of();
        }
        Map<String, Map<IPath, FileIndexEntry>> result = new LinkedHashMap<>();
        Object projects = root.get("projects");
        if (!(projects instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> projectMap)) {
                continue;
            }
            String name = str(projectMap.get("project"));
            if (name == null) {
                continue;
            }
            Map<IPath, FileIndexEntry> entries = new LinkedHashMap<>();
            Object files = projectMap.get("files");
            if (files instanceof List<?> fileList) {
                for (Object fileItem : fileList) {
                    if (!(fileItem instanceof Map<?, ?> fileMap)) {
                        continue;
                    }
                    String path = str(fileMap.get("path"));
                    if (path == null) {
                        continue;
                    }
                    long stamp = num(fileMap.get("stamp"));
                    long size = num(fileMap.get("size"));
                    String failure = str(fileMap.get("failure"));
                    List<UuidOccurrence> occurrences = new ArrayList<>();
                    Object uuids = fileMap.get("uuids");
                    if (uuids instanceof List<?> uuidList) {
                        for (Object uuidItem : uuidList) {
                            if (!(uuidItem instanceof Map<?, ?> uuidMap)) {
                                continue;
                            }
                            String uuid = str(uuidMap.get("uuid"));
                            if (uuid == null) {
                                continue;
                            }
                            occurrences.add(new UuidOccurrence(new Path(path), uuid,
                                    strOr(uuidMap.get("element"), ""), strOr(uuidMap.get("qName"), ""),
                                    (int) num(uuidMap.get("line")),
                                    (int) num(uuidMap.get("charStart")),
                                    (int) num(uuidMap.get("charEnd"))));
                        }
                    }
                    entries.put(new Path(path), new FileIndexEntry(stamp, size, occurrences, failure));
                }
            }
            result.put(name, entries);
        }
        return result;
    }

    /** Атомарная запись кэша (tmp + move). */
    public void save(Map<IProject, ProjectUuidIndex> indexes) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("pluginVersion", "1.0.0");
        List<Object> projects = new ArrayList<>();
        for (Map.Entry<IProject, ProjectUuidIndex> indexEntry : indexes.entrySet()) {
            IProject project = indexEntry.getKey();
            if (!project.exists()) {
                continue;
            }
            Map<String, Object> projectMap = new LinkedHashMap<>();
            projectMap.put("project", project.getName());
            projectMap.put("generatedAt", OffsetDateTime.now().toString());
            List<Object> files = new ArrayList<>();
            for (Map.Entry<IPath, FileIndexEntry> fileEntry : indexEntry.getValue().byFile().entrySet()) {
                FileIndexEntry entry = fileEntry.getValue();
                Map<String, Object> fileMap = new LinkedHashMap<>();
                fileMap.put("path", fileEntry.getKey().toString());
                fileMap.put("stamp", entry.modificationStamp());
                fileMap.put("size", entry.size());
                fileMap.put("failure", entry.failure());
                List<Object> uuids = new ArrayList<>();
                for (UuidOccurrence occurrence : entry.occurrences()) {
                    Map<String, Object> uuidMap = new LinkedHashMap<>();
                    uuidMap.put("uuid", occurrence.uuid());
                    uuidMap.put("element", occurrence.elementType());
                    uuidMap.put("qName", occurrence.qualifiedName());
                    uuidMap.put("line", occurrence.line());
                    uuidMap.put("charStart", occurrence.charStart());
                    uuidMap.put("charEnd", occurrence.charEnd());
                    uuids.add(uuidMap);
                }
                fileMap.put("uuids", uuids);
                files.add(fileMap);
            }
            projectMap.put("files", files);
            projects.add(projectMap);
        }
        root.put("projects", projects);

        java.nio.file.Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String str(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String strOr(Object value, String fallback) {
        return value instanceof String string ? string : fallback;
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
