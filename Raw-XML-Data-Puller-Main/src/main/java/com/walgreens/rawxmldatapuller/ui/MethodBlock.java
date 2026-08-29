package com.walgreens.rawxmldatapuller.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * A collapsible accordion block representing one retrieval method in the
 * execution log.
 *
 * <p>The header row shows the method name, a short description, and a
 * colour-coded status badge.  Clicking the header toggles the content body.
 * The body expands automatically when {@link #setHeaderRunning} is called or
 * when a step line is added via {@link #addRunningStep}.</p>
 *
 * <p>All public methods are thread-safe — UI mutations are dispatched via
 * {@link Platform#runLater}.</p>
 */
public class MethodBlock extends VBox {

    private final Label   chevronLabel;
    private final Label   descLabel;
    private final Label   statusBadge;
    private final VBox    contentBox;

    private volatile boolean           expanded      = false;
    private final    List<TerminalLine> managedLines  = new ArrayList<>();

    public MethodBlock(String methodName) {
        getStyleClass().add("method-block");

        chevronLabel = new Label("▶");
        chevronLabel.getStyleClass().add("method-chevron");
        chevronLabel.setMinWidth(26);  chevronLabel.setMaxWidth(26);
        chevronLabel.setMinHeight(26); chevronLabel.setMaxHeight(26);
        chevronLabel.setAlignment(Pos.CENTER);

        Label methodLabel = new Label(methodName);
        methodLabel.getStyleClass().add("method-block-label");

        descLabel = new Label("");
        descLabel.getStyleClass().add("method-block-desc");
        HBox.setHgrow(descLabel, Priority.ALWAYS);

        statusBadge = new Label("PENDING");
        statusBadge.getStyleClass().addAll("method-badge", "badge-pending");

        HBox headerRow = new HBox(10, chevronLabel, methodLabel, descLabel, statusBadge);
        headerRow.getStyleClass().add("method-block-header");
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setOnMouseClicked(e -> toggleExpand());

        contentBox = new VBox(2);
        contentBox.getStyleClass().add("method-block-content");
        contentBox.setVisible(false);
        contentBox.setManaged(false);

        getChildren().addAll(headerRow, contentBox);
    }

    // ----------------------------------------------------------------
    //  Header state transitions
    // ----------------------------------------------------------------

    public void setHeaderRunning(String description) {
        Platform.runLater(() -> {
            descLabel.setText(description);
            statusBadge.setText("RUNNING");
            statusBadge.getStyleClass().setAll("method-badge", "badge-running");
        });
        expand();
    }

    public void setHeaderSuccess() {
        Platform.runLater(() -> {
            statusBadge.setText("COMPLETED");
            statusBadge.getStyleClass().setAll("method-badge", "badge-success");
        });
    }

    public void setHeaderFailed() {
        Platform.runLater(() -> {
            statusBadge.setText("FAILED");
            statusBadge.getStyleClass().setAll("method-badge", "badge-failed");
        });
    }

    public void setHeaderSkipped(String reason) {
        Platform.runLater(() -> {
            descLabel.setText(reason);
            statusBadge.setText("SKIPPED");
            statusBadge.getStyleClass().setAll("method-badge", "badge-skipped");
        });
    }

    // ----------------------------------------------------------------
    //  Expand / collapse
    // ----------------------------------------------------------------

    public void expand() {
        if (!expanded) {
            expanded = true;
            Platform.runLater(() -> {
                chevronLabel.setRotate(90);
                contentBox.setVisible(true);
                contentBox.setManaged(true);
            });
        }
    }

    public void collapse() {
        expanded = false;
        Platform.runLater(() -> {
            chevronLabel.setRotate(0);
            contentBox.setVisible(false);
            contentBox.setManaged(false);
        });
    }

    public void toggleExpand() {
        if (expanded) collapse(); else expand();
    }

    // ----------------------------------------------------------------
    //  Step line additions
    // ----------------------------------------------------------------

    /** Adds a spinning running-step line and expands the block. */
    public TerminalLine addRunningStep(String message) {
        return addLine(LineType.RUNNING, message, true);
    }

    private TerminalLine addLine(LineType type, String message, boolean spinner) {
        TerminalLine line = new TerminalLine(type, message, spinner);
        managedLines.add(line);
        Platform.runLater(() -> contentBox.getChildren().add(line));
        return line;
    }

    // ----------------------------------------------------------------
    //  Reset
    // ----------------------------------------------------------------

    /** Resets to PENDING / collapsed state, stopping all spinners. */
    public void reset() {
        managedLines.forEach(TerminalLine::stopSpinner);
        managedLines.clear();
        expanded = false;
        Platform.runLater(() -> {
            chevronLabel.setRotate(0);
            descLabel.setText("");
            statusBadge.setText("PENDING");
            statusBadge.getStyleClass().setAll("method-badge", "badge-pending");
            contentBox.getChildren().clear();
            contentBox.setVisible(false);
            contentBox.setManaged(false);
        });
    }
}

