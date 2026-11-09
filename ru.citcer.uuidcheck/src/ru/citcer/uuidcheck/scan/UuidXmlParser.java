/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.scan;

import java.io.InputStream;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Потоковый разбор XML: извлекает только собственный атрибут {@code uuid}
 * START-элементов. Атрибуты-ссылки ({@code ref}, {@code typeId} и т.п.),
 * текст и комментарии не участвуют.
 */
public final class UuidXmlParser {

    /** Строгий канонический UUID 8-4-4-4-12 hex. */
    private static final Pattern STRICT_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UuidXmlParser() {
    }

    /**
     * Читает XML целиком, вызывая {@code sink} для каждого элемента с корректным
     * атрибутом {@code uuid}.
     *
     * @throws XMLStreamException если XML невалиден
     */
    public static void parse(InputStream in, Consumer<RawOccurrence> sink) throws XMLStreamException {
        XMLStreamReader reader = newFactory().createXMLStreamReader(in);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String uuid = ownUuid(reader);
                if (uuid == null) {
                    continue;
                }
                Location loc = reader.getLocation();
                String qName = reader.getName().toString();
                int charStart = loc.getCharacterOffset();
                sink.accept(new RawOccurrence(uuid.toLowerCase(Locale.ROOT),
                        reader.getLocalName(), qName, loc.getLineNumber(),
                        charStart, charStart + qName.length()));
            }
        } finally {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // закрытие входного потока выполнит вызывающая сторона
            }
        }
    }

    private static String ownUuid(XMLStreamReader reader) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if ("uuid".equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                String value = reader.getAttributeValue(i);
                if (value == null) {
                    return null;
                }
                value = value.trim();
                return STRICT_UUID.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", Boolean.FALSE);
        setIfSupported(factory, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        setIfSupported(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        return factory;
    }

    private static void setIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // часть реализаций StAX не поддерживает конкретное свойство
        }
    }
}
