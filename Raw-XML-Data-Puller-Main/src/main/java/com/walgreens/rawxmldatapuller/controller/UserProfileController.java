package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.UserService;
import com.walgreens.rawxmldatapuller.util.SessionContext;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UserProfileController {

    @FXML private Label avatarLabel;
    @FXML private Label fullNameLabel;
    @FXML private Label usernameLabel;
    @FXML private Label roleLabel;

    @FXML private PasswordField currentPwdField;
    @FXML private TextField     currentPwdFieldTxt;
    @FXML private Button        currentPwdToggleBtn;
    @FXML private PasswordField newPwdField;
    @FXML private TextField     newPwdFieldTxt;
    @FXML private Button        newPwdToggleBtn;
    @FXML private PasswordField confirmPwdField;
    @FXML private TextField     confirmPwdFieldTxt;
    @FXML private Button        confirmPwdToggleBtn;
    @FXML private Label         pwdStatusLabel;
    @FXML private Button        changePwdBtn;

    // Password criteria labels
    @FXML private VBox  pwdCriteriaPane;
    @FXML private Label critLength;
    @FXML private Label critUpper;
    @FXML private Label critLower;
    @FXML private Label critNumber;
    @FXML private Label critSpecial;

    private final UserService    userService = new UserService();
    private final ExecutorService executor   = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "profile-thread");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        User user = SessionContext.getCurrentUser();
        if (user == null) return;

        String initials = user.getFullName().substring(0, 1).toUpperCase();
        avatarLabel.setText(initials);
        fullNameLabel.setText(user.getFullName());
        usernameLabel.setText("@" + user.getUsername());
        roleLabel.setText(user.getRole());
        roleLabel.getStyleClass().add(user.isAdmin() ? "role-admin" : "role-user");
        hidePwdStatus();

        bindPwdField(currentPwdField, currentPwdFieldTxt, currentPwdToggleBtn);
        bindPwdField(newPwdField,     newPwdFieldTxt,     newPwdToggleBtn);
        bindPwdField(confirmPwdField, confirmPwdFieldTxt, confirmPwdToggleBtn);

        newPwdField.textProperty().addListener((obs, old, text) -> updatePasswordCriteria(text));
    }

    private void bindPwdField(PasswordField pwd, TextField txt, Button btn) {
        txt.textProperty().bindBidirectional(pwd.textProperty());
        setEyeIcon(btn, false);
    }

    private void setEyeIcon(Button btn, boolean showing) {
        btn.setGraphic(new FontAwesomeIconView(
                showing ? FontAwesomeIcon.EYE_SLASH : FontAwesomeIcon.EYE, "15"));
    }

    private void togglePwd(PasswordField pwd, TextField txt, Button btn) {
        boolean show = !txt.isVisible();
        pwd.setVisible(!show); pwd.setManaged(!show);
        txt.setVisible(show);  txt.setManaged(show);
        setEyeIcon(btn, show);
        if (show) txt.end();
    }

    @FXML private void toggleCurrentPwdVisibility() { togglePwd(currentPwdField, currentPwdFieldTxt, currentPwdToggleBtn); }
    @FXML private void toggleNewPwdVisibility()     { togglePwd(newPwdField,     newPwdFieldTxt,     newPwdToggleBtn); }
    @FXML private void toggleConfirmPwdVisibility() { togglePwd(confirmPwdField, confirmPwdFieldTxt, confirmPwdToggleBtn); }

    // ----------------------------------------------------------------
    //  Password strength criteria
    // ----------------------------------------------------------------

    private void updatePasswordCriteria(String password) {
        boolean hasLength  = password.length() >= 8;
        boolean hasUpper   = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower   = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit   = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));

        setCrit(critLength,  hasLength,  "Minimum 8 characters");
        setCrit(critUpper,   hasUpper,   "At least one uppercase letter");
        setCrit(critLower,   hasLower,   "At least one lowercase letter");
        setCrit(critNumber,  hasDigit,   "At least one number");
        setCrit(critSpecial, hasSpecial, "At least one special character");

        boolean show = !password.isEmpty();
        pwdCriteriaPane.setVisible(show);
        pwdCriteriaPane.setManaged(show);
    }

    private void setCrit(Label label, boolean met, String text) {
        label.setText((met ? "✓  " : "✗  ") + text);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (met ? "#27ae60;" : "#e74c3c;"));
    }

    private boolean isPasswordStrong(String password) {
        return password.length() >= 8
            && password.chars().anyMatch(Character::isUpperCase)
            && password.chars().anyMatch(Character::isLowerCase)
            && password.chars().anyMatch(Character::isDigit)
            && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }

    @FXML
    private void handleChangePassword() {
        String current = currentPwdField.getText();
        String next    = newPwdField.getText();
        String confirm = confirmPwdField.getText();

        if (current.isEmpty() || next.isEmpty() || confirm.isEmpty()) {
            showPwdStatus("All password fields are required.", false); return;
        }
        if (!next.equals(confirm)) {
            showPwdStatus("New passwords do not match.", false); return;
        }
        if (!isPasswordStrong(next)) {
            showPwdStatus("Password must be at least 8 characters and include uppercase, lowercase, a number, and a special character.", false); return;
        }

        changePwdBtn.setDisable(true);
        executor.submit(() -> {
            try {
                User user = SessionContext.getCurrentUser();
                userService.changeOwnPassword(user.getId(), current, next);
                Platform.runLater(() -> {
                    changePwdBtn.setDisable(false);
                    currentPwdField.clear(); newPwdField.clear(); confirmPwdField.clear();
                    resetPwdVisibility();

                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Your password has been changed successfully.\nYou will now be logged out for security.",
                            ButtonType.OK);
                    info.setTitle("Password Changed");
                    info.setHeaderText(null);
                    info.showAndWait();

                    AppShellController.getInstance().performLogout();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    changePwdBtn.setDisable(false);
                    showPwdStatus(e.getMessage(), false);
                });
            }
        });
    }

    private void showPwdStatus(String msg, boolean success) {
        pwdStatusLabel.setText(msg);
        pwdStatusLabel.getStyleClass().removeAll("pwd-status-ok", "pwd-status-error");
        pwdStatusLabel.getStyleClass().add(success ? "pwd-status-ok" : "pwd-status-error");
        pwdStatusLabel.setVisible(true);
        pwdStatusLabel.setManaged(true);
    }

    private void hidePwdStatus() {
        pwdStatusLabel.setVisible(false);
        pwdStatusLabel.setManaged(false);
    }

    private void resetPwdVisibility() {
        for (var pair : new Object[][]{{currentPwdField, currentPwdFieldTxt, currentPwdToggleBtn},
                                       {newPwdField,     newPwdFieldTxt,     newPwdToggleBtn},
                                       {confirmPwdField, confirmPwdFieldTxt, confirmPwdToggleBtn}}) {
            ((PasswordField) pair[0]).setVisible(true);  ((PasswordField) pair[0]).setManaged(true);
            ((TextField)     pair[1]).setVisible(false); ((TextField)     pair[1]).setManaged(false);
            setEyeIcon((Button) pair[2], false);
        }
        pwdCriteriaPane.setVisible(false);
        pwdCriteriaPane.setManaged(false);
    }

}

