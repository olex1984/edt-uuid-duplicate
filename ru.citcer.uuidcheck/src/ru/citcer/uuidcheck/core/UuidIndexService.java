/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;

import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.scan.MdoUuidScanner;
import ru.citcer.uuidcheck.scan.ScanResult;
import ru.citcer.uuidcheck.scan.UuidOccurrence;

/** Реализация фасада индекса: RW-lock, снимки, планирование, кэш. */
public final class UuidIndexService implements IUuidIndexService {

    private static final long SAVE_THROTTLE_MS = 5000;

    private final UuidPreferences preferences = new UuidPreferences();
    private final MdoUuidScanner scanner = new MdoUuidScanner();
    private final DuplicateAnalyzer analyzer = new DuplicateAnalyzer();
    private final PersistedIndexStore store;
    private final ScanScheduler scheduler;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<IProject, ProjectUuidIndex> indexes = new HashMap<>();
    private final Map<IProject, Map<IPath, FileIndexEntry>> cache = new HashMap<>();
    private final CopyOnWriteArrayList<IUuidIndexListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong generation = new AtomicLong();
    private volatile long lastSave;

    public UuidIndexService(UuidCheckPlugin plugin) {
        PersistedIndexStore createdStore = null;
        try {
            createdStore = new PersistedIndexStore(plugin.getStateLocation().toFile().toPath());
        } catch (RuntimeException e) {
            UuidCheckPlugin.logError("State location недоступен, кэш индекса отключён", e);
        }
        this.store = createdStore;
        this.scheduler = new ScanScheduler(this, preferences);
        if (store != null) {
            loadCache();
        }
    }

