package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the single-window shell (app-shell.fxml).
 *
 * Responsibilities:
 *  - Displays the login overlay on startup.
 *  - After login, reveals the main layout (sidebar + content area).
 *  - Handles sidebar navigation (Search / Profile / Admin).
 *  - Owns the Night Vision / Light Vision theme toggle.
 *  - Enforces mandatory password change on first login.
 */
public class AppShellController {

    private static final Logger log = LoggerFactory.getLogger(AppShellController.class);

    public static final int INACTIVITY_MINUTES = 2;

    private static AppShellController instance;

    private Timeline                inactivityTimer;
    private EventHandler<Event>     activityHandler;

    @FXML private StackPane  shellRoot;
    @FXML private StackPane  loginOverlay;
    @FXML private BorderPane mainLayout;
    @FXML private Button     navSearch;
    @FXML private Button     navProfile;
    @FXML private Button     navUserManual;
    @FXML private Button     navAdmin;
    @FXML private Button     darkToggleBtn;
    @FXML private Button     logoutBtn;
    @FXML private Label      shellUserLabel;
    @FXML private StackPane  contentArea;

    private boolean darkMode = false;
    private Button  activeNavBtn;

    private Parent          queryView;
    private MainController  mainController;
    private LoginController loginController;

    // ----------------------------------------------------------------
    //  Lifecycle
    // ----------------------------------------------------------------

    @FXML
    public void initialize() {
        instance = this;
        setupNavIcons();
        loadLoginView();
    }

    public static AppShellController getInstance() { return instance; }
    public static boolean isDarkMode()             { return instance != null && instance.darkMode; }

    // ----------------------------------------------------------------
    //  Icon setup
    // ----------------------------------------------------------------

    private void setupNavIcons() {
        applyNavIcon(navSearch,     FontAwesomeIcon.SEARCH,      "Search");
        applyNavIcon(navProfile,    FontAwesomeIcon.USER,        "Profile");
        applyNavIcon(navUserManual, FontAwesomeIcon.BOOK,        "User Manual");
        applyNavIcon(navAdmin,      FontAwesomeIcon.USER_SECRET, "Admin");
        applyNavIcon(darkToggleBtn, FontAwesomeIcon.MOON_ALT,   "Night Vision");
        applyNavIcon(logoutBtn,     FontAwesomeIcon.SIGN_OUT,   "Logout");
        logoutBtn.getStyleClass().setAll("nav-btn", "nav-btn-logout");
    }

    private void applyNavIcon(Button btn, FontAwesomeIcon icon, String label) {
        FontAwesomeIconView view = new FontAwesomeIconView(icon, "14");
        btn.setGraphic(view);
        btn.setGraphicTextGap(8);
        btn.setText(label);
    }

    // ----------------------------------------------------------------
    //  Login bootstrap
    // ----------------------------------------------------------------

