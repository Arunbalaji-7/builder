package com.walgreens.rawxmldatapuller.util;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Utility class for XML formatting, validation, and binary-content detection.
 *
 * <p>All methods are stateless and thread-safe.
 *
 * <h3>Security</h3>
 * DTD processing is disabled ({@code disallow-doctype-decl}) to prevent
 * XXE (XML External Entity) injection attacks.
 */
public class XmlFormatter {

    private XmlFormatter() {}

    /**
     * Formats raw XML with 2-space indentation using the JDK's built-in
     * {@link javax.xml.transform.Transformer}.
     *
     * <p>If the input is not valid XML, the original string is returned unchanged
     * so the caller can still display it.
     *
     * @param rawXml the raw XML string (may be unindented or single-line)
     * @return a pretty-printed XML string, or the original input on parse failure
     */
    public static String format(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) return "";
        String trimmed = rawXml.trim();
        int xmlStart = trimmed.indexOf('<');
        if (xmlStart < 0)  return rawXml;
        if (xmlStart > 0)  trimmed = trimmed.substring(xmlStart);

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(true);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(trimmed)));

            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.INDENT,   "yes");
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter sw = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            return rawXml;
        }
    }

    /**
     * Returns {@code true} if the given string is well-formed XML.
     *
     * @param content the string to check
     * @return {@code true} if {@link DocumentBuilder#parse} succeeds
     */
    public static boolean isValidXml(String content) {
        if (content == null || content.isBlank()) return false;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.newDocumentBuilder().parse(new InputSource(new StringReader(content.trim())));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Heuristically detects binary or corrupted content by checking the ratio
     * of non-printable characters in the first 2 000 chars of the input.
     *
     * <p>A ratio greater than 2% is treated as binary. This catches Oracle LOBs
     * that have been written with non-XML binary data while still allowing
     * control characters such as tabs and newlines.</p>
     *
     * @param content the string to inspect
     * @return {@code true} if the content appears to be binary/corrupted
     */
    public static boolean isBinaryContent(String content) {
        if (content == null || content.isEmpty()) return false;
        int sample = Math.min(content.length(), 2000);
        long nonPrintable = content.chars().limit(sample)
                .filter(c -> c < 32 && c != '\t' && c != '\n' && c != '\r')
                .count();
        return (double) nonPrintable / sample > 0.02;
    }
}