    @Override
    public IndexSnapshot snapshot(IProject project) {
        lock.readLock().lock();
        try {
            ProjectUuidIndex index = indexes.get(project);
            return index == null ? null : index.snapshot(project, generation.get());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<IndexSnapshot> snapshots() {
        List<IndexSnapshot> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            long currentGeneration = generation.get();
            for (Map.Entry<IProject, ProjectUuidIndex> entry : indexes.entrySet()) {
                if (entry.getKey().isAccessible()) {
                    result.add(entry.getValue().snapshot(entry.getKey(), currentGeneration));
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        result.sort(Comparator.comparing(snapshot -> snapshot.project().getName()));
        return result;
    }

    @Override
    public void rescan(IProject project, boolean ignoreCache, IProgressMonitor monitor) {
        if (project == null || !project.exists() || !project.isAccessible()) {
            return;
        }
        // Ручной скан (ignoreCache=true) работает и при выключенном автоскане проекта.
        if (!ProjectUtils.isEdtProject(project)
                || (!preferences.enabled(project) && !ignoreCache)) {
            return;
        }
        if (monitor != null && monitor.isCanceled()) {
            return;
        }

        List<IFile> files;
        try {
            files = collectFiles(project);
        } catch (CoreException e) {
            UuidCheckPlugin.logError("Не удалось собрать файлы проекта " + project.getName(), e);
            return;
        }

        SubMonitor subMonitor = SubMonitor.convert(monitor, files.size() + 1);
        subMonitor.setTaskName("Проверка дублей UUID: " + project.getName());

        Map<IPath, FileIndexEntry> cached = new HashMap<>();
        lock.readLock().lock();
        try {
            Map<IPath, FileIndexEntry> stored = cache.get(project);
            if (stored != null) {
                cached.putAll(stored);
            }
        } finally {
            lock.readLock().unlock();
        }

        Map<IPath, FileIndexEntry> entries = new LinkedHashMap<>();
        for (IFile file : files) {
            if (subMonitor.isCanceled()) {
                return;
            }
            subMonitor.split(1);
            IPath relative = file.getProjectRelativePath();
            long stamp = file.getModificationStamp();
            long size = sizeOf(file);
            FileIndexEntry previous = ignoreCache ? null : cached.get(relative);
            if (previous != null && previous.modificationStamp() == stamp && previous.size() == size) {
                entries.put(relative, previous);
                continue;
            }
            ScanResult result = scanner.scan(file);
            if (result.isCancelled()) {
                return;
            }
            entries.put(relative, new FileIndexEntry(stamp, size, result.occurrences(), result.failure()));
        }
        subMonitor.split(1);

        ProjectUuidIndex index = new ProjectUuidIndex();
        for (Map.Entry<IPath, FileIndexEntry> entry : entries.entrySet()) {
            index.replaceFile(entry.getKey(), entry.getValue());
        }

        ProjectUuidIndex previousIndex;
        lock.writeLock().lock();
        try {
            previousIndex = indexes.put(project, index);
            cache.put(project, entries);
            generation.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }

        Set<IPath> changed = changedFiles(previousIndex, index);
        Set<String> affected = affectedUuids(previousIndex, index);
        boolean full = previousIndex == null || ignoreCache
                || changed.size() > preferences.dirtyFileThreshold();
        notifyListeners(new IndexChange(project, affected, changed, full));
        saveCache(false);
    }

    @Override
    public void requestScan(IProject project, boolean ignoreCache) {
        scheduler.request(project, ignoreCache);
    }

    @Override
    public void removeProject(IProject project) {
        if (project == null) {
            return;
        }
        ProjectUuidIndex removed;
        lock.writeLock().lock();
        try {
            removed = indexes.remove(project);
            cache.remove(project);
        } finally {
            lock.writeLock().unlock();
        }
        if (removed != null) {
            notifyListeners(new IndexChange(project, Set.of(), Set.of(), true));
        }
        saveCache(false);
    }

    @Override
    public void addListener(IUuidIndexListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeListener(IUuidIndexListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Set<IProject> indexedProjects() {
        Set<IProject> result = new HashSet<>();
        lock.readLock().lock();
        try {
            for (IProject project : indexes.keySet()) {
                if (project.isAccessible()) {
                    result.add(project);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    @Override
    public void flushCache() {
        saveCache(true);
    }

    @Override
    public void shutdown() {
        scheduler.shutdown();
        saveCache(true);
    }

    /** Группы дублей проекта на текущий момент. */
    public List<DuplicateGroup> groups(IProject project) {
        IndexSnapshot snapshot = snapshot(project);
        if (snapshot == null) {
            return List.of();
        }
        return analyzer.analyze(snapshot, preferences.intraFileMode(project));
    }

    private void loadCache() {
        if (store == null) {
            return;
        }
        try {
            Map<String, Map<IPath, FileIndexEntry>> loaded = store.load();
            if (loaded.isEmpty()) {
                return;
            }
            lock.writeLock().lock();
            try {
                for (Map.Entry<String, Map<IPath, FileIndexEntry>> entry : loaded.entrySet()) {
                    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(entry.getKey());
                    if (project.exists() && project.isAccessible()) {
                        cache.put(project, entry.getValue());
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            UuidCheckPlugin.logWarning("Кэш индекса не прочитан: " + e.getMessage());
        } catch (IllegalStateException e) {
            UuidCheckPlugin.logWarning("Workspace недоступен при загрузке кэша: " + e.getMessage());
        }
    }

    private void saveCache(boolean force) {
        if (store == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastSave < SAVE_THROTTLE_MS) {
            return;
        }
        lastSave = now;
        Map<IProject, ProjectUuidIndex> copy;
        lock.readLock().lock();
        try {
            copy = new HashMap<>(indexes);
        } finally {
            lock.readLock().unlock();
        }
        if (copy.isEmpty() && !force) {
            return;
        }
        try {
            store.save(copy);
        } catch (IOException e) {
            UuidCheckPlugin.logError("Не удалось сохранить кэш индекса", e);
        }
    }

    private List<IFile> collectFiles(IProject project) throws CoreException {
        Set<String> extensions = preferences.extensions(project);
        Set<String> excludedDirs = preferences.excludeDirs();
        boolean includeLinked = preferences.includeLinked();
        List<IFile> files = new ArrayList<>();
        project.accept((IResourceVisitor) resource -> {
            if (resource.getType() == IResource.FOLDER) {
                if (excludedDirs.contains(resource.getName().toLowerCase(Locale.ROOT))) {
                    return false;
                }
                return includeLinked || !resource.isLinked();
            }
            if (resource.getType() == IResource.FILE) {
                if (resource.isDerived() || resource.isTeamPrivateMember()) {
                    return true;
                }
                if (!includeLinked && resource.isLinked()) {
                    return true;
                }
                IFile file = (IFile) resource;
                if (ProjectUtils.hasExtension(file, extensions)) {
                    files.add(file);
                }
            }
            return true;
        });
        files.sort(Comparator.comparing(file -> file.getProjectRelativePath().toString()));
        return files;
    }

    private static long sizeOf(IFile file) {
        IPath location = file.getLocation();
        if (location != null) {
            java.io.File local = location.toFile();
            if (local.exists()) {
                return local.length();
            }
        }
        return 0L;
    }

    private static Set<IPath> changedFiles(ProjectUuidIndex previous, ProjectUuidIndex current) {
        Set<IPath> changed = new HashSet<>();
        if (previous == null) {
            changed.addAll(current.byFile().keySet());
            return changed;
        }
        for (Map.Entry<IPath, FileIndexEntry> entry : current.byFile().entrySet()) {
            if (!Objects.equals(previous.byFile().get(entry.getKey()), entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (IPath path : previous.byFile().keySet()) {
            if (!current.byFile().containsKey(path)) {
                changed.add(path);
            }
        }
        return changed;
    }

    private static Set<String> affectedUuids(ProjectUuidIndex previous, ProjectUuidIndex current) {
        Set<String> affected = new HashSet<>();
        if (previous == null) {
            affected.addAll(current.byUuid().keySet());
            return affected;
        }
        for (String uuid : current.byUuid().keySet()) {
            if (!Objects.equals(previous.byUuid().get(uuid), current.byUuid().get(uuid))) {
                affected.add(uuid);
            }
        }
        for (String uuid : previous.byUuid().keySet()) {
            if (!current.byUuid().containsKey(uuid)) {
                affected.add(uuid);
            }
        }
        return affected;
    }

    private void notifyListeners(IndexChange change) {
        for (IUuidIndexListener listener : listeners) {
            try {
                listener.indexChanged(change);
            } catch (RuntimeException e) {
                UuidCheckPlugin.logError("Ошибка слушателя индекса", e);
            }
        }
    }
}
