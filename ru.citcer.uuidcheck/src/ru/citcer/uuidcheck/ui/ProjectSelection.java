/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.IUuidIndexService;

/** Извлечение проекта из контекста команды. */
public final class ProjectSelection {

    private ProjectSelection() {
    }

    public static IProject from(ExecutionEvent event) {
        IProject project = from(HandlerUtil.getCurrentSelection(event));
        if (project != null) {
            return project;
        }
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor != null && editor.getEditorInput() != null) {
            IResource resource = Adapters.adapt(editor.getEditorInput(), IResource.class);
            if (resource != null) {
                return resource.getProject();
            }
        }
        return firstIndexed();
    }

    public static IProject from(ISelection selection) {
        if (selection instanceof IStructuredSelection structured) {
            Object element = structured.getFirstElement();
            IProject project = Adapters.adapt(element, IProject.class);
            if (project != null) {
                return project;
            }
            if (element instanceof IResource resource) {
                return resource.getProject();
            }
        }
        return null;
    }

    public static IProject firstIndexed() {
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        if (service == null) {
            return null;
        }
        for (IProject project : service.indexedProjects()) {
            return project;
        }
        return null;
    }
}
