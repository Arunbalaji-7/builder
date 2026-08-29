package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.DbConfig;
import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.AppConfigService;
import com.walgreens.rawxmldatapuller.service.AuthException;
import com.walgreens.rawxmldatapuller.service.AuthService;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    @FXML private TextField         usernameField;
    @FXML private PasswordField     passwordField;
    @FXML private TextField         passwordFieldTxt;
    @FXML private Button            pwdToggleBtn;
    @FXML private Button            loginBtn;
    @FXML private Label             errorLabel;
    @FXML private ProgressIndicator loginProgress;

    private AppShellController shellController;

    private final AuthService     authService = new AuthService();
    private final ExecutorService executor    = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auth-thread");
        t.setDaemon(true);
        return t;
    });

    public void setShellController(AppShellController shell) { this.shellController = shell; }

    @FXML
    public void initialize() {
        passwordFieldTxt.textProperty().bindBidirectional(passwordField.textProperty());
        setEyeIcon(false);
    }

    /** Clears all fields and resets password visibility to hidden. */
    public void reset() {
        usernameField.clear();
        passwordField.clear();
        passwordField.setVisible(true);     passwordField.setManaged(true);
        passwordFieldTxt.setVisible(false); passwordFieldTxt.setManaged(false);
        setEyeIcon(false);
        hideError();
        setLoading(false);
    }

    /** Shown after an inactivity-triggered auto-logout. */
    public void showSessionExpired() {
        errorLabel.setText("Session expired — please sign in again.");
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML
    private void togglePasswordVisibility() {
        boolean show = !passwordFieldTxt.isVisible();
        passwordField.setVisible(!show);    passwordField.setManaged(!show);
        passwordFieldTxt.setVisible(show);  passwordFieldTxt.setManaged(show);
        setEyeIcon(show);
        if (show) passwordFieldTxt.end();
    }

    private void setEyeIcon(boolean passwordVisible) {
        FontAwesomeIconView icon = new FontAwesomeIconView(
                passwordVisible ? FontAwesomeIcon.EYE_SLASH : FontAwesomeIcon.EYE, "15");
        pwdToggleBtn.setGraphic(icon);
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText();
        String password = passwordField.getText();

        hideError();
        setLoading(true);

        executor.submit(() -> {
            try {
                User user = authService.login(username, password);
                Platform.runLater(() -> onLoginSuccess(user));
            } catch (AuthException e) {
                Platform.runLater(() -> {
                    setLoading(false);
                    showError(e.getMessage());
                });
            }
        });
    }

    private void onLoginSuccess(User user) {
        SessionContext.setCurrentUser(user);

        ConfigLoader cfg = ConfigLoader.getInstance();

        AppConfigService appCfg = new AppConfigService(
                cfg.getAuthDbHost(), cfg.getAuthDbPort(), cfg.getAuthDbName(),
                cfg.getAuthDbUsername(), cfg.getAuthDbPassword(),
                cfg.getEncryptionKey());

        // Build defaults from application.properties BEFORE injecting appCfg,
        // so ConfigLoader still reads from the properties file here.
        Map<String, String> defaults = buildDefaultConfig(cfg);

        cfg.setAppConfigService(appCfg);

        // Seed app_config on first launch — INSERT IGNORE never overwrites admin changes.
        // Runs in background so it doesn't delay the UI transition.
        executor.submit(() -> {
            try {
                appCfg.saveIfAbsent(defaults);
            } catch (Exception e) {
                log.warn("Could not auto-seed app_config defaults", e);
            }
        });

        shellController.onLoginSuccess(user);
    }

    /**
     * Builds a map of all admin-editable settings read directly from
     * {@code application.properties} (AppConfigService not yet injected).
     */
    private Map<String, String> buildDefaultConfig(ConfigLoader cfg) {
        DbConfig erx    = cfg.getErxDbConfig();
        DbConfig icplus = cfg.getIcPlusDbConfig();
        DbConfig vision = cfg.getVisionDbConfig();

        Map<String, String> map = new LinkedHashMap<>();

        // eRx Oracle DB
        map.put("erx.db.hostname", erx.getHostname());
        map.put("erx.db.port",     String.valueOf(erx.getPort()));
        map.put("erx.db.sid",      erx.getSid());
        map.put("erx.db.username", erx.getUsername());
        map.put("erx.db.password", erx.getPassword());

        // IC+ Oracle DB
        map.put("icplus.db.hostname", icplus.getHostname());
        map.put("icplus.db.port",     String.valueOf(icplus.getPort()));
        map.put("icplus.db.sid",      icplus.getSid());
        map.put("icplus.db.username", icplus.getUsername());
        map.put("icplus.db.password", icplus.getPassword());

        // Vision Oracle DB
        map.put("vision.db.hostname", vision.getHostname());
        map.put("vision.db.port",     String.valueOf(vision.getPort()));
        map.put("vision.db.sid",      vision.getSid());
        map.put("vision.db.username", vision.getUsername());
        map.put("vision.db.password", vision.getPassword());

        // SSH
        map.put("ssh.server",            cfg.getSshServer());
        map.put("ssh.port",              String.valueOf(cfg.getSshPort()));
        map.put("ssh.business.username", "");
        map.put("ssh.business.password", "");

        // Mail API endpoint (hidden system config — never shown in any UI)
        map.put("mail.server.api", cfg.getMailServerApi());
        map.put("mail.from", cfg.getMailFrom());

        // Application links
        map.put("link.sop",      cfg.getSopLink());
        map.put("link.incident", cfg.getIncidentLink());

        return map;
    }

    private void setLoading(boolean loading) {
        loginBtn.setDisable(loading);
        usernameField.setDisable(loading);
        passwordField.setDisable(loading);
        passwordFieldTxt.setDisable(loading);
        pwdToggleBtn.setDisable(loading);
        loginProgress.setVisible(loading);
        loginProgress.setManaged(loading);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}

