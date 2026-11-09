/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.ProjectUtils;

/** Команда «Проверить дубликаты UUID»: полный скан проекта и открытие view. */
public final class CheckUuidCommandHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        if (service == null) {
            return null;
        }
        IProject project = ProjectSelection.from(event);
        if (project == null) {
            UuidCheckPlugin.showInformation(Messages.ErrorNoProject);
            return null;
        }
        if (!ProjectUtils.isEdtProject(project)) {
            UuidCheckPlugin.showInformation(Messages.ErrorNotEdt);
            return null;
        }
        service.requestScan(project, true);
        UuidCheckPlugin.showView();
        return null;
    }
}
