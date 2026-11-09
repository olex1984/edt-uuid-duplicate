/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

/** Экспорт CSV из команды. */
public final class ExportCsvHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ExportSupport.exportCsv(HandlerUtil.getActiveShell(event), ProjectSelection.from(event));
        return null;
    }
}
