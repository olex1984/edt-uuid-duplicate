/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IStartup;

import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.ProjectUtils;
import ru.citcer.uuidcheck.core.UuidPreferences;

/** Фоновый прогрев при старте EDT (scan.onStartup). */
public final class UuidEarlyStartup implements IStartup {

    @Override
    public void earlyStartup() {
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        UuidPreferences preferences = UuidCheckPlugin.getPreferences();
        if (service == null || preferences == null || !preferences.onStartup()) {
            return;
        }
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isAccessible()
                        && ProjectUtils.isEdtProject(project)
                        && preferences.enabled(project)) {
                    service.requestScan(project, false);
                }
            }
        } catch (IllegalStateException e) {
            UuidCheckPlugin.logWarning("Прогрев при старте не выполнен: " + e.getMessage());
        }
    }
}
