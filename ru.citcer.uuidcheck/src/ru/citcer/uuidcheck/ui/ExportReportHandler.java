/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

/** Экспорт текстового отчёта из команды. */
public final class ExportReportHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ExportSupport.exportReport(HandlerUtil.getActiveShell(event), ProjectSelection.from(event));
        return null;
    }
}
