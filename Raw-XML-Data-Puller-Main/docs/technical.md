# Technical Reference

Architecture, module structure, and design decisions for Raw XML Data Puller.

## Application Architecture

```
com.walgreens.rawxmldatapuller   (module root)
│
├── MainApp.java         Application entry point (extends javafx.application.Application)
├── Launcher.java        Bootstrap class (no JavaFX parent) — required for classpath mode
│
├── controller/          FXML controllers — loaded by FXMLLoader via reflection
│   ├── AppShellController    Root window shell; owns login overlay + sidebar navigation
│   ├── LoginController       Credential entry and SSH/DB auth flow
│   ├── MainController        Query search panel; cached across sidebar navigations
│   ├── SettingsController    DB/SSH configuration UI
│   ├── UserProfileController Current user info
│   ├── AdminController       Admin panel (role-gated)
│   └── SearchFlowHandler     Orchestrates multi-step query execution
│
├── service/             Business logic; no JavaFX dependencies
│   ├── AuthService           Login + session management
│   ├── DatabaseService       JDBC query execution (Oracle + MySQL)
│   ├── DbPingService         Background DB connectivity check
│   ├── SshService            JSch SSH tunnel setup/teardown
│   ├── AppConfigService      Loads and stores DB/SSH config
│   ├── UserService           User CRUD
│   ├── MailService           Calls remote mail API endpoint
│   ├── Method1-4Service      Four XML extraction strategies
│   └── SshCredentialCache    Short-lived SSH credential store
│
├── model/
│   ├── User                  Authenticated user + role
│   ├── DbConfig              JDBC connection parameters
│   └── SearchResult          Query result container
│
├── ui/                  Reusable UI components
│   ├── TerminalPanel         Scrollable log panel mimicking a terminal
│   ├── XmlHighlightPane      Syntax-highlighted XML viewer
│   ├── MethodBlock           Collapsible query-method result block
│   ├── TerminalLine          Single terminal log entry
│   ├── LineType              Enum: INFO / WARN / ERROR / SUCCESS
│   └── AppDialogs            Static helpers for Alert/Confirm dialogs
│
└── util/
    ├── ConfigLoader          Singleton config file reader
    ├── SessionContext        Thread-local authenticated session
    ├── CryptoUtil            AES encryption for stored credentials
    ├── PasswordUtil          BCrypt password hashing
    └── XmlFormatter          Pretty-print raw XML strings
```

## FXML / Resource Layout

```
src/main/resources/
  fxml/
    app-shell.fxml        Root window; contains loginOverlay + mainLayout
    login.fxml            Credential input form
    main.fxml             Query search + results panel
    settings.fxml         DB/SSH config form
    user-profile.fxml     User info view
    admin-panel.fxml      Admin CRUD panel
  css/
    styles.css            Light theme
    styles-dark.css       Dark theme
  images/
    Walgreens-Logo.png    Window icon (16px)
    Walgreens-Full-Logo.png  Splash/header logo
  db/
    init.sql              Database schema initialisation
  application.properties  App config defaults (includes mail.server.api, mail.from)
  logback.xml             Logging configuration
```

## JPMS Module Descriptor

### Compile-time (`src/main/java/module-info.java`)

```java
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
    requires mysql.connector.j;       // automatic name from jar filename
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
```

### Runtime (injected by moditect after ProGuard)

The runtime module-info replaces `mysql.connector.j` with `com.mysql.cj` — the moditect-assigned clean module name for MySQL Connector/J. This is because `mysql.connector.j` is an automatic module name (derived from the JAR filename) which is unstable and not a real module name, while `com.mysql.cj` is the explicit name given by the moditect patch.

## Dependency Map

| JAR | Scope | Module Name | Source |
|---|---|---|---|
| javafx-controls-21.0.12 | compile/runtime | `javafx.controls` | Maven Central + JMODs |
| javafx-fxml-21.0.12 | compile/runtime | `javafx.fxml` | Maven Central + JMODs |
| ojdbc11-21.9.0.0 | runtime | `com.oracle.database.jdbc` | moditect-patched |
| mysql-connector-j-8.3.0 | runtime | `com.mysql.cj` | moditect-patched |
| protobuf-java-3.25.1 | runtime | `com.google.protobuf` | moditect-patched (transitive of MySQL) |
| jsch-0.2.16 | runtime | `com.jcraft.jsch` | named via Multi-Release JAR |
| fontawesomefx-fontawesome | runtime | `de.jensd.fx.glyphs.fontawesome` | named |
| fontawesomefx-commons | runtime | `de.jensd.fx.glyphs.commons` | named |
| slf4j-api-2.0.9 | runtime | `org.slf4j` | named |
| logback-classic-1.4.11 | runtime | `ch.qos.logback.classic` | named |
| junit-jupiter | test | — | not packaged |

