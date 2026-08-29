// Compile-time module descriptor.
// NOTE: mysql.connector.j is the automatic module name derived from mysql-connector-j-*.jar at compile time.
// moditect replaces this class with the final module-info (com.mysql.cj) after ProGuard.
module com.walgreens.rawxmldatapuller {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.desktop;
    requires java.logging;
    requires java.naming;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires com.oracle.database.jdbc;
    requires mysql.connector.j;
    requires com.jcraft.jsch;
    requires de.jensd.fx.glyphs.fontawesome;
    requires de.jensd.fx.glyphs.commons;

    opens com.walgreens.rawxmldatapuller            to javafx.graphics, javafx.fxml;
    opens com.walgreens.rawxmldatapuller.controller to javafx.fxml;
    opens com.walgreens.rawxmldatapuller.model      to javafx.base, javafx.fxml;
    opens com.walgreens.rawxmldatapuller.service    to javafx.fxml;
    opens com.walgreens.rawxmldatapuller.ui         to javafx.fxml;
    opens com.walgreens.rawxmldatapuller.util       to javafx.fxml;

    exports com.walgreens.rawxmldatapuller;
}
