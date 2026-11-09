/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import org.eclipse.core.runtime.Path;
import org.junit.Test;

import ru.citcer.uuidcheck.scan.MdoUuidScanner;
import ru.citcer.uuidcheck.scan.ScanResult;
import ru.citcer.uuidcheck.scan.UuidOccurrence;

/** Unit-тесты парсера на фикстурах §8.1. */
public class UuidXmlParserTest {

    private static final String UUID = "7c9e4a1b-0000-4000-8000-000000000001";

    private static ScanResult parse(String name) throws Exception {
        try (InputStream in = new FileInputStream(new File("testdata", name))) {
            return new MdoUuidScanner().parse(new Path(name), in);
        }
    }

    @Test
    public void simple() throws Exception {
        List<UuidOccurrence> found = parse("simple.mdo").occurrences();
        assertEquals(1, found.size());
        assertEquals(UUID, found.get(0).uuid());
        assertEquals(2, found.get(0).line());
    }

    @Test
    public void nested() throws Exception {
        assertEquals(2, parse("nested.mdo").occurrences().size());
    }

    @Test
    public void uppercaseAttributeIsNormalized() throws Exception {
        List<UuidOccurrence> found = parse("uppercase-attr.mdo").occurrences();
        assertEquals(1, found.size());
        assertEquals(UUID, found.get(0).uuid());
    }

    @Test
    public void singleQuotes() throws Exception {
        assertEquals(1, parse("single-quotes.mdo").occurrences().size());
    }

    @Test
    public void spacesAroundEquals() throws Exception {
        assertEquals(1, parse("spaces.mdo").occurrences().size());
    }

    @Test
    public void refAttributesAreIgnored() throws Exception {
        assertEquals(0, parse("ref-only.mdo").occurrences().size());
    }

    @Test
    public void commentIsIgnored() throws Exception {
        assertEquals(0, parse("comment-uuid.mdo").occurrences().size());
    }

    @Test
    public void textIsIgnored() throws Exception {
        assertEquals(0, parse("text-uuid.mdo").occurrences().size());
    }

    @Test
    public void lookalikeAttributeIsIgnored() throws Exception {
        assertEquals(0, parse("myuuid-attr.mdo").occurrences().size());
    }

    @Test
    public void malformedXmlFailsSoftly() throws Exception {
        ScanResult result = parse("malformed.mdo");
        assertFalse(result.isOk());
        assertNotNull(result.failure());
    }

    @Test
    public void utf8BomIsHandled() throws Exception {
        assertEquals(1, parse("bom.mdo").occurrences().size());
    }

    @Test
    public void windows1251IsHandled() throws Exception {
        assertEquals(1, parse("cp1251.mdo").occurrences().size());
    }

    @Test
    public void emptyFileIsOk() throws Exception {
        ScanResult result = parse("empty.mdo");
        assertTrue(result.isOk());
        assertEquals(0, result.occurrences().size());
    }

    @Test
    public void bigFile() throws Exception {
        ScanResult result = parse("big.mdo");
        assertEquals(10001, result.occurrences().size());
    }
}
