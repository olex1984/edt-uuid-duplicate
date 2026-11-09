/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.scan;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;

/** Результат разбора одного файла: успех, ошибка или отмена. */
public final class ScanResult {

    private final IPath path;
    private final IFile file;
    private final List<UuidOccurrence> occurrences;
    private final String failure;
    private final boolean cancelled;

    private ScanResult(IPath path, IFile file, List<UuidOccurrence> occurrences,
                       String failure, boolean cancelled) {
        this.path = path;
        this.file = file;
        this.occurrences = occurrences;
        this.failure = failure;
        this.cancelled = cancelled;
    }

    public static ScanResult ok(IPath path, List<UuidOccurrence> occurrences) {
        return new ScanResult(path, null, List.copyOf(occurrences), null, false);
    }

    public static ScanResult ok(IFile file, List<UuidOccurrence> occurrences) {
        return new ScanResult(file.getProjectRelativePath(), file, List.copyOf(occurrences), null, false);
    }

    public static ScanResult failed(IPath path, String message) {
        return new ScanResult(path, null, List.of(), message == null ? "" : message, false);
    }

    public static ScanResult cancelled(IPath path) {
        return new ScanResult(path, null, List.of(), null, true);
    }

    public IPath path() {
        return path;
    }

    public IFile file() {
        return file;
    }

    public List<UuidOccurrence> occurrences() {
        return occurrences;
    }

    public String failure() {
        return failure;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isOk() {
        return failure == null && !cancelled;
    }
}
