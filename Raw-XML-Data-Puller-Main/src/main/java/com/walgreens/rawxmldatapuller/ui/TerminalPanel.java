package com.walgreens.rawxmldatapuller.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Terminal-style execution log panel containing three collapsible
 * {@link MethodBlock} accordion sections (one per retrieval method), an info
 * area above them, and a summary line below.
 *
 * <p>All public methods are thread-safe — they dispatch UI mutations via
 * {@link Platform#runLater}.</p>
 *
 * @see MethodBlock
 * @see TerminalLine
 */
public class TerminalPanel extends VBox {

    private final VBox        infoBox;
    private final MethodBlock block1;
    private final MethodBlock block2;
    private final MethodBlock block3;
    private final MethodBlock block4;
    private final VBox        outerContent;
    private final ScrollPane  scroll;

    public TerminalPanel() {
        getStyleClass().add("terminal-panel");
        VBox.setVgrow(this, Priority.ALWAYS);

        infoBox = new VBox(3);
        infoBox.getStyleClass().add("terminal-info-box");
        infoBox.setPadding(new Insets(12, 16, 8, 16));

        block1 = new MethodBlock("METHOD 1");
        block2 = new MethodBlock("METHOD 2");
        block3 = new MethodBlock("METHOD 3");
        block4 = new MethodBlock("METHOD 4");

        outerContent = new VBox(0);
        outerContent.getStyleClass().add("terminal-lines");
        outerContent.getChildren().addAll(infoBox, block1, block2, block3, block4);

        scroll = new ScrollPane(outerContent);
        scroll.getStyleClass().add("terminal-scroll");
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().add(scroll);
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Clears info lines, resets all three blocks to PENDING/collapsed,
     * and removes any summary lines added below the blocks.
     */
    public void clear() {
        Platform.runLater(() -> infoBox.getChildren().clear());
        block1.reset();
        block2.reset();
        block3.reset();
        block4.reset();
        Platform.runLater(() ->
                outerContent.getChildren().removeIf(n -> n.getStyleClass().contains("terminal-summary")));
    }

    /** Adds a blue informational line in the top info section. */
    public void addInfo(String message) {
        Platform.runLater(() -> {
            Label lbl = new Label("ℹ  " + message);
            lbl.getStyleClass().addAll("terminal-msg", "t-msg-info");
            lbl.setWrapText(true);
            infoBox.getChildren().add(lbl);
            nudgeScroll();
        });
    }

    /** Appends a green success summary line below all three method blocks. */
    public void addSuccess(String message) {
        appendSummary("✓  " + message, "t-msg-success");
    }

    /** Appends a red failure summary line below all three method blocks. */
    public void addFailed(String message) {
        appendSummary("✗  " + message, "t-msg-failed");
    }

    public MethodBlock getBlock1() { return block1; }
    public MethodBlock getBlock2() { return block2; }
    public MethodBlock getBlock3() { return block3; }
    public MethodBlock getBlock4() { return block4; }

    // ----------------------------------------------------------------

    private void appendSummary(String text, String colorClass) {
        Platform.runLater(() -> {
            Label lbl = new Label(text);
            lbl.getStyleClass().addAll("terminal-msg", colorClass, "terminal-summary");
            lbl.setWrapText(true);
            lbl.setPadding(new Insets(8, 16, 8, 16));
            outerContent.getChildren().add(lbl);
            nudgeScroll();
        });
    }

    private void nudgeScroll() {
        Platform.runLater(() -> scroll.setVvalue(1.0));
    }
}

