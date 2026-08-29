package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.*;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import com.walgreens.rawxmldatapuller.ui.TerminalPanel;
import com.walgreens.rawxmldatapuller.ui.XmlHighlightPane;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.XmlFormatter;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Controller for the query / search view (main.fxml).
 *
 * Embedded inside AppShellController's content area.
 * The execution log (terminal) is hidden — users see only the XML output,
 * matching a Postman-style clean response experience.
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    // ---- Input / mode ----
    @FXML private RadioButton erxModeRadio;
    @FXML private RadioButton rxNbrModeRadio;
    @FXML private HBox        erxInputPane;
    @FXML private HBox        rxNbrInputPane;
    @FXML private TextField   erxMsgIdField;
    @FXML private TextField   rxNbrField;
    @FXML private TextField   storeNbrField;
    @FXML private Button      searchBtn;
    @FXML private Button      refreshBtn;

    // ---- DB status indicators ----
    @FXML private Label  dbStatusHeader;
    @FXML private Region erxDbDot;
    @FXML private Label  erxDbLabel;
    @FXML private Region icplusDbDot;
    @FXML private Label  icplusDbLabel;
    @FXML private Region visionDbDot;
    @FXML private Label  visionDbLabel;

    // ---- XML output ----
    @FXML private TabPane xmlTabPane;
    @FXML private Button  clearOutputBtn;
    @FXML private Button  copyXmlBtn;
    @FXML private Button  exportTxtBtn;
    @FXML private Button  exportXmlBtn;
    @FXML private VBox    outputCard;

    // ---- Status / progress ----
    @FXML private Label             statusLabel;
    @FXML private ProgressIndicator progressIndicator;

    // ---- Loading overlay ----
    @FXML private VBox loadingOverlay;

    // ---- Failure pane ----
    @FXML private VBox      failurePane;
    @FXML private Label     failureMessageLabel;
    @FXML private Hyperlink sopLink;
    @FXML private Hyperlink incidentLink;

    // ---- Services ----
    private final DatabaseService    dbService       = new DatabaseService();
    private final DbPingService      dbPingService   = new DbPingService(dbService);
    private final Method1Service     method1         = new Method1Service(dbService);
    private final Method2Service     method2         = new Method2Service(dbService);
    private final Method3Service     method3         = new Method3Service(dbService);
    private final Method4Service     method4         = new Method4Service(dbService);
    private final SshCredentialCache credentialCache         = new SshCredentialCache(10);
    private final SshCredentialCache businessCredentialCache = new SshCredentialCache(5);

    // ---- State ----
    private TerminalPanel     terminal;
    private SearchFlowHandler flowHandler;

    // ================================================================
    //  Initialization
    // ================================================================

    @FXML
    public void initialize() {
        erxModeRadio.setSelected(true);
        setupModeToggle();
        applyIcons();

        // Terminal is created for internal logging but NOT added to the scene.
        terminal    = new TerminalPanel();
        flowHandler = new SearchFlowHandler(
                terminal, method1, method2, method3, method4, this::setStatus,
                credentialCache, businessCredentialCache);

        setOutputVisible(false);
        copyXmlBtn.setDisable(true);
        exportTxtBtn.setDisable(true);
        exportXmlBtn.setDisable(true);
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);
        hideFailurePane();
        setLoading(false);
        setStatus("Ready.");

        // Clear SSH credential cache when the window is closed via native OS controls
        Platform.runLater(() -> getStage().setOnCloseRequest(e -> {
            credentialCache.clear();
            businessCredentialCache.clear();
        }));

        // ADMIN and BUSINESS don't need to see DB status or refresh controls
        User currentUser = SessionContext.getCurrentUser();
        if (currentUser != null && (currentUser.isAdmin() || currentUser.isBusiness())) {
            hide(dbStatusHeader);
            hide(erxDbDot); hide(erxDbLabel);
            hide(icplusDbDot); hide(icplusDbLabel);
            hide(visionDbDot); hide(visionDbLabel);
            hide(refreshBtn);
            hide(statusLabel);
        } else {
            // USER role — kick off DB ping on load
            checkAllDbConnections();
        }

    }

    private void hide(javafx.scene.Node node) { node.setVisible(false); node.setManaged(false); }

    private void applyIcons() {
        setIcon(refreshBtn,   FontAwesomeIcon.REFRESH,       "Refresh",     "refresh-db-btn");
        setIcon(searchBtn,    FontAwesomeIcon.SEARCH,        "Search",      "search-btn");
        setIcon(copyXmlBtn,   FontAwesomeIcon.CLIPBOARD,     "Copy",        "copy-btn");
        setIcon(exportTxtBtn, FontAwesomeIcon.FILE_TEXT_ALT, "Export .txt", "export-btn");
        setIcon(exportXmlBtn, FontAwesomeIcon.FILE_CODE_ALT, "Export .xml", "export-btn-primary");
    }

    private void setIcon(Button btn, FontAwesomeIcon icon, String text, String styleClass) {
        FontAwesomeIconView view = new FontAwesomeIconView(icon, "12");
        btn.setGraphic(view);
        btn.setGraphicTextGap(6);
        btn.getStyleClass().setAll(styleClass);
        btn.setText(text);
    }

    private void setupModeToggle() {
        ToggleGroup group = new ToggleGroup();
        erxModeRadio.setToggleGroup(group);
        rxNbrModeRadio.setToggleGroup(group);
        group.selectedToggleProperty().addListener((obs, old, nw) -> {
            boolean erxMode = (nw == erxModeRadio);
            erxInputPane.setVisible(erxMode);   erxInputPane.setManaged(erxMode);
            rxNbrInputPane.setVisible(!erxMode); rxNbrInputPane.setManaged(!erxMode);
        });
    }

    private Stage getStage() { return (Stage) searchBtn.getScene().getWindow(); }

    // ================================================================
    //  DB status
    // ================================================================

    @FXML public void refreshDbStatus() {
        User u = SessionContext.getCurrentUser();
        if (u == null || (!u.isAdmin() && !u.isBusiness())) checkAllDbConnections();
    }

    void checkAllDbConnections() {
        ConfigLoader cfg = ConfigLoader.getInstance();
        setDbStatus(erxDbDot,    erxDbLabel,    "eRx DB",    "checking");
        setDbStatus(icplusDbDot, icplusDbLabel, "IC+ DB",    "checking");
        setDbStatus(visionDbDot, visionDbLabel, "Vision DB", "checking");
        dbPingService.pingAsync(cfg.getErxDbConfig(),    ok -> setDbStatus(erxDbDot,    erxDbLabel,    "eRx DB",    ok ? "connected" : "failed"));
        dbPingService.pingAsync(cfg.getIcPlusDbConfig(), ok -> setDbStatus(icplusDbDot, icplusDbLabel, "IC+ DB",    ok ? "connected" : "failed"));
        dbPingService.pingAsync(cfg.getVisionDbConfig(), ok -> setDbStatus(visionDbDot, visionDbLabel, "Vision DB", ok ? "connected" : "failed"));
    }

    private void setDbStatus(Region dot, Label label, String name, String state) {
        dot.getStyleClass().removeAll("db-dot-checking", "db-dot-connected", "db-dot-failed");
        label.getStyleClass().removeAll("db-status-connected", "db-status-failed", "db-status-checking");
        switch (state) {
            case "connected" -> { dot.getStyleClass().add("db-dot-connected");  label.setText(name + ": Connected");   label.getStyleClass().add("db-status-connected"); }
            case "failed"    -> { dot.getStyleClass().add("db-dot-failed");     label.setText(name + ": Unavailable"); label.getStyleClass().add("db-status-failed"); }
            default          -> { dot.getStyleClass().add("db-dot-checking");   label.setText(name + ": Checking...");  label.getStyleClass().add("db-status-checking"); }
        }
    }

    // ================================================================
    //  Search
    // ================================================================

    @FXML
    private void handleSearch() {
        boolean erxMode = erxModeRadio.isSelected();
        String rxNbr    = rxNbrField.getText().trim();
        String storeNbr = storeNbrField.getText().trim();

        List<String> msgIds = new ArrayList<>();
        if (erxMode) {
            for (String raw : erxMsgIdField.getText().split("[,\\n\\r]+")) {
                String id = raw.trim();
                if (!id.isEmpty()) msgIds.add(id);
            }
            if (msgIds.isEmpty()) { alert(Alert.AlertType.WARNING, "Input Required", "Enter at least one eRx Message ID."); return; }
        } else {
            if (rxNbr.isEmpty() || storeNbr.isEmpty()) { alert(Alert.AlertType.WARNING, "Input Required", "Enter both Rx Number and Store Number."); return; }
        }

        resetUI();
        setLoading(true);
        long startMs = System.currentTimeMillis();
        final int totalSearches = erxMode ? msgIds.size() : 1;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (erxMode) {
                    for (int i = 0; i < msgIds.size(); i++) {
                        if (i > 0) { resetBlocksSync(); }
                        String msgId = msgIds.get(i);
                        processResult(msgId, flowHandler.runErxMsgIdFlow(msgId));
                    }
                } else {
                    processResult("Rx " + rxNbr + " / Store " + storeNbr, flowHandler.runRxNbrFlow(rxNbr, storeNbr));
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            businessCredentialCache.clear(); // close SSH immediately after data fetched
            setLoading(false);
            finishSearch(System.currentTimeMillis() - startMs, totalSearches);
        });
        task.setOnFailed(e -> {
            log.error("Task failure", task.getException());
            businessCredentialCache.clear(); // close SSH on failure too
            setLoading(false);
            finishSearch(System.currentTimeMillis() - startMs, totalSearches);
        });

        Thread t = new Thread(task, "rx-search");
        t.setDaemon(true); t.start();
    }

    private void resetBlocksSync() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            terminal.getBlock1().reset(); terminal.getBlock2().reset();
            terminal.getBlock3().reset(); terminal.getBlock4().reset();
            latch.countDown();
        });
        latch.await();
    }

    private void processResult(String label, SearchResult result) {
        if (result != null && result.isFound() && !result.isCorrupted()) {
            String rawXml = result.getRawXmlDoc();
            boolean dark  = AppShellController.isDarkMode();
            XmlHighlightPane pane = new XmlHighlightPane();
            pane.setXml(XmlFormatter.format(rawXml), dark);
            String tabTitle = label.length() > 26 ? label.substring(0, 24) + "…" : label;
            Platform.runLater(() -> {
                Tab tab = new Tab(tabTitle);
                tab.setTooltip(new Tooltip(label));
                tab.setContent(pane);
                tab.setUserData(rawXml);
                xmlTabPane.getTabs().add(tab);
                xmlTabPane.getSelectionModel().selectLast();
            });
        }
    }

    private void finishSearch(long elapsed, int total) {
        int found = xmlTabPane.getTabs().size();
        if (found > 0) {
            setOutputVisible(true);
            copyXmlBtn.setDisable(false); exportTxtBtn.setDisable(false); exportXmlBtn.setDisable(false);
            hideFailurePane();
            setStatus("Found " + found + " of " + total + " result" + (found > 1 ? "s" : "")
                    + " in " + String.format("%.2f", elapsed / 1000.0) + "s");
        } else {
            setOutputVisible(false);
            String msg = total > 1
                    ? "Data not found for any of the " + total + " MSG IDs provided."
                    : "Data could not be retrieved through any automated method.";
            failureMessageLabel.setText(msg);
            failurePane.setVisible(true); failurePane.setManaged(true);
            // SOP link hidden for ADMIN and BUSINESS — only the incident link is relevant
            User u = SessionContext.getCurrentUser();
            boolean showSop = (u == null || (!u.isBusiness() && !u.isAdmin()));
            sopLink.setVisible(showSop); sopLink.setManaged(showSop);
            setStatus("Not found — try manual process or raise an IDI incident.");
        }
    }

    /** Clears execution state and XML output without touching the input fields. */
    @FXML
    private void handleClearOutput() {
        resetUI();
        setStatus("Ready.");
    }

    @FXML
    private void handleClearAll() {
        erxMsgIdField.clear();
        rxNbrField.clear();
        storeNbrField.clear();
        resetUI();
        setStatus("Ready.");
    }

    // ================================================================
    //  Copy / Export
    // ================================================================

    @FXML
    private void copyXml() {
        Tab tab = xmlTabPane.getSelectionModel().getSelectedItem();
        if (tab == null || !(tab.getUserData() instanceof String rawXml)) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(rawXml);
        Clipboard.getSystemClipboard().setContent(cc);
        setStatus("XML copied to clipboard.");
        String prev = copyXmlBtn.getText();
        copyXmlBtn.setText("✓  Copied!");
        javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        pt.setOnFinished(e -> copyXmlBtn.setText(prev));
        pt.play();
    }

    @FXML private void exportTxt() { exportActiveTab("txt"); }
    @FXML private void exportXml() { exportActiveTab("xml"); }

    private void exportActiveTab(String ext) {
        Tab tab = xmlTabPane.getSelectionModel().getSelectedItem();
        if (tab == null || !(tab.getUserData() instanceof String rawXml)) return;
        String content = XmlFormatter.format(rawXml);

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Raw XML");
        fc.setInitialFileName(toFileName(tab.getText()) + "." + ext);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext.toUpperCase() + " Files", "*." + ext));
        File file = fc.showSaveDialog(getStage());
        if (file == null) return;

        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
            fw.write(content);
            setStatus("Exported: " + file.getAbsolutePath());
            alert(Alert.AlertType.INFORMATION, "Export Successful", "File saved to:\n" + file.getAbsolutePath());
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "Export Failed", e.getMessage());
        }
    }

    // ================================================================
    //  Theme — called by AppShellController when theme toggles
    // ================================================================

    public void reapplyTheme(boolean dark) {
        xmlTabPane.getTabs().forEach(tab -> {
            if (tab.getContent() instanceof XmlHighlightPane pane
                    && tab.getUserData() instanceof String rawXml)
                pane.setXml(XmlFormatter.format(rawXml), dark);
        });
    }

    // ================================================================
    //  Links
    // ================================================================

    @FXML private void openSopLink()      { openUrl(ConfigLoader.getInstance().getSopLink()); }
    @FXML private void openIncidentLink() { openUrl(ConfigLoader.getInstance().getIncidentLink()); }

    private void openUrl(String url) {
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            alert(Alert.AlertType.INFORMATION, "Link Not Configured",
                    "Configure this URL in the Admin → Configuration panel."); return;
        }
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { alert(Alert.AlertType.ERROR, "Cannot Open Link", url + "\n\n" + e.getMessage()); }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private void resetUI() {
        xmlTabPane.getTabs().clear();
        setOutputVisible(false);
        copyXmlBtn.setDisable(true); exportTxtBtn.setDisable(true); exportXmlBtn.setDisable(true);
        hideFailurePane();
    }

    private String toFileName(String tabTitle) {
        return tabTitle
                .replaceAll("[\\s/\\\\:*?\"<>|…]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private void setOutputVisible(boolean visible) { outputCard.setVisible(visible); outputCard.setManaged(visible); }
    private void hideFailurePane()                  { failurePane.setVisible(false); failurePane.setManaged(false); }

    private void setLoading(boolean on) {
        searchBtn.setDisable(on);
        loadingOverlay.setVisible(on);
        loadingOverlay.setManaged(on);
    }

    private void setStatus(String msg) { Platform.runLater(() -> statusLabel.setText(msg)); }

    private void alert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert a = new Alert(type, content, ButtonType.OK);
            a.setTitle(title); a.setHeaderText(null); a.showAndWait();
        });
    }
}
