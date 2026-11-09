/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

import ru.citcer.uuidcheck.core.DuplicateAnalyzer;
import ru.citcer.uuidcheck.core.DuplicateGroup;
import ru.citcer.uuidcheck.core.DuplicateKind;
import ru.citcer.uuidcheck.core.IndexSnapshot;
import ru.citcer.uuidcheck.core.IntraFileMode;
import ru.citcer.uuidcheck.export.CsvExporter;
import ru.citcer.uuidcheck.export.TextReportExporter;

/** Сверка с эталонными экспортами на «чистом» наборе фикстур. */
public class ParityTest {

    private static final File ROOT = new File("testdata/parity");

    @Test
    public void reportMatchesReference() throws Exception {
        IndexSnapshot snapshot = ParityMain.buildSnapshot(ROOT);
        List<DuplicateGroup> groups = new DuplicateAnalyzer().analyze(snapshot, IntraFileMode.IGNORE);
        String expected = Files.readString(new File("testdata/expected_report.txt").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(expected, new TextReportExporter().build(snapshot, groups));
    }

    @Test
    public void csvMatchesReference() throws Exception {
        IndexSnapshot snapshot = ParityMain.buildSnapshot(ROOT);
        List<DuplicateGroup> groups = new DuplicateAnalyzer().analyze(snapshot, IntraFileMode.IGNORE);
        File temp = File.createTempFile("uuid-parity", ".csv");
        try {
            new CsvExporter().export(new org.eclipse.core.runtime.Path(temp.getAbsolutePath()),
                    groups, new CsvExporter.Options());
            String expected = Files.readString(new File("testdata/expected.csv").toPath(),
                    StandardCharsets.UTF_8);
            assertEquals(expected, Files.readString(temp.toPath(), StandardCharsets.UTF_8));
        } finally {
            assertTrue(temp.delete() || !temp.exists());
        }
    }

    @Test
    public void intraFileIsDetectedAsSeparateKind() throws Exception {
        IndexSnapshot snapshot = ParityMain.buildSnapshot(new File("testdata"));
        List<DuplicateGroup> groups = new DuplicateAnalyzer().analyze(snapshot, IntraFileMode.WARN);
        long intra = groups.stream().filter(g -> g.kind() == DuplicateKind.INTRA_FILE).count();
        assertTrue("ожидалась хотя бы одна внутрифайловая группа", intra >= 1);
    }
}
