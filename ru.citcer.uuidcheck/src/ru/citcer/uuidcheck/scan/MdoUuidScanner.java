/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.scan;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

/**
 * Адаптер {@code IFile} → {@link UuidXmlParser}. Не бросает {@code CoreException}
 * наружу: любая ошибка превращается в {@link ScanResult#failed}.
 */
public final class MdoUuidScanner {

    public ScanResult scan(IFile file) {
        IPath path = file.getProjectRelativePath();
        try (InputStream in = file.getContents(true)) {
            return parse(path, in);
        } catch (CoreException e) {
            return ScanResult.failed(path, statusMessage(e));
        } catch (IOException e) {
            return ScanResult.failed(path, e.getMessage());
        }
    }

    /** Разбор из произвольного потока (используется тестами и кэшем). */
    public ScanResult parse(IPath path, InputStream input) {
        BufferedInputStream in = input instanceof BufferedInputStream buffered
                ? buffered : new BufferedInputStream(input);
        List<UuidOccurrence> out = new ArrayList<>();
        try {
            if (isEmpty(in)) {
                // Эталонная реализация для пустого файла даёт «0 UUID, ok» — сохраняем паритет.
                return ScanResult.ok(path, List.of());
            }
            UuidXmlParser.parse(in, raw -> out.add(new UuidOccurrence(path, raw.uuid(),
                    raw.elementType(), raw.qualifiedName(), raw.line(), raw.charStart(), raw.charEnd())));
            return ScanResult.ok(path, out);
        } catch (XMLStreamException e) {
            return ScanResult.failed(path, e.getMessage());
        } catch (IOException e) {
            return ScanResult.failed(path, e.getMessage());
        }
    }

    /** Проверяет, что поток не содержит ничего, кроме пробельных символов. */
    private static boolean isEmpty(BufferedInputStream in) throws IOException {
        in.mark(8192);
        try {
            int next;
            while ((next = in.read()) != -1) {
                if (!Character.isWhitespace(next)) {
                    return false;
                }
            }
            return true;
        } finally {
            in.reset();
        }
    }

    private static String statusMessage(CoreException e) {
        return e.getStatus() != null ? e.getStatus().getMessage() : e.getMessage();
    }
}
