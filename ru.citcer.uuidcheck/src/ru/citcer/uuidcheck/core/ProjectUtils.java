/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.util.Locale;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

/**
 * Определение «это проект 1С:EDT». Решение от 2026-11-06:
 * {@code DT-INF/PROJECT.PMF} ИЛИ {@code src/Configuration/Configuration.mdo}
 * ИЛИ хотя бы один {@code .mdo} под {@code src}.
 */
public final class ProjectUtils {

    private ProjectUtils() {
    }

    public static boolean isEdtProject(IProject project) {
        if (project == null || !project.exists() || !project.isAccessible()) {
            return false;
        }
        if (project.getFile(new Path("DT-INF/PROJECT.PMF")).exists()) {
            return true;
        }
        if (project.getFile(new Path("src/Configuration/Configuration.mdo")).exists()) {
            return true;
        }
        IContainer src = project.getFolder("src");
        if (src.exists()) {
            final boolean[] found = {false};
            try {
                src.accept((IResourceVisitor) resource -> {
                    if (resource.getType() == IResource.FILE
                            && resource.getName().toLowerCase(Locale.ROOT).endsWith(".mdo")) {
                        found[0] = true;
                        return false;
                    }
                    return !found[0];
                });
            } catch (CoreException ignored) {
                // нет доступа — считаем, что признаков не нашли
            }
            return found[0];
        }
        return false;
    }

    public static boolean hasExtension(IFile file, java.util.Set<String> extensions) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && extensions.contains(name.substring(dot + 1));
    }
}
