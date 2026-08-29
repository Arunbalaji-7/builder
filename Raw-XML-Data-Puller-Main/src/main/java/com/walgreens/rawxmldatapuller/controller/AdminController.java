package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.AppConfigService;
import com.walgreens.rawxmldatapuller.service.MailService;
import com.walgreens.rawxmldatapuller.service.UserService;
import com.walgreens.rawxmldatapuller.service.UserService.LoginHistoryEntry;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    // ---- Users tab ----
    @FXML private TableView<User>            usersTable;
    @FXML private TableColumn<User, String>  colUsername;
    @FXML private TableColumn<User, String>  colFullName;
    @FXML private TableColumn<User, String>  colEmail;
    @FXML private TableColumn<User, String>  colRole;
    @FXML private TableColumn<User, Boolean> colActive;
    @FXML private TableColumn<User, String>  colLastLogin;
    @FXML private TextField searchField;
    @FXML private Button    editUserBtn;
    @FXML private Button    resetPwdBtn;
    @FXML private Button    deleteUserBtn;
    @FXML private Label     userListStatus;

    // ---- Login history tab ----
    @FXML private TableView<LoginHistoryEntry>            historyTable;
    @FXML private TableColumn<LoginHistoryEntry, String>  histColUsername;
    @FXML private TableColumn<LoginHistoryEntry, String>  histColFullName;
    @FXML private TableColumn<LoginHistoryEntry, String>  histColLoginTime;
    @FXML private Label historyStatus;

    // ---- Users tab — pagination ----
    @FXML private Label  userPageInfo;
    @FXML private Button userPrevBtn;
    @FXML private Button userNextBtn;

    // ---- Login history tab — search + pagination ----
    @FXML private TextField historySearchField;
    @FXML private Label     histPageInfo;
    @FXML private Button    histPrevBtn;
    @FXML private Button    histNextBtn;

    // ---- SOP tab — Links ----
    @FXML private TextField sopLink;
    @FXML private TextField incidentLink;
    @FXML private Label     configSaveStatus;

    // ---- Backing lists (full data) ----
    private final ObservableList<User>              userList    = FXCollections.observableArrayList();
    private final ObservableList<LoginHistoryEntry> historyList = FXCollections.observableArrayList();

    // ---- Page-sliced lists (bound to tables) ----
    private final ObservableList<User>              pagedUserItems    = FXCollections.observableArrayList();
    private final ObservableList<LoginHistoryEntry> pagedHistoryItems = FXCollections.observableArrayList();

    // ---- Filtered wrappers (class-level for page recalc) ----
    private FilteredList<User>              filteredUsers;
    private FilteredList<LoginHistoryEntry> filteredHistory;

    // ---- Pagination state ----
    private static final int PAGE_SIZE = 20;
    private int currentUserPage    = 0;
    private int currentHistoryPage = 0;

    private final UserService      userService      = new UserService();
    private final AppConfigService appConfigService = ConfigLoader.getInstance().getAppConfigService();

    // ================================================================
    //  Init
    // ================================================================

    @FXML
    public void initialize() {
        setupUsersTable();
        setupHistoryTable();
        loadUsers();
        loadHistory();
        loadApplicationLinks();
    }

    // ================================================================
    //  Users Table setup
    // ================================================================

    private void setupUsersTable() {
        colUsername.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        colFullName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFullName()));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEmail()));
        // Role value is always "USER"/"BUSINESS"/"ADMIN" in DB; display "IT" for USER in table
        colRole.setCellValueFactory(c -> new SimpleStringProperty(
                "USER".equals(c.getValue().getRole()) ? "IT" : c.getValue().getRole()));
        colActive.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().isActive()).asObject());
        colLastLogin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLastLogin()));

        colActive.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v ? "Yes" : "No");
                applyStyle(v);
            }
            @Override public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                Boolean v = getItem();
                if (v != null && !isEmpty()) applyStyle(v);
            }
            private void applyStyle(Boolean v) {
                setStyle(isSelected() ? "" : (v ? "-fx-text-fill: #059669;" : "-fx-text-fill: #DC2626;"));
            }
        });

        colRole.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                applyStyle(v);
            }
            @Override public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                String v = getItem();
                if (v != null && !isEmpty()) applyStyle(v);
            }
            private void applyStyle(String v) {
                setStyle(isSelected() ? "" :
                        ("ADMIN".equals(v) ? "-fx-text-fill: #E31837; -fx-font-weight: bold;" : "-fx-text-fill: #374151;"));
            }
        });

        filteredUsers = new FilteredList<>(userList, u -> true);
        SortedList<User> sorted = new SortedList<>(pagedUserItems);
        sorted.comparatorProperty().bind(usersTable.comparatorProperty());
        usersTable.setItems(sorted);
        usersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        searchField.textProperty().addListener((obs, old, val) -> {
            filteredUsers.setPredicate(u -> {
                if (val == null || val.isBlank()) return true;
                String q = val.toLowerCase();
                return u.getUsername().toLowerCase().contains(q)
                        || (u.getFullName() != null && u.getFullName().toLowerCase().contains(q))
                        || (u.getEmail()    != null && u.getEmail().toLowerCase().contains(q))
                        || u.getRole().toLowerCase().contains(q);
            });
            currentUserPage = 0;
            updateUserPage();
        });

        usersTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> setUserActionButtons(sel != null));
        setUserActionButtons(false);
    }

    // ================================================================
    //  Login History Table setup
    // ================================================================

    private void setupHistoryTable() {
        histColUsername.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        histColFullName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFullName()));
        histColLoginTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLoginTime()));
        historyTable.setItems(pagedHistoryItems);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        filteredHistory = new FilteredList<>(historyList, h -> true);
        historySearchField.textProperty().addListener((obs, old, val) -> {
            filteredHistory.setPredicate(h -> {
                if (val == null || val.isBlank()) return true;
                String q = val.toLowerCase();
                return h.getUsername().toLowerCase().contains(q)
                        || h.getFullName().toLowerCase().contains(q);
            });
            currentHistoryPage = 0;
            updateHistoryPage();
        });
    }

    // ================================================================
    //  Pagination — Users
    // ================================================================

    private void updateUserPage() {
        java.util.List<User> all = new java.util.ArrayList<>(filteredUsers);
        int total = all.size();
        int pages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        currentUserPage = Math.max(0, Math.min(currentUserPage, pages - 1));
        int from = currentUserPage * PAGE_SIZE;
        int to   = Math.min(from + PAGE_SIZE, total);
        pagedUserItems.setAll(all.subList(from, to));
        userPageInfo.setText("Page " + (currentUserPage + 1) + " / " + pages
                + "  (" + total + " user" + (total != 1 ? "s" : "") + ")");
        userPrevBtn.setDisable(currentUserPage == 0);
        userNextBtn.setDisable(currentUserPage >= pages - 1);
    }

    @FXML private void handleUserPrevPage() { currentUserPage--; updateUserPage(); }
    @FXML private void handleUserNextPage() { currentUserPage++; updateUserPage(); }

    // ================================================================
    //  Pagination — History
    // ================================================================

    private void updateHistoryPage() {
        java.util.List<LoginHistoryEntry> all = new java.util.ArrayList<>(filteredHistory);
        int total = all.size();
        int pages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        currentHistoryPage = Math.max(0, Math.min(currentHistoryPage, pages - 1));
        int from = currentHistoryPage * PAGE_SIZE;
        int to   = Math.min(from + PAGE_SIZE, total);
        pagedHistoryItems.setAll(all.subList(from, to));
        histPageInfo.setText("Page " + (currentHistoryPage + 1) + " / " + pages
                + "  (" + total + " record" + (total != 1 ? "s" : "") + ")");
        histPrevBtn.setDisable(currentHistoryPage == 0);
        histNextBtn.setDisable(currentHistoryPage >= pages - 1);
    }

    @FXML private void handleHistPrevPage() { currentHistoryPage--; updateHistoryPage(); }
    @FXML private void handleHistNextPage() { currentHistoryPage++; updateHistoryPage(); }

    private void setUserActionButtons(boolean enabled) {
        editUserBtn.setDisable(!enabled);
        resetPwdBtn.setDisable(!enabled);
        User sel = usersTable.getSelectionModel().getSelectedItem();
        User me  = SessionContext.getCurrentUser();
        deleteUserBtn.setDisable(!enabled || (sel != null && me != null && sel.getId().equals(me.getId())));
    }

    // ================================================================
    //  Users Tab — CRUD
    // ================================================================

    @FXML private void handleRefreshUsers() { loadUsers(); }

    private void loadUsers() {
        try {
            userList.setAll(userService.getAllUsers());
            currentUserPage = 0;
            updateUserPage();
            userListStatus.setText("");
        } catch (SQLException e) {
            userListStatus.setText("Error loading users: " + e.getMessage());
            log.error("Failed to load users", e);
        }
    }

    @FXML
    private void handleAddUser() {
        showUserDialog("Add User", null).ifPresent(data -> {
            try {
                String tempPwd = userService.createUser(
                        data.get("username"), data.get("fullName"),
                        data.get("email"),    data.get("role"));
                loadUsers();
                sendCredentialEmail(data.get("username"), data.get("fullName"),
                        data.get("email"), tempPwd, false);
            } catch (Exception e) {
                showError("Create User", e.getMessage());
            }
        });
    }

    @FXML
    private void handleEditUser() {
        User sel = usersTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        showUserDialog("Edit User", sel).ifPresent(data -> {
            try {
                userService.updateUser(sel.getId(), data.get("fullName"),
                        data.get("email"), data.get("role"), "1".equals(data.get("active")));
                loadUsers();
                userListStatus.setText("User '" + sel.getUsername() + "' updated.");
            } catch (Exception e) {
                showError("Edit User", e.getMessage());
            }
        });
    }

    @FXML
    private void handleResetPassword() {
        User sel = usersTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset password for '" + sel.getUsername() + "'?\n\n"
                + "A new temporary password will be generated and should be emailed to the user.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Reset Password");
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
            try {
                String tempPwd = userService.adminResetPassword(sel.getId());
                loadUsers();

                User currentUser = SessionContext.getCurrentUser();
                if (currentUser != null && sel.getId().equals(currentUser.getId())) {
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Your password has been reset. You will be logged out now.",
                            ButtonType.OK);
                    info.setTitle("Password Reset");
                    info.setHeaderText(null);
                    info.showAndWait();
                    AppShellController.getInstance().performLogout();
                } else {
                    sendCredentialEmail(sel.getUsername(), sel.getFullName(),
                            sel.getEmail(), tempPwd, true);
                }
            } catch (Exception e) {
                showError("Reset Password", e.getMessage());
            }
        });
    }

    @FXML
    private void handleDeleteUser() {
        User sel = usersTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete user '" + sel.getUsername() + "'?  This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Delete");
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
            try {
                userService.deleteUser(sel.getId());
                loadUsers();
                userListStatus.setText("User '" + sel.getUsername() + "' deleted.");
            } catch (SQLException e) {
                showError("Delete User", e.getMessage());
            }
        });
    }

    // ================================================================
    //  Login History Tab
    // ================================================================

    @FXML private void handleRefreshHistory() { loadHistory(); }

    private void loadHistory() {
        try {
            historyList.setAll(userService.getLoginHistory());
            currentHistoryPage = 0;
            updateHistoryPage();
            historyStatus.setText("");
        } catch (SQLException e) {
            historyStatus.setText("Error loading history: " + e.getMessage());
            log.error("Failed to load login history", e);
        }
    }

    // ================================================================
    //  Configuration Tab — Application Links
    // ================================================================

    private void loadApplicationLinks() {
        ConfigLoader cfg = ConfigLoader.getInstance();
        sopLink.setText(cfg.getSopLink());
        incidentLink.setText(cfg.getIncidentLink());
    }

    @FXML
    private void handleSaveConfiguration() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("link.sop",      sopLink.getText().trim());
        cfg.put("link.incident", incidentLink.getText().trim());
        try {
            appConfigService.saveAll(cfg);
            ConfigLoader.getInstance().reload();
            configSaveStatus.setText("Configuration saved.");
            configSaveStatus.getStyleClass().removeAll("save-status-ok", "save-status-error");
            configSaveStatus.getStyleClass().add("save-status-ok");
        } catch (Exception e) {
            configSaveStatus.setText("Error: " + e.getMessage());
            configSaveStatus.getStyleClass().removeAll("save-status-ok", "save-status-error");
            configSaveStatus.getStyleClass().add("save-status-error");
            log.error("Failed to save configuration", e);
        }
    }

    // ================================================================
    //  Email — send credentials via mail API automatically
    // ================================================================

    /** Sends the generated credentials to the user via the mail API endpoint. */
    private void sendCredentialEmail(String username, String fullName,
                                     String email, String tempPwd, boolean isReset) {
        String baseMsg = (isReset ? "Password reset for '" : "User '") + username
                + (isReset ? "'." : "' created.");

        if (email == null || email.isBlank()) {
            userListStatus.setText(baseMsg + " No email on file — credentials not sent.");
            showMailResult(false, email,
                    "No email address is on file for '" + username + "'.\n"
                    + "The account has been " + (isReset ? "reset" : "created")
                    + " but credentials were not sent.");
            return;
        }

        ConfigLoader cfg = ConfigLoader.getInstance();
        String mailServerApi = cfg.getMailServerApi();
        String mailFrom = cfg.getMailFrom();

        if (mailServerApi == null || mailServerApi.isBlank()) {
            userListStatus.setText(baseMsg + " Mail API not configured — email not sent.");
            showMailResult(false, email,
                "Mail API endpoint is not configured.\n"
                + "Update mail.server.api and mail.from in application.properties.");
            return;
        }

        if (mailFrom == null || mailFrom.isBlank()) {
            userListStatus.setText(baseMsg + " Mail sender not configured — email not sent.");
            showMailResult(false, email,
                "Mail sender is not configured.\n"
                + "Update mail.from in application.properties.");
            return;
        }

        String subject = isReset
                ? "Raw XML Data Puller - Your Password Has Been Reset"
                : "Welcome to Raw XML Data Puller - Your Account Credentials";
        String body = isReset
                ? buildResetEmailBody(fullName, username, tempPwd)
                : buildWelcomeEmailBody(fullName, username, tempPwd);

        new Thread(() -> {
            try {
                MailService.send(mailServerApi, mailFrom, email, subject, body);
                Platform.runLater(() -> {
                    userListStatus.setText(baseMsg + " Credentials emailed to " + email + ".");
                    showMailResult(true, email, null);
                });
            } catch (Exception e) {
                log.error("Failed to send credential email to {}", email, e);
                Platform.runLater(() -> {
                    userListStatus.setText(baseMsg + " Email delivery failed.");
                    showMailResult(false, email, e.getMessage());
                });
            }
        }, "mail-sender").start();
    }

    private void showMailResult(boolean success, String email, String errorDetail) {
        Alert a = new Alert(
                success ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                success
                    ? "Credentials have been sent successfully to:\n" + email
                    : "Email could not be delivered.\n\n" + (errorDetail != null ? errorDetail : ""),
                ButtonType.OK);
        a.setTitle(success ? "Email Sent" : "Email Not Sent");
        a.setHeaderText(null);
        applyDialogStyle(a.getDialogPane());
        a.showAndWait();
    }

    private static final String SYSTEM_MAIL_FOOTER =
            "\n\nRegards,\n"
            + "Raw XML Data Puller Admin Team\n"
            + "Walgreens Pharmacy Systems\n\n"
            + "---\n"
            + "This is a system-generated email. Please do not reply to this message.";

    private String buildWelcomeEmailBody(String fullName, String username, String tempPwd) {
        return "Hello " + (fullName != null && !fullName.isBlank() ? fullName : username) + ",\n\n"
             + "Your account has been created for the Raw XML Data Puller application.\n\n"
             + "Username:            " + username + "\n"
             + "Temporary Password:  " + tempPwd + "\n\n"
             + "IMPORTANT: This password is valid for one-time use only.\n"
             + "You will be required to set a new password upon your first login.\n\n"
             + "Please keep your credentials secure and do not share them with anyone."
             + SYSTEM_MAIL_FOOTER;
    }

    private String buildResetEmailBody(String fullName, String username, String tempPwd) {
        return "Hello " + (fullName != null && !fullName.isBlank() ? fullName : username) + ",\n\n"
             + "Your password for the Raw XML Data Puller application has been reset by an administrator.\n\n"
             + "Username:            " + username + "\n"
             + "Temporary Password:  " + tempPwd + "\n\n"
             + "IMPORTANT: This password is valid for one-time use only.\n"
             + "You will be required to set a new password upon your next login.\n\n"
             + "If you did not request this reset, please contact your administrator immediately."
             + SYSTEM_MAIL_FOOTER;
    }

    // ================================================================
    //  Add/Edit User dialog
    // ================================================================

    private Optional<Map<String, String>> showUserDialog(String title, User existing) {
        boolean isEdit = (existing != null);

        ButtonType saveType   = new ButtonType(isEdit ? "Save Changes" : "Add User", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);
        applyDialogStyle(dialog.getDialogPane());

        TextField usernameField = new TextField(isEdit ? existing.getUsername() : "");
        TextField fullNameField = new TextField(isEdit ? existing.getFullName() : "");
        TextField emailField    = new TextField(isEdit ? existing.getEmail() : "");
        ComboBox<String> roleBox = new ComboBox<>(FXCollections.observableArrayList("USER", "BUSINESS", "ADMIN"));
        // Display "IT" in UI but store "USER" in DB — backend role unchanged
        roleBox.setConverter(new javafx.util.StringConverter<String>() {
            @Override public String toString(String r)   { return "USER".equals(r) ? "IT" : (r != null ? r : ""); }
            @Override public String fromString(String s) { return "IT".equals(s)   ? "USER" : s; }
        });
        CheckBox activeBox = new CheckBox("Active");

        roleBox.setValue(isEdit ? existing.getRole() : "USER");
        activeBox.setSelected(!isEdit || existing.isActive());
        usernameField.setPromptText("Enter One ID");
        fullNameField.setPromptText("Enter full name");
        emailField.setPromptText("user@example.com");

        usernameField.getStyleClass().add("input-field");
        fullNameField.getStyleClass().add("input-field");
        emailField.getStyleClass().add("input-field");
        roleBox.setMaxWidth(Double.MAX_VALUE);
        roleBox.setPrefHeight(36);

        if (isEdit) {
            usernameField.setDisable(true);
            usernameField.getStyleClass().add("input-field-readonly");
        }

        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 4, 24));
        content.getChildren().addAll(
                makeFieldGroup("One ID",    usernameField),
                makeFieldGroup("Full Name", fullNameField),
                makeFieldGroup("Email",     emailField),
                makeFieldGroup("Role",      roleBox)
        );

        if (!isEdit) {
            Label hint = new Label("A temporary password will be auto-generated and sent to the user's email.");
            hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #6B7280; -fx-wrap-text: true;");
            content.getChildren().add(hint);
        }

        if (isEdit) content.getChildren().add(activeBox);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMaxHeight(460);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);

        Button saveBtn   = (Button) dialog.getDialogPane().lookupButton(saveType);
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        if (saveBtn   != null) saveBtn.getStyleClass().addAll("search-btn");
        if (cancelBtn != null) cancelBtn.getStyleClass().addAll("header-btn");

        if (!isEdit) Platform.runLater(usernameField::requestFocus);
        else         Platform.runLater(fullNameField::requestFocus);

        dialog.setResultConverter(btn -> {
            if (btn != saveType) return null;
            Map<String, String> result = new LinkedHashMap<>();
            result.put("username", usernameField.getText().trim());
            result.put("fullName", fullNameField.getText().trim());
            result.put("email",    emailField.getText().trim());
            result.put("role",     roleBox.getValue());
            result.put("active",   activeBox.isSelected() ? "1" : "0");
            return result;
        });

        return dialog.showAndWait();
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private void applyDialogStyle(DialogPane pane) {
        boolean dark = AppShellController.isDarkMode();
        pane.getStylesheets().add(getClass().getResource(
                dark ? "/css/styles-dark.css" : "/css/styles.css").toExternalForm());
        pane.setStyle("-fx-background-color: " + (dark ? "#161B22" : "#FFFFFF") + ";");
        pane.setPrefWidth(480);
    }

    private VBox makeFieldGroup(String labelText, Node field) {
        VBox group = new VBox(5);
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("field-label");
        group.getChildren().addAll(lbl, field);
        return group;
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }
}
