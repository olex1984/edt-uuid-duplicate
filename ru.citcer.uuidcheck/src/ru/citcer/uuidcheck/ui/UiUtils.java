/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import ru.citcer.uuidcheck.UuidCheckPlugin;

/** Открытие файла на нужной строке. */
public final class UiUtils {

    private UiUtils() {
    }

    /** Открывает файл в текстовом редакторе и выделяет вхождение. */
    public static void open(IFile file, int line, int charStart, int charEnd) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                return;
            }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                return;
            }
            IEditorPart part = IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor", true);
            if (part instanceof ITextEditor textEditor) {
                int offset = Math.max(0, charStart);
                int length = Math.max(1, charEnd - charStart);
                textEditor.selectAndReveal(offset, length);
            }
        } catch (PartInitException e) {
            UuidCheckPlugin.logError("Не удалось открыть файл " + file.getProjectRelativePath(), e);
        }
    }
}