## Key Design Decisions

### API-based Mail Instead of Desktop SMTP

Desktop-side SMTP handling has been removed to avoid direct relay dependencies on each client host. The desktop app now calls a central endpoint (`eRx-mail-server`) and treats mail as an external service.

Benefits:

- Centralized relay defaults (`corpsmtprelay.walgreens.com:25`) and future auth/TLS policy changes.
- Simplified desktop network and security posture.
- Better observability at a single mail service boundary.

### Classpath Mode (jpackage)

The application runs as an **unnamed module** at runtime even though JPMS module descriptors are present. This is intentional.

`FXMLLoader` resolves resources like `@/images/Walgreens-Logo.png` using `ClassLoader.getResource("images/Walgreens-Logo.png")`. When the app is a **named module** inside a jlink image, JPMS treats paths containing `/` as package-qualified resources. These are only accessible from within the same module — `ClassLoader.getResource()` returns `null` even for a module trying to access its own resources from outside the module boundary.

Classpath mode (jpackage `--input --main-jar --main-class`) avoids this entirely. All resource lookups behave identically to running with `mvn javafx:run`. The `--add-modules=ALL-MODULE-PATH` JVM option makes JavaFX visible to the unnamed module.

### ProGuard — No Shrinking or Obfuscation

FXML files reference controllers by fully-qualified class name (e.g. `fx:controller="com.walgreens.rawxmldatapuller.controller.LoginController"`). FXMLLoader instantiates these via `Class.forName()` at runtime. If ProGuard renames or removes them, the application crashes at startup. The `proguard.cfg` therefore uses `-dontshrink -dontobfuscate`. ProGuard is retained in the pipeline as a processing step that can be enabled for future needs.

### Two-Phase module-info (compile → runtime)

MySQL Connector/J does not ship a `module-info.class`. At compile time its automatic module name is derived from its JAR filename (`mysql-connector-j-8.3.0.jar` → `mysql.connector.j`). This name is unstable and changes with version bumps. moditect replaces it with the explicit `com.mysql.cj` module name. The compile-time `module-info.class` is stripped by ProGuard (`<inFilter>!module-info.class</inFilter>`) so moditect can inject the final version cleanly.

### ProGuard `<java.home>` Variable

The JDK is installed at `C:\Program Files\Zulu\zulu-21` — a path with spaces. ProGuard's `-libraryjars 'path(!filter)'` syntax breaks when the path contains spaces (it treats the space as a path separator). ProGuard's built-in `<java.home>` variable handles quoted paths internally, so all JDK jmod entries in `proguard.cfg` use `<java.home>/jmods/...` instead of the literal path.

## ProGuard Configuration (`proguard.cfg`)

```
-dontshrink        preserve all classes (FXML reflection requires them)
-dontobfuscate     preserve class/method names (FXML fx:controller attribute)
-dontoptimize      skip bytecode optimisation (stability)
-dontwarn **       suppress all warnings (many 3rd-party JARs have missing refs)

-libraryjars '<java.home>/jmods/*.jmod'(!**.jar;!module-info.class)
-libraryjars 'C:/javafx-jmods-21.0.12/*.jmod'(!**.jar;!module-info.class)
    The (!**.jar;!module-info.class) filter tells ProGuard to read only
    the .class files from the jmod archive, not nested JARs or module-info.

-keep public class com.walgreens.rawxmldatapuller.** { public protected *; }
    Keeps all application classes and their public/protected members.

-keep class * implements java.sql.Driver { *; }
    Keeps JDBC driver implementations for ServiceLoader discovery.

-keepattributes Signature, *Annotation*, ...
    Preserves reflection metadata needed by JavaFX property binding.
```

## jlink Module Set

```
java.base, java.sql, java.xml, java.desktop, java.logging,
java.naming, java.management, java.transaction.xa,
java.security.jgss, java.prefs, java.compiler,
jdk.crypto.ec,       required for TLS (SSH + HTTPS)
jdk.localedata,      date/number formatting
javafx.controls, javafx.fxml, javafx.graphics,
javafx.base, javafx.media, javafx.swing
```

`--strip-debug --no-header-files --no-man-pages --compress=1` reduce the bundled JRE from ~300 MB to ~123 MB.

## Mail Delivery Architecture (Current)

The application now uses an API relay pattern for email:

- Desktop app invokes `mail.server.api` using HTTP POST (`application/x-www-form-urlencoded`).
- Desktop app sends: `mail_from`, `mail_to`, `subject`, `body`.
- `eRx-mail-server` (WAR) receives request and performs SMTP relay server-side.

This removes SMTP handling from the desktop runtime and centralizes mail relay behavior.

See [architecture-diagram.svg](architecture-diagram.svg) for the current system view.
