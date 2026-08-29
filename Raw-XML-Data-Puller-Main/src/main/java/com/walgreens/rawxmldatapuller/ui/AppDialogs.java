package com.walgreens.rawxmldatapuller.ui;

import com.walgreens.rawxmldatapuller.service.SshCredentialCache;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe factory for modal input dialogs used during Method 3 execution.
 *
 * <p>Each method blocks the calling background thread via {@link CountDownLatch}
 * until the user dismisses the dialog on the FX thread.  Returns the collected
 * values or {@code null} if the user cancelled.</p>
 */
public final class AppDialogs {

    private AppDialogs() {}

    /**
     * Returns SSH credentials for Method 3.
     *
     * <p>If {@code cache} already holds credentials from a previous call in this
     * session, they are returned immediately and the idle timer is reset — no
     * dialog is shown.  Otherwise a dialog is presented, and on success the
     * credentials are stored in {@code cache} for all future calls.</p>
     *
     * @param cache shared credential cache for this app session
     * @return {@code String[]{username, password}}, or {@code null} if cancelled
     */
    public static String[] askSshCredentials(SshCredentialCache cache)
            throws InterruptedException {
        String[] cached = cache.use();
        if (cached != null) return cached;          // silent reuse — no dialog

        String[] creds = showTwoFieldDialog(
                "SSH Credentials Required",
                "SSH access to " + ConfigLoader.getInstance().getSshServer()
                        + " is required to retrieve the XML data.\n"
                        + "Enter your One ID and password.",
                new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE),
                "One ID:",   "One ID",   false,
                "Password:", "Password", true
        );
        if (creds != null) cache.store(creds[0], creds[1]);
        return creds;
    }

    /**
     * Asks the user for the Rx date (MM/DD/YYYY) to use in Method 4's path construction.
     *
     * <p>The dialog validates the format before closing; an inline error label is shown
     * for invalid entries and the dialog stays open until a valid date is entered or
     * the user cancels.</p>
     *
     * @return the date string in {@code MM/DD/YYYY} format, or {@code null} if cancelled
     */
    public static String askRxDate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        Platform.runLater(() -> {
            Dialog<String> dlg = new Dialog<>();
            dlg.setTitle("Rx Date Required");
            dlg.setHeaderText(
                    "All automated retrieval methods failed.\n"
                            + "Please provide the Rx fill date to attempt a file path search.\n\n"
                            + "Select the date from the calendar, or type MM/DD/YYYY directly.");

            ButtonType continueBtn = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
            dlg.getDialogPane().getButtonTypes().addAll(continueBtn, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10); grid.setVgap(8);
            grid.setPadding(new Insets(20, 60, 10, 10));

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy");

            DatePicker datePicker = new DatePicker();
            datePicker.setPrefWidth(200);

            // Parse/format in MM/DD/YYYY so typed input is handled correctly
            datePicker.setConverter(new StringConverter<LocalDate>() {
                @Override public String toString(LocalDate date) {
                    return date != null ? date.format(fmt) : "";
                }
                @Override public LocalDate fromString(String s) {
                    if (s == null || s.isBlank()) return null;
                    try { return LocalDate.parse(s.trim(), fmt); } catch (Exception e) { return null; }
                }
            });

            TextField editor = datePicker.getEditor();
            editor.setPromptText("MM/DD/YYYY  e.g. 11/18/2023");

            // Auto-insert "/" after the 2nd digit (MM) and after the 4th digit (DD)
            boolean[] updating = {false};
            editor.textProperty().addListener((obs, oldVal, newVal) -> {
                if (updating[0] || newVal == null || newVal.length() <= oldVal.length()) return;
                String digits = newVal.replaceAll("[^\\d]", "");
                if (digits.length() > 8) digits = digits.substring(0, 8);
                String formatted;
                if (digits.length() <= 2) {
                    formatted = digits;
                    if (digits.length() == 2) formatted += "/";
                } else if (digits.length() <= 4) {
                    formatted = digits.substring(0, 2) + "/" + digits.substring(2);
                    if (digits.length() == 4) formatted += "/";
                } else {
                    formatted = digits.substring(0, 2) + "/" + digits.substring(2, 4) + "/" + digits.substring(4);
                }
                if (!formatted.equals(newVal)) {
                    updating[0] = true;
                    editor.setText(formatted);
                    editor.positionCaret(formatted.length());
                    updating[0] = false;
                }
            });

            Label errorLbl = new Label();
            errorLbl.setStyle("-fx-text-fill: #c62828; -fx-font-size: 11;");
            errorLbl.setVisible(false);

            grid.add(new Label("Rx Date:"), 0, 0);
            grid.add(datePicker,             1, 0);
            grid.add(errorLbl,               1, 1);
            dlg.getDialogPane().setContent(grid);
            Platform.runLater(datePicker::requestFocus);

            // Block dialog close when the editor text is not a valid MM/DD/YYYY date
            Node confirmNode = dlg.getDialogPane().lookupButton(continueBtn);
            confirmNode.addEventFilter(ActionEvent.ACTION, e -> {
                String text = editor.getText().trim();
                if (!text.matches("\\d{2}/\\d{2}/\\d{4}")) {
                    errorLbl.setText("Invalid date — select from the calendar or type MM/DD/YYYY");
                    errorLbl.setVisible(true);
                    e.consume();
                }
            });

            dlg.setResultConverter(btn -> {
                if (btn == continueBtn) {
                    String text = editor.getText().trim();
                    return text.matches("\\d{2}/\\d{2}/\\d{4}") ? text : null;
                }
                return null;
            });

            dlg.showAndWait().ifPresent(result::set);
            latch.countDown();
        });

        latch.await();
        return result.get();
    }

    /** Rx/Store dialog → {@code [rxNbr, storeNbr]} or {@code null}. */
    public static String[] askRxAndStoreNbr() throws InterruptedException {
        return showTwoFieldDialog(
                "Additional Details Required",
                "Additional details are required to continue the search.\n"
                        + "Please provide the Rx Number and Store Number.",
                new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE),
                "Rx Number:",    "Rx Number",    false,
                "Store Number:", "Store Number", false
        );
    }

    // ----------------------------------------------------------------

    private static String[] showTwoFieldDialog(
            String title, String header, ButtonType confirmBtn,
            String label1, String prompt1, boolean password1,
            String label2, String prompt2, boolean password2)
            throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String[]> result = new AtomicReference<>();

        Platform.runLater(() -> {
            Dialog<String[]> dlg = new Dialog<>();
            dlg.setTitle(title);
            dlg.setHeaderText(header);
            dlg.getDialogPane().getButtonTypes().addAll(confirmBtn, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10); grid.setVgap(10);
            grid.setPadding(new Insets(20, 100, 10, 10));

            TextField f1 = password1 ? new PasswordField() : new TextField();
            f1.setPromptText(prompt1);
            TextField f2 = password2 ? new PasswordField() : new TextField();
            f2.setPromptText(prompt2);

            grid.add(new Label(label1), 0, 0); grid.add(f1, 1, 0);
            grid.add(new Label(label2), 0, 1); grid.add(f2, 1, 1);
            dlg.getDialogPane().setContent(grid);
            Platform.runLater(f1::requestFocus);

            dlg.setResultConverter(btn ->
                    btn == confirmBtn && !f1.getText().isBlank() && !f2.getText().isBlank()
                            ? new String[]{f1.getText().trim(),
                            password2 ? f2.getText() : f2.getText().trim()}
                            : null);

            dlg.showAndWait().ifPresent(result::set);
            latch.countDown();
        });

        latch.await();
        return result.get();
    }
}

