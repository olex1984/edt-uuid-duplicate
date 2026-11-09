/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.tests;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.IPath;

import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.FileIndexEntry;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IntraFileMode;
import ru.citcer.uuidcheck.core.ProjectUuidIndex;
import ru.citcer.uuidcheck.export.CsvExporter;
import ru.citcer.uuidcheck.export.TextReportExporter;
import ru.citcer.uuidcheck.scan.MdoUuidScanner;
import ru.citcer.uuidcheck.scan.ScanResult;

/** Построение индекса по каталогу и прогон экспорта для сверки с эталоном. */
public final class ParityMain {

    private ParityMain() {
    }

    public static IndexSnapshot buildSnapshot(File root) throws Exception {
        List<File> files = new ArrayList<>();
        walk(root, files);
        ProjectUuidIndex index = new ProjectUuidIndex();
        MdoUuidScanner scanner = new MdoUuidScanner();
        for (File file : files) {
            String relative = root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
            IPath path = new org.eclipse.core.runtime.Path(relative);
            try (InputStream in = new FileInputStream(file)) {
                ScanResult result = scanner.parse(path, in);
                index.replaceFile(path,
                        new FileIndexEntry(0L, file.length(), result.occurrences(), result.failure()));
            }
        }
        return index.snapshot(null, 1L);
    }

    private static void walk(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                if (!".git".equals(child.getName())) {
                    walk(child, out);
                }
            } else {
                String name = child.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".mdo") || name.endsWith(".xml")) {
                    out.add(child);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        IndexSnapshot snapshot = buildSnapshot(root);
        List<DuplicateGroup> groups = new DuplicateAnalyzer().analyze(snapshot, IntraFileMode.IGNORE);
        if (args.length >= 3) {
            new CsvExporter().export(new org.eclipse.core.runtime.Path(args[1]), groups,
                    new CsvExporter.Options());
            new TextReportExporter().export(new org.eclipse.core.runtime.Path(args[2]), snapshot,
                    groups, true);
        }
        System.out.println("files=" + snapshot.fileCount()
                + " unique=" + snapshot.uniqueUuidCount()
                + " groups=" + groups.size());
    }
}
