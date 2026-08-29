package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.DatabaseService;
import com.walgreens.rawxmldatapuller.service.DbPingService;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Paths;

/**
 * JavaFX controller for the Settings dialog.
 *
 * <p>All config values are read-only — edit {@code application.properties} and
 * click "Reload Config" to apply changes.  DB pings use {@link DbPingService}
 * (shared, no raw threads).  Status colours are CSS classes, not inline styles.</p>
 */
public class SettingsController {

    private static final Logger log             = LoggerFactory.getLogger(SettingsController.class);
    private static final String LOG_FILE_NAME   = "rx-data-puller.log";

    @FXML private Label  erxDbInfo;
    @FXML private Label  icplusDbInfo;
    @FXML private Label  visionDbInfo;
    @FXML private Region erxStatusDot;
    @FXML private Region icplusStatusDot;
    @FXML private Region visionStatusDot;
    @FXML private Label  erxStatusLabel;
    @FXML private Label  icplusStatusLabel;
    @FXML private Label  visionStatusLabel;

    @FXML private Label     sshServerLabel;
    @FXML private TextField sshServer;
    @FXML private TextField sshPort;
    @FXML private TextField sopLinkField;
    @FXML private TextField incidentLinkField;
    @FXML private Label     logFilePathLabel;

    private final DbPingService dbPingService = new DbPingService(new DatabaseService());

    // ================================================================
    //  Initialization
    // ================================================================

    @FXML
    public void initialize() {
        loadConfig();
        applyRoleVisibility();
        testAllConnections();
        File logFile = Paths.get(System.getProperty("user.dir"), LOG_FILE_NAME).toFile();
        logFilePathLabel.setText("Log file: " + logFile.getAbsolutePath());
    }

    private void loadConfig() {
        ConfigLoader cfg = ConfigLoader.getInstance();
        User user = SessionContext.getCurrentUser();
        boolean isAdmin = (user != null && user.isAdmin());

        if (isAdmin) {
            erxDbInfo.setText(cfg.getErxDbConfig().toString());
            icplusDbInfo.setText(cfg.getIcPlusDbConfig().toString());
            visionDbInfo.setText(cfg.getVisionDbConfig().toString());
        } else {
            erxDbInfo.setText(   cfg.getErxDbConfig().getName());
            icplusDbInfo.setText(cfg.getIcPlusDbConfig().getName());
            visionDbInfo.setText(cfg.getVisionDbConfig().getName());
        }

        sshServer.setText(cfg.getSshServer());
        sshPort.setText(String.valueOf(cfg.getSshPort()));
        sopLinkField.setText(cfg.getSopLink());
        incidentLinkField.setText(cfg.getIncidentLink());
    }

    private void applyRoleVisibility() {
        User user = SessionContext.getCurrentUser();
        if (user != null && !user.isAdmin()) {
            sshServerLabel.setVisible(false); sshServerLabel.setManaged(false);
            sshServer.setVisible(false);      sshServer.setManaged(false);
        }
    }

    // ================================================================
    //  DB connectivity
    // ================================================================

    @FXML
    private void testAllConnections() {
        setStatus("dot-checking", erxStatusDot,    erxStatusLabel,    "eRx DB",    "Checking...");
        setStatus("dot-checking", icplusStatusDot, icplusStatusLabel, "IC+ DB",    "Checking...");
        setStatus("dot-checking", visionStatusDot, visionStatusLabel, "Vision DB", "Checking...");

        ConfigLoader cfg = ConfigLoader.getInstance();
        dbPingService.pingAsync(cfg.getErxDbConfig(),    ok -> setStatus(ok ? "dot-connected" : "dot-failed", erxStatusDot,    erxStatusLabel,    "eRx DB",    ok ? "Connected" : "Unavailable"));
        dbPingService.pingAsync(cfg.getIcPlusDbConfig(), ok -> setStatus(ok ? "dot-connected" : "dot-failed", icplusStatusDot, icplusStatusLabel, "IC+ DB",    ok ? "Connected" : "Unavailable"));
        dbPingService.pingAsync(cfg.getVisionDbConfig(), ok -> setStatus(ok ? "dot-connected" : "dot-failed", visionStatusDot, visionStatusLabel, "Vision DB", ok ? "Connected" : "Unavailable"));
    }

    private void setStatus(String dotClass, Region dot, Label label, String name, String msg) {
        dot.getStyleClass().removeAll("dot-checking", "dot-connected", "dot-failed");
        dot.getStyleClass().add(dotClass);
        label.setText(name + ": " + msg);
        label.getStyleClass().removeAll("db-status-connected", "db-status-failed", "db-status-checking");
        switch (dotClass) {
            case "dot-connected" -> label.getStyleClass().add("db-status-connected");
            case "dot-failed"    -> label.getStyleClass().add("db-status-failed");
            default              -> label.getStyleClass().add("db-status-checking");
        }
    }

    // ================================================================
    //  Config / logs
    // ================================================================

    @FXML
    private void handleReloadConfig() {
        ConfigLoader.getInstance().reload();
        loadConfig();
        testAllConnections();
    }

    @FXML private void handleViewLogs()      { openFile(Paths.get(System.getProperty("user.dir"), LOG_FILE_NAME).toFile()); }
    @FXML private void handleOpenLogFolder() { openFile(Paths.get(System.getProperty("user.dir")).toFile()); }

    private void openFile(File target) {
        try {
            if (!target.exists()) { showAlert("File Not Found", "Log file not found at:\n" + target.getAbsolutePath()); return; }
            Desktop.getDesktop().open(target);
        } catch (Exception e) {
            log.error("Cannot open {}", target, e);
            showAlert("Cannot Open File", "Could not open:\n" + target.getAbsolutePath() + "\n\n" + e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
            a.setTitle(title); a.setHeaderText(null); a.showAndWait();
        });
    }

}

