package com.walgreens.rawxmldatapuller.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A single timestamped log entry displayed inside a {@link MethodBlock}.
 *
 * <p>Each line has a timestamp, an animated or static icon, and a message label.
 * Right-clicking any line shows a context menu with a <em>Copy</em> option that
 * copies the message text to the system clipboard — useful for paths reported by
 * Method 3 steps.</p>
 *
 * <p>All public state-transition methods ({@link #succeed}, {@link #fail},
 * {@link #skip}, {@link #updateMessage}) are thread-safe and may be called from
 * any thread.</p>
 */
public class TerminalLine extends HBox {

    private static final String[] SPINNER   = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Label    iconLabel;
    private final Label    messageLabel;
    private Timeline spinnerTimeline;
    private int      spinnerFrame = 0;

    TerminalLine(LineType type, String message, boolean withSpinner) {
        setSpacing(0);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("terminal-line");

        Label timeLabel = new Label(LocalTime.now().format(TIME_FMT) + "  ");
        timeLabel.getStyleClass().add("terminal-ts");

        iconLabel = new Label();
        iconLabel.setMinWidth(22);
        iconLabel.getStyleClass().add("terminal-icon");

        messageLabel = new Label(message);
        messageLabel.getStyleClass().add("terminal-msg");
        messageLabel.setWrapText(true);
        HBox.setHgrow(messageLabel, Priority.ALWAYS);

        getChildren().addAll(timeLabel, iconLabel, messageLabel);
        applyType(type, withSpinner);
        attachCopyMenu();
    }

    // ----------------------------------------------------------------
    //  Context menu — right-click to copy the message text
    // ----------------------------------------------------------------

    private void attachCopyMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem copyItem = new MenuItem("⎘  Copy");
        copyItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(messageLabel.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        menu.getItems().add(copyItem);
        setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
    }

    // ----------------------------------------------------------------
    //  State transitions (thread-safe)
    // ----------------------------------------------------------------

    /** Finalises this line as succeeded: stops spinner, shows green ✓. */
    public void succeed(String msg) {
        stopSpinner();
        Platform.runLater(() -> transition("✓ ", "t-icon-success", "t-msg-success", msg));
    }

    /** Finalises this line as failed: stops spinner, shows red ✗. */
    public void fail(String msg) {
        stopSpinner();
        Platform.runLater(() -> transition("✗ ", "t-icon-failed", "t-msg-failed", msg));
    }

    /** Finalises this line as skipped: stops spinner, shows grey ⇢. */
    public void skip(String msg) {
        stopSpinner();
        Platform.runLater(() -> transition("⇢ ", "t-icon-skipped", "t-msg-skipped", msg));
    }

    /** Updates only the message text without changing icon or status. */
    public void updateMessage(String msg) {
        Platform.runLater(() -> { if (msg != null) messageLabel.setText(msg); });
    }

    /** Returns {@code true} when the success checkmark is showing. */
    public boolean isSucceeded() {
        return iconLabel.getStyleClass().contains("t-icon-success");
    }

    /** Stops the spinner animation (no-op if not spinning). */
    public void stopSpinner() {
        if (spinnerTimeline != null) { spinnerTimeline.stop(); spinnerTimeline = null; }
    }

    // ----------------------------------------------------------------
    //  Internal helpers
    // ----------------------------------------------------------------

    private void applyType(LineType type, boolean withSpinner) {
        switch (type) {
            case RUNNING -> {
                iconLabel.setText(SPINNER[0]);
                iconLabel.getStyleClass().add("t-icon-running");
                messageLabel.getStyleClass().add("t-msg-running");
                if (withSpinner) startSpinner();
            }
            case SUCCESS       -> applyFinal("✓ ", "t-icon-success", "t-msg-success");
            case FAILED        -> applyFinal("✗ ", "t-icon-failed",  "t-msg-failed");
            case SKIPPED       -> applyFinal("⇢ ", "t-icon-skipped", "t-msg-skipped");
            case INFO          -> applyFinal("ℹ ", "t-icon-info",    "t-msg-info");
            case METHOD_HEADER -> applyFinal("▶ ", "t-icon-method",  "t-msg-method");
        }
    }

    private void applyFinal(String icon, String iconCls, String msgCls) {
        iconLabel.setText(icon);
        iconLabel.getStyleClass().add(iconCls);
        messageLabel.getStyleClass().add(msgCls);
    }

    private void startSpinner() {
        spinnerTimeline = new Timeline(
                new KeyFrame(Duration.millis(80), e -> {
                    spinnerFrame = (spinnerFrame + 1) % SPINNER.length;
                    iconLabel.setText(SPINNER[spinnerFrame]);
                })
        );
        spinnerTimeline.setCycleCount(Timeline.INDEFINITE);
        spinnerTimeline.play();
    }

    private void transition(String icon, String iconCls, String msgCls, String msg) {
        iconLabel.getStyleClass().removeAll(
                "t-icon-running", "t-icon-method", "t-icon-success",
                "t-icon-failed",  "t-icon-skipped", "t-icon-info");
        iconLabel.getStyleClass().add(iconCls);
        iconLabel.setText(icon);

        messageLabel.getStyleClass().removeAll(
                "t-msg-running", "t-msg-method", "t-msg-success",
                "t-msg-failed",  "t-msg-skipped", "t-msg-info");
        messageLabel.getStyleClass().add(msgCls);

        if (msg != null && !msg.isBlank()) messageLabel.setText(msg);
    }
}

