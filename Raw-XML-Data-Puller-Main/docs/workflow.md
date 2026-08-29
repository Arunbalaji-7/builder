# Build Workflow

This document explains the full build pipeline for Raw XML Data Puller, from source code to distributable Windows `.exe`.

## Pipeline Overview

```
Source (.java)
    │
    ▼  [1] maven-compiler-plugin
Compiled classes (target/classes/)
    │
    ▼  [2] maven-jar-plugin
Plain JAR (target/Raw-Xml-Data-Puller-1.0-SNAPSHOT.jar)
    │
    ▼  [3] maven-dependency-plugin
Runtime deps copied to target/modules/
(JavaFX JARs excluded — provided via JMODs at jlink step)
    │
    ▼  [4] ProGuard (com.github.wvengen:proguard-maven-plugin)
ProGuarded JAR (target/Raw-Xml-Data-Puller-1.0-SNAPSHOT-proguarded.jar)
module-info.class STRIPPED — moditect adds final version next
    │
    ▼  [5] moditect-maven-plugin
Modular JARs written to target/modules/
  - ojdbc11 → module com.oracle.database.jdbc
  - mysql-connector-j → module com.mysql.cj
  - protobuf-java → module com.google.protobuf
  - app JAR → module com.walgreens.rawxmldatapuller (runtime module-info)
    │
    ▼  [6] jlink
JRE-only image (target/jlink-runtime/)
Contains: JDK 21 modules + JavaFX 21.0.12 modules
App JARs are NOT embedded — they go on the classpath
    │
    ▼  [7] jpackage (--type app-image)
Portable app folder (target/installer/Raw-Xml-Data-Puller/)
  Raw-Xml-Data-Puller.exe  — launcher
  runtime/                 — the jlink image (bundled JRE)
  app/                     — all JARs from target/modules/
```

## Step Details

### Step 1: Compile

`maven-compiler-plugin` compiles at `--release 21`. `module-info.java` is compiled as part of the named module `com.walgreens.rawxmldatapuller`. The compile-time module descriptor uses `mysql.connector.j` (the automatic module name from the JAR filename).

### Step 2: Create JAR

`maven-jar-plugin` packages the compiled classes into a plain (non-shaded) JAR. No dependencies are bundled. The manifest sets `Main-Class: com.walgreens.rawxmldatapuller.Launcher`.

### Step 3: Copy Dependencies

`maven-dependency-plugin` copies all `runtime`-scope dependencies to `target/modules/`. JavaFX JARs are excluded because jlink gets JavaFX directly from the `.jmod` files — bundling the JARs too would duplicate them.

### Step 4: ProGuard

ProGuard processes only the application JAR (not the dependency JARs). The configuration in `proguard.cfg`:
- **`-dontshrink`**: keeps all classes (required — FXML loads controllers by reflection using class name strings)
- **`-dontobfuscate`**: preserves class/method names (required for FXML)
- **`-dontoptimize`**: skip bytecode optimisation (stability)
- **`<inFilter>!module-info.class</inFilter>`**: strips the compile-time `module-info.class` from the output so moditect can inject the correct runtime version in step 5

### Step 5: moditect

`moditect-maven-plugin` adds `module-info.class` to non-modular JARs and replaces the compile-time module-info in the ProGuarded app JAR. Key replacements:
- `mysql.connector.j` (automatic, derived from filename) → `com.mysql.cj` (clean module name)
- `ojdbc11` (non-modular) → `com.oracle.database.jdbc` (proper JPMS descriptor)

All patched JARs land in `target/modules/`.

### Step 6: jlink

Creates a JRE-only image containing only the JDK modules + JavaFX modules the app needs. The app JARs are **not** embedded in the image — they are served via the jpackage `--input` path (classpath mode). This avoids JPMS resource-encapsulation issues with `FXMLLoader`.

Flags: `--strip-debug --no-header-files --no-man-pages --compress=1` — reduces image size from ~300 MB to ~123 MB.

### Step 7: jpackage

Creates a portable app-image folder (no WiX Toolset required). The `--type app-image` flag produces a plain folder instead of an MSI/EXE installer. Key flags:

```
--input target/modules          all JARs placed on classpath
--main-jar ...-proguarded.jar   entry point JAR
--main-class ...Launcher        entry point class
--java-options --add-modules=ALL-MODULE-PATH
                                makes JavaFX modules visible to the classpath app
--runtime-image target/jlink-runtime
                                bundles the custom JRE
```

The `--add-modules=ALL-MODULE-PATH` option is the bridge: the app runs as an **unnamed module** (classpath mode, no JPMS encapsulation), but it can access JavaFX because all modules in the bundled JRE are explicitly opened to the unnamed module.

## Why Classpath Mode?

The app uses `FXMLLoader` with `@/images/...` resource references. In a named JPMS module embedded in a jlink image, `ClassLoader.getResource("images/Walgreens-Logo.png")` returns `null` even for the module's own resources — JPMS treats paths containing `/` as package-qualified resources, which are only accessible from within the declaring module itself.

Running as an unnamed module (classpath) bypasses JPMS encapsulation entirely. All resource lookups work as they do during development.

## Maven Phase Mapping

| Maven Phase | Plugin | Action |
|---|---|---|
| `compile` | maven-compiler-plugin | Compile Java sources |
| `prepare-package` | maven-dependency-plugin | Copy runtime deps to target/modules |
| `package` | maven-jar-plugin | Create plain JAR |
| `package` | proguard-maven-plugin | Process app JAR through ProGuard |
| `package` | moditect-maven-plugin | Patch module-infos, output to target/modules |
| `package` | exec-maven-plugin (x4) | Clean dirs, run jlink, clean dir, run jpackage |

## Runtime Credential-Email Workflow

When an admin creates a user or resets a password, the desktop app sends credentials through the mail API endpoint.

1. Admin action in `AdminController` triggers credential generation/reset.
2. App reads `mail.server.api` and `mail.from` from config loader (DB override or properties default).
3. App composes form payload:
    - `mail_from`
    - `mail_to`
    - `subject`
    - `body`
4. `MailService` sends HTTP POST (`application/x-www-form-urlencoded`).
5. `eRx-mail-server` receives request and relays message via SMTP (server-side).
6. Desktop app shows success/failure status to admin.

See [workflow-diagram.svg](workflow-diagram.svg) for a visual sequence.