    private void loadLoginView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            Parent view = loader.load();
            loginController = loader.getController();
            loginController.setShellController(this);
            loginOverlay.getChildren().add(view);
        } catch (Exception e) {
            log.error("Failed to load login view", e);
        }
    }

    /**
     * Called by LoginController after a successful login.
     * If the user must change their password, shows a mandatory change dialog first.
     */
    public void onLoginSuccess(User user) {
        shellUserLabel.setText("Hello, " + user.getFullName());
        if (user.isAdmin()) {
            navAdmin.setVisible(true);
            navAdmin.setManaged(true);
        }

        if (user.isMustChangePassword()) {
            showMandatoryPasswordChange(user);
            return;
        }

        finalizeLogin(user);
    }

    // ----------------------------------------------------------------
    //  Mandatory password change on first login
    // ----------------------------------------------------------------

    private void showMandatoryPasswordChange(User user) {
        ButtonType saveType   = new ButtonType("Set New Password", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel / Logout", ButtonBar.ButtonData.CANCEL_CLOSE);

        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("Password Change Required");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

        boolean dark = darkMode;
        dialog.getDialogPane().getStylesheets().add(getClass().getResource(
                dark ? "/css/styles-dark.css" : "/css/styles.css").toExternalForm());
        dialog.getDialogPane().setStyle("-fx-background-color: " + (dark ? "#161B22" : "#FFFFFF") + ";");
        dialog.getDialogPane().setPrefWidth(420);

        Label infoLabel = new Label(
                "You are logging in with a temporary password.\n"
                + "You must set a new password before continuing.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280; -fx-padding: 0 0 8 0;");

        PasswordField newPwd     = makePasswordField("Minimum 8 characters");
        PasswordField confirmPwd = makePasswordField("Repeat new password");

        VBox critPane = makePasswordCriteriaPane();
        VBox newGroup = makeFieldGroup("New Password", makePasswordToggleRow(newPwd));
        newGroup.getChildren().add(critPane);
        newPwd.textProperty().addListener((obs, old, text) -> updateCriteria(critPane, text));

        VBox content = new VBox(12, infoLabel, newGroup, makeFieldGroup("Confirm Password", makePasswordToggleRow(confirmPwd)));
        content.setPadding(new Insets(20, 24, 4, 24));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMaxHeight(480);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);

        Button saveBtn   = (Button) dialog.getDialogPane().lookupButton(saveType);
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        if (saveBtn   != null) saveBtn.getStyleClass().add("search-btn");
        if (cancelBtn != null) cancelBtn.getStyleClass().add("header-btn");

        if (saveBtn != null) {
            saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String np = newPwd.getText();
                String cp = confirmPwd.getText();
                if (!isPasswordStrong(np)) {
                    showDialogError("Weak Password",
                            "Password must be at least 8 characters and include uppercase, "
                            + "lowercase, a number, and a special character.");
                    event.consume();
                } else if (!np.equals(cp)) {
                    showDialogError("Passwords Don't Match", "New password and confirmation do not match.");
                    event.consume();
                }
            });
        }

        dialog.setResultConverter(btn -> {
            if (btn != saveType) return null;
            return new String[]{ newPwd.getText(), confirmPwd.getText() };
        });

        // User was already authenticated with the temp password by AuthService,
        // so no current-password verification is needed here — update the hash directly.
        dialog.showAndWait().ifPresentOrElse(
            result -> {
                try {
                    String hash = com.walgreens.rawxmldatapuller.util.PasswordUtil.hash(result[0]);
                    updatePasswordDirectly(user.getId(), hash);
                    finalizeLogin(user);
                } catch (Exception e) {
                    log.error("Failed to update password on first login", e);
                    showDialogError("Error", "Failed to update your password. Please contact your administrator.");
                    performLogout();
                }
            },
            () -> performLogout()
        );
    }

    private void updatePasswordDirectly(String userId, String hash) throws java.sql.SQLException {
        com.walgreens.rawxmldatapuller.util.ConfigLoader cfg =
                com.walgreens.rawxmldatapuller.util.ConfigLoader.getInstance();
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                cfg.getAuthDbHost(), cfg.getAuthDbPort(), cfg.getAuthDbName());
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                url, cfg.getAuthDbUsername(), cfg.getAuthDbPassword());
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE raw_xml_data_puller_users SET password_hash = ?, must_change_password = 0 WHERE id = ?")) {
            ps.setString(1, hash);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    private void finalizeLogin(User user) {
        loadQueryView();
        loginOverlay.setVisible(false);
        loginOverlay.setManaged(false);
        mainLayout.setVisible(true);
        mainLayout.setManaged(true);
        activateNav(navSearch);
        contentArea.getChildren().setAll(queryView);
        startInactivityTimer();
    }

    // ----------------------------------------------------------------
    //  Sidebar navigation
    // ----------------------------------------------------------------

    @FXML private void showSearch() {
        if (activeNavBtn == navSearch) return;
        activateNav(navSearch);
        contentArea.getChildren().setAll(queryView);
    }

    @FXML private void showProfile() {
        if (activeNavBtn == navProfile) return;
        activateNav(navProfile);
        contentArea.getChildren().setAll(loadFresh("/fxml/user-profile.fxml"));
    }

    @FXML private void showAdmin() {
        if (activeNavBtn == navAdmin) return;
        activateNav(navAdmin);
        contentArea.getChildren().setAll(loadFresh("/fxml/admin-panel.fxml"));
    }

    @FXML private void showUserManual() {
        if (activeNavBtn == navUserManual) return;
        activateNav(navUserManual);
        contentArea.getChildren().setAll(loadFresh("/fxml/user-manual.fxml"));
    }

    // ----------------------------------------------------------------
    //  Theme — Night Vision / Light Vision
    // ----------------------------------------------------------------

    @FXML private void toggleTheme() {
        darkMode = !darkMode;
        applyNavIcon(darkToggleBtn,
                darkMode ? FontAwesomeIcon.SUN_ALT  : FontAwesomeIcon.MOON_ALT,
                darkMode ? "Light Vision"            : "Night Vision");
        javafx.scene.Scene scene = shellRoot.getScene();
        scene.getStylesheets().clear();
        scene.getStylesheets().add(getClass().getResource(
                darkMode ? "/css/styles-dark.css" : "/css/styles.css").toExternalForm());
        if (mainController != null) mainController.reapplyTheme(darkMode);
    }

    // ----------------------------------------------------------------
    //  Logout
    // ----------------------------------------------------------------

    @FXML private void handleLogout() { performLogout(); }

    public void performLogout() {
        stopInactivityTimer();

        SessionContext.clear();
        com.walgreens.rawxmldatapuller.util.ConfigLoader.getInstance().setAppConfigService(null);

        if (activeNavBtn != null) {
            activeNavBtn.getStyleClass().remove("nav-btn-active");
            activeNavBtn = null;
        }

        navAdmin.setVisible(false);
        navAdmin.setManaged(false);

        queryView      = null;
        mainController = null;
        contentArea.getChildren().clear();

        if (loginController != null) loginController.reset();

        if (darkMode) {
            darkMode = false;
            applyNavIcon(darkToggleBtn, FontAwesomeIcon.MOON_ALT, "Night Vision");
            javafx.scene.Scene scene = shellRoot.getScene();
            scene.getStylesheets().clear();
            scene.getStylesheets().add(
                    getClass().getResource("/css/styles.css").toExternalForm());
        }

        mainLayout.setVisible(false);
        mainLayout.setManaged(false);
        loginOverlay.setVisible(true);
        loginOverlay.setManaged(true);
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private void loadQueryView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            queryView      = loader.load();
            mainController = loader.getController();
        } catch (Exception e) {
            log.error("Failed to load query view", e);
        }
    }

    private Parent loadFresh(String fxmlPath) {
        try {
            return new FXMLLoader(getClass().getResource(fxmlPath)).load();
        } catch (Exception e) {
            log.error("Failed to load {}", fxmlPath, e);
            return new javafx.scene.layout.VBox();
        }
    }

    private void activateNav(Button btn) {
        if (activeNavBtn != null) activeNavBtn.getStyleClass().remove("nav-btn-active");
        activeNavBtn = btn;
        if (!btn.getStyleClass().contains("nav-btn-active"))
            btn.getStyleClass().add("nav-btn-active");
    }

    // ----------------------------------------------------------------
    //  Inactivity auto-logout (2 minutes)
    // ----------------------------------------------------------------

    private void startInactivityTimer() {
        inactivityTimer = new Timeline(new KeyFrame(Duration.minutes(INACTIVITY_MINUTES), e -> {
            log.info("Auto-logout: {} min inactivity", INACTIVITY_MINUTES);
            performLogout();
            if (loginController != null) loginController.showSessionExpired();
        }));
        inactivityTimer.setCycleCount(1);
        inactivityTimer.play();

        // Null guard: timer may have been cleared by performLogout before event fires
        activityHandler = event -> { if (inactivityTimer != null) inactivityTimer.playFromStart(); };

        // Defer scene attachment to the next FX pulse — guarantees the scene is non-null
        // (calling getScene() synchronously during login transition can return null)
        javafx.application.Platform.runLater(() -> {
            javafx.scene.Scene scene = shellRoot.getScene();
            if (scene != null) {
                scene.addEventFilter(MouseEvent.MOUSE_MOVED,   activityHandler);
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, activityHandler);
                scene.addEventFilter(MouseEvent.MOUSE_CLICKED, activityHandler);
                scene.addEventFilter(KeyEvent.KEY_PRESSED,     activityHandler);
            }
        });
    }

    private void stopInactivityTimer() {
        if (inactivityTimer != null) { inactivityTimer.stop(); inactivityTimer = null; }
        if (activityHandler != null) {
            javafx.scene.Scene scene = shellRoot.getScene();
            if (scene != null) {
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED,   activityHandler);
                scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, activityHandler);
                scene.removeEventFilter(KeyEvent.KEY_PRESSED,     activityHandler);
            }
            activityHandler = null;
        }
    }

    // ----------------------------------------------------------------
    //  Password criteria helpers (for mandatory change dialog)
    // ----------------------------------------------------------------

    private boolean isPasswordStrong(String password) {
        return password.length() >= 8
            && password.chars().anyMatch(Character::isUpperCase)
            && password.chars().anyMatch(Character::isLowerCase)
            && password.chars().anyMatch(Character::isDigit)
            && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }

    private VBox makePasswordCriteriaPane() {
        Label critLength  = new Label("✗  Minimum 8 characters");
        Label critUpper   = new Label("✗  At least one uppercase letter");
        Label critLower   = new Label("✗  At least one lowercase letter");
        Label critNumber  = new Label("✗  At least one number");
        Label critSpecial = new Label("✗  At least one special character");
        Label[] crits = {critLength, critUpper, critLower, critNumber, critSpecial};
        for (Label l : crits) l.setStyle("-fx-font-size: 11px; -fx-text-fill: #e74c3c;");
        VBox pane = new VBox(3, crits);
        pane.setStyle("-fx-padding: 6 4 2 4; -fx-background-color: #f8f9fa; "
                    + "-fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        pane.setVisible(false); pane.setManaged(false);
        pane.setUserData(crits);
        return pane;
    }

    private void updateCriteria(VBox pane, String password) {
        boolean show = !password.isEmpty();
        pane.setVisible(show); pane.setManaged(show);
        if (!show) return;
        Label[] crits = (Label[]) pane.getUserData();
        boolean[] met = {
            password.length() >= 8,
            password.chars().anyMatch(Character::isUpperCase),
            password.chars().anyMatch(Character::isLowerCase),
            password.chars().anyMatch(Character::isDigit),
            password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))
        };
        String[] texts = {
            "Minimum 8 characters", "At least one uppercase letter",
            "At least one lowercase letter", "At least one number",
            "At least one special character"
        };
        for (int i = 0; i < 5; i++) {
            crits[i].setText((met[i] ? "✓  " : "✗  ") + texts[i]);
            crits[i].setStyle("-fx-font-size: 11px; -fx-text-fill: " + (met[i] ? "#27ae60;" : "#e74c3c;"));
        }
    }

    private VBox makeFieldGroup(String labelText, javafx.scene.Node field) {
        VBox group = new VBox(5);
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("field-label");
        group.getChildren().addAll(lbl, field);
        return group;
    }

    private PasswordField makePasswordField(String prompt) {
        PasswordField pf = new PasswordField();
        pf.setPromptText(prompt);
        pf.getStyleClass().add("input-field");
        return pf;
    }

    /** Wraps a PasswordField in an HBox with a show/hide eye-icon toggle button. */
    private HBox makePasswordToggleRow(PasswordField pwd) {
        TextField txt    = new TextField();
        Button    toggle = new Button();
        txt.getStyleClass().add("input-field");
        txt.setPromptText(pwd.getPromptText());
        txt.textProperty().bindBidirectional(pwd.textProperty());
        txt.setVisible(false); txt.setManaged(false);
        toggle.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; "
                      + "-fx-cursor: hand; -fx-padding: 6 10 6 10;");
        toggle.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.EYE, "15"));
        toggle.setFocusTraversable(false);
        toggle.setOnAction(e -> {
            boolean show = !txt.isVisible();
            pwd.setVisible(!show); pwd.setManaged(!show);
            txt.setVisible(show);  txt.setManaged(show);
            toggle.setGraphic(new FontAwesomeIconView(
                    show ? FontAwesomeIcon.EYE_SLASH : FontAwesomeIcon.EYE, "15"));
            if (show) txt.end();
        });
        HBox row = new HBox(0, pwd, txt, toggle);
        HBox.setHgrow(pwd, Priority.ALWAYS);
        HBox.setHgrow(txt, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void showDialogError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }
}
