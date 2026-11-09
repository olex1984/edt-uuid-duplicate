/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.ProjectUtils;
import ru.citcer.uuidcheck.core.UuidPreferences;

/** Команда «Пересканировать все проекты» (игнорирует кэш). */
public final class RescanAllHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        if (service == null || preferences == null) {
            return null;
        }
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isAccessible()
                    && ProjectUtils.isEdtProject(project)
                    && preferences.enabled(project)) {
                service.requestScan(project, true);
            }
        }
        UuidCheckPlugin.showView();
        return null;
    }
}
