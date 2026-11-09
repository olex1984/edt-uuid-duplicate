/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.listener;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;

import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.ProjectUtils;
import ru.citcer.uuidcheck.core.UuidPreferences;

/**
 * Реакция на изменения ресурсов: только планирование, без чтения содержимого
 * внутри callback (риск R11). Широкая дельта обрабатывается тем же сканом —
 * кэш отсеивает неизменённые файлы.
 */
public final class UuidResourceChangeListener implements IResourceChangeListener {

    private final IUuidIndexService service;
    private final UuidPreferences preferences;
    private final Map<IProject, IPreferenceChangeListener> projectListeners = new ConcurrentHashMap<>();

    public UuidResourceChangeListener(IUuidIndexService service, UuidPreferences preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (event.getType() == IResourceChangeEvent.PRE_CLOSE
                || event.getType() == IResourceChangeEvent.PRE_DELETE) {
            if (event.getResource() instanceof IProject project) {
                service.removeProject(project);
                removeProjectListener(project);
            }
            return;
        }
        if (event.getType() != IResourceChangeEvent.POST_CHANGE || event.getDelta() == null) {
            return;
        }
        Set<IProject> touched = new LinkedHashSet<>();
        try {
            event.getDelta().accept(delta -> visit(delta, touched));
        } catch (CoreException e) {
            UuidCheckPlugin.logError("Ошибка обхода дельты ресурсов", e);
        }
        for (IProject project : touched) {
            ensureProjectListener(project);
            service.requestScan(project, false);
        }
    }

    private boolean visit(IResourceDelta delta, Set<IProject> touched) {
        IResource resource = delta.getResource();
        int type = resource.getType();
        if (type == IResource.PROJECT) {
            IProject project = (IProject) resource;
            if (delta.getKind() == IResourceDelta.REMOVED) {
                service.removeProject(project);
                removeProjectListener(project);
                return false;
            }
            if (delta.getKind() == IResourceDelta.CHANGED
                    && (delta.getFlags() & IResourceDelta.OPEN) != 0
                    && project.isAccessible()) {
                touched.add(project);
            }
            return true;
        }
        if (type == IResource.FOLDER) {
            return !preferences.excludeDirs().contains(resource.getName().toLowerCase(Locale.ROOT));
        }
        if (type == IResource.FILE) {
            if (resource.isDerived() || resource.isTeamPrivateMember()) {
                return true;
            }
            IProject project = resource.getProject();
            if (!project.isAccessible()
                    || !preferences.enabled(project)
                    || !ProjectUtils.isEdtProject(project)) {
                return true;
            }
            if (!preferences.includeLinked() && resource.isLinked()) {
                return true;
            }
            if (ProjectUtils.hasExtension((IFile) resource, preferences.extensions(project))) {
                touched.add(project);
            }
        }
        return true;
    }

    private void ensureProjectListener(IProject project) {
        projectListeners.computeIfAbsent(project, key -> {
            IEclipsePreferences node = new ProjectScope(key).getNode(UuidCheckPlugin.PLUGIN_ID);
            IPreferenceChangeListener listener = event -> {
                String changed = event.getKey();
                if (UuidPreferences.SCAN_ENABLED.equals(changed)
                        || UuidPreferences.SCAN_EXTENSIONS.equals(changed)
                        || UuidPreferences.INTRA_FILE_MODE.equals(changed)) {
                    service.requestScan(key, false);
                }
            };
            node.addPreferenceChangeListener(listener);
            return listener;
        });
    }

    private void removeProjectListener(IProject project) {
        IPreferenceChangeListener listener = projectListeners.remove(project);
        if (listener != null) {
            try {
                new ProjectScope(project).getNode(UuidCheckPlugin.PLUGIN_ID)
                        .removePreferenceChangeListener(listener);
            } catch (IllegalStateException ignored) {
                // проект уже удалён
            }
        }
    }
}
