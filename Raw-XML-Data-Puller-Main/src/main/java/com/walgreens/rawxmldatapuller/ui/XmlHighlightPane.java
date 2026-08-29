package com.walgreens.rawxmldatapuller.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only XML viewer with VS Code–style syntax highlighting.
 *
 * <p>Uses a {@link TextFlow} of coloured {@link Text} nodes — no external
 * dependencies.  Two colour palettes (light / dark) match the app themes.</p>
 *
 * <p>All public methods are thread-safe: UI mutations are dispatched via
 * {@link Platform#runLater}.</p>
 */
public class XmlHighlightPane extends VBox {

    // ---- Fonts ----
    private static final Font FONT      = Font.font("Consolas", 13.0);
    private static final Font FONT_ITALIC = Font.font("Consolas", FontPosture.ITALIC, 13.0);

    // ---- Light palette (Atom One Light) ----
    private static final Color L_PUNCT   = Color.web("#777777");
    private static final Color L_TAG     = Color.web("#E45649");
    private static final Color L_ATTR_N  = Color.web("#4078F2");
    private static final Color L_ATTR_V  = Color.web("#50A14F");
    private static final Color L_COMMENT = Color.web("#9CA3AF");
    private static final Color L_PI      = Color.web("#986801");
    private static final Color L_CDATA   = Color.web("#986801");
    private static final Color L_TEXT    = Color.web("#383A42");

    // ---- Dark palette (Atom One Dark) ----
    private static final Color D_PUNCT   = Color.web("#ABB2BF");
    private static final Color D_TAG     = Color.web("#E06C75");
    private static final Color D_ATTR_N  = Color.web("#61AFEF");
    private static final Color D_ATTR_V  = Color.web("#98C379");
    private static final Color D_COMMENT = Color.web("#5C6370");
    private static final Color D_PI      = Color.web("#E5C07B");
    private static final Color D_CDATA   = Color.web("#D19A66");
    private static final Color D_TEXT    = Color.web("#ABB2BF");

    // ---- Tokeniser patterns ----
    private static final Pattern COMMENT_PAT  = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern CDATA_PAT    = Pattern.compile("(?s)<!\\[CDATA\\[.*?\\]\\]>");
    private static final Pattern PI_PAT       = Pattern.compile("(?s)<\\?.*?\\?>");
    private static final Pattern DOCTYPE_PAT  = Pattern.compile("(?s)<!(?!--)\\w[^>]*>");
    private static final Pattern CLOSE_PAT    = Pattern.compile("</([\\w:.-]+)>");
    private static final Pattern OPEN_PAT     = Pattern.compile("(?s)<([\\w:.-]+)((?:\\s+[\\w:.-]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*)\\s*(/?)>");
    private static final Pattern ATTR_PAT     = Pattern.compile(
            "(?s)(\\s+)|([\\w:.-]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')|([\\w:.-]+)");

    private final TextFlow   flow;
    private final ScrollPane scroll;

    /** Constructs the pane and wires its internal layout. */
    public XmlHighlightPane() {
        getStyleClass().add("xml-highlight-pane");
        VBox.setVgrow(this, Priority.ALWAYS);

        flow = new TextFlow();
        flow.setLineSpacing(2.5);
        flow.setPadding(new Insets(14, 18, 18, 18));
        flow.getStyleClass().add("xml-highlight-flow");

        scroll = new ScrollPane(flow);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("xml-highlight-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().add(scroll);
    }

    // ---- Public API -------------------------------------------------------

    /**
     * Replaces all displayed content with syntax-highlighted XML.
     * Safe to call from any thread.
     *
     * @param xml      the XML string to display
     * @param darkMode {@code true} for dark colour palette
     */
    public void setXml(String xml, boolean darkMode) {
        List<Text> nodes = (xml == null || xml.isBlank())
                ? List.of() : parse(xml, darkMode);
        Platform.runLater(() -> {
            flow.getChildren().setAll(nodes);
            scroll.setVvalue(0);
        });
    }

    /** Clears the display. Safe to call from any thread. */
    public void clear() {
        Platform.runLater(() -> flow.getChildren().clear());
    }

    // ---- Tokeniser --------------------------------------------------------

    private List<Text> parse(String xml, boolean dark) {
        List<Text> out = new ArrayList<>(512);
        int pos = 0;
        int len = xml.length();

        while (pos < len) {
            // ---- comment ----
            if (match(COMMENT_PAT, xml, pos)) {
                Matcher m = COMMENT_PAT.matcher(xml);
                m.find(pos);
                out.add(t(m.group(), dark ? D_COMMENT : L_COMMENT, true));
                pos = m.end();
                continue;
            }
            // ---- CDATA ----
            if (match(CDATA_PAT, xml, pos)) {
                Matcher m = CDATA_PAT.matcher(xml);
                m.find(pos);
                out.add(t(m.group(), dark ? D_CDATA : L_CDATA, false));
                pos = m.end();
                continue;
            }
            // ---- PI / XML declaration ----
            if (match(PI_PAT, xml, pos)) {
                Matcher m = PI_PAT.matcher(xml);
                m.find(pos);
                out.add(t(m.group(), dark ? D_PI : L_PI, false));
                pos = m.end();
                continue;
            }
            // ---- DOCTYPE ----
            if (match(DOCTYPE_PAT, xml, pos)) {
                Matcher m = DOCTYPE_PAT.matcher(xml);
                m.find(pos);
                out.add(t(m.group(), dark ? D_PI : L_PI, false));
                pos = m.end();
                continue;
            }
            // ---- closing tag ----
            if (match(CLOSE_PAT, xml, pos)) {
                Matcher m = CLOSE_PAT.matcher(xml);
                m.find(pos);
                out.add(t("</",        dark ? D_PUNCT : L_PUNCT, false));
                out.add(t(m.group(1),  dark ? D_TAG   : L_TAG,   false));
                out.add(t(">",         dark ? D_PUNCT : L_PUNCT, false));
                pos = m.end();
                continue;
            }
            // ---- opening / self-closing tag ----
            if (match(OPEN_PAT, xml, pos)) {
                Matcher m = OPEN_PAT.matcher(xml);
                m.find(pos);
                out.add(t("<",         dark ? D_PUNCT : L_PUNCT, false));
                out.add(t(m.group(1),  dark ? D_TAG   : L_TAG,   false));
                if (m.group(2) != null && !m.group(2).isEmpty()) {
                    out.addAll(parseAttrs(m.group(2), dark));
                }
                boolean selfClose = "/".equals(m.group(3));
                out.add(t(selfClose ? "/>" : ">", dark ? D_PUNCT : L_PUNCT, false));
                pos = m.end();
                continue;
            }
            // ---- text content ----
            int next = xml.indexOf('<', pos);
            if (next == -1) next = len;
            if (next > pos) {
                out.add(t(xml.substring(pos, next), dark ? D_TEXT : L_TEXT, false));
                pos = next;
            } else {
                // unrecognised '<' — output raw character
                out.add(t("<", dark ? D_PUNCT : L_PUNCT, false));
                pos++;
            }
        }
        return out;
    }

    private List<Text> parseAttrs(String attrStr, boolean dark) {
        List<Text> out = new ArrayList<>();
        Matcher m = ATTR_PAT.matcher(attrStr);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(t(attrStr.substring(last, m.start()), dark ? D_PUNCT : L_PUNCT, false));
            }
            last = m.end();

            if (m.group(1) != null) {
                // whitespace
                out.add(t(m.group(1), dark ? D_PUNCT : L_PUNCT, false));
            } else if (m.group(2) != null) {
                // attr="value"
                out.add(t(m.group(2),                       dark ? D_ATTR_N : L_ATTR_N, false));
                out.add(t(m.group(3),                       dark ? D_PUNCT  : L_PUNCT,  false));
                out.add(t(m.group(4),                       dark ? D_ATTR_V : L_ATTR_V, false));
            } else if (m.group(5) != null) {
                // standalone attr (no value)
                out.add(t(m.group(5), dark ? D_ATTR_N : L_ATTR_N, false));
            }
        }
        if (last < attrStr.length()) {
            out.add(t(attrStr.substring(last), dark ? D_PUNCT : L_PUNCT, false));
        }
        return out;
    }

    // ---- Helpers ----------------------------------------------------------

    private static boolean match(Pattern p, String s, int pos) {
        Matcher m = p.matcher(s);
        m.region(pos, s.length());
        return m.lookingAt();
    }

    private Text t(String content, Color fill, boolean italic) {
        Text node = new Text(content);
        node.setFill(fill);
        node.setFont(italic ? FONT_ITALIC : FONT);
        return node;
    }
}

