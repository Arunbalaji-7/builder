package com.walgreens.rawxmldatapuller;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        log.info("Starting Raw XML Data Puller");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/app-shell.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 800, 600);
        scene.getStylesheets().add(
                getClass().getResource("/css/styles.css").toExternalForm());

        primaryStage.setTitle("Raw XML Data Puller — Walgreens Pharmacy Systems");
        primaryStage.getIcons().add(
                new Image(getClass().getResourceAsStream("/images/Walgreens-Logo.png")));

        primaryStage.setMinWidth(880);
        primaryStage.setMinHeight(620);
        primaryStage.setScene(scene);
        primaryStage.show();

        log.info("Application window displayed");
    }
}
