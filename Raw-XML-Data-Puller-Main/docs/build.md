# Build Reference

How to build Raw XML Data Puller from source. Three methods are available: the automated script, the Maven CLI, and a full manual step-by-step walkthrough.

## Prerequisites

Before building, ensure the following are available:

**1. JDK 21 (minimum 21.0.10)**

Azul Zulu 21 recommended. Must be a full JDK (not JRE) — `jlink` and `jpackage` must be present.

```powershell
java -version      # must show 21.x.x
jlink --version    # must exist
jpackage --version # must exist
```

**2. JavaFX 21.0.12 JMODs**

Download the "jmods" zip from https://gluonhq.com/products/javafx/, select version 21.0.12, Windows, type "jmods". Extract to `C:\javafx-jmods-21.0.12\`. The folder must contain files like `javafx.controls.jmod`.

**3. Apache Maven 3.9+**

```powershell
mvn --version
```

**4. Internet access (first build only)**

Maven downloads ~50 MB of dependencies into the local repository (`%USERPROFILE%\.m2\repository`). Subsequent builds run fully offline.

---

## Method 1: PowerShell Script (Recommended)

The build script handles prerequisites checking, ProGuard flag management, build execution, artifact verification, and ZIP creation automatically.

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

### CONFIG section

Open [build.ps1](build.ps1) and edit the top block if your environment differs:

```powershell
$APP_NAME       = "Raw-Xml-Data-Puller"       # must match --name in pom.xml
$APP_VERSION    = "1.0"                        # used for output ZIP name only
$VENDOR         = "Walgreens Pharmacy Systems"
$JAVA_HOME_DIR  = "C:\Program Files\Zulu\zulu-21"   # path to your JDK
$JAVAFX_JMODS   = "C:/javafx-jmods-21.0.12"         # path to JavaFX jmods folder
$MVN            = "mvn"                              # or full path e.g. "C:\tools\mvn\bin\mvn"
$PROGUARD_SHRINK    = $false  # set $true to enable class removal (breaks FXML — use carefully)
$PROGUARD_OBFUSCATE = $true  # set $true to obfuscate names (breaks FXML — do not enable)
$ZIP_OUTPUT     = $true       # set $false to skip ZIP creation
```

---

## Method 2: Maven CLI

Run the full pipeline with a single Maven command:

```powershell
mvn clean package -Djavafx.jmods="C:/javafx-jmods-21.0.12"
```

Override the JavaFX JMODs path if it differs from the value in `pom.xml`:

```powershell
mvn clean package -Djavafx.jmods="D:/tools/javafx-jmods-21.0.12"
```

Suppress download progress (cleaner output):

```powershell
mvn clean package -Djavafx.jmods="C:/javafx-jmods-21.0.12" --no-transfer-progress
```

### Output artifacts

After a successful `mvn package`:

| Path | Description |
|---|---|
| `target/Raw-Xml-Data-Puller-1.0-SNAPSHOT.jar` | Plain application JAR |
| `target/Raw-Xml-Data-Puller-1.0-SNAPSHOT-proguarded.jar` | ProGuarded JAR (module-info included) |
| `target/modules/` | All runtime JARs (app + deps, excl. JavaFX) |
| `target/jlink-runtime/` | Custom JRE (JDK 21 + JavaFX 21.0.12) |
| `target/installer/Raw-Xml-Data-Puller/` | Portable app folder |
| `target/installer/Raw-Xml-Data-Puller/Raw-Xml-Data-Puller.exe` | Windows launcher |

---

## Method 3: Manual Step-by-Step

This is the full breakdown of what `mvn package` does internally. Useful for debugging or for running individual stages.

### Step 1 — Compile

```powershell
mvn compile
```

Compiles all `.java` files in `src/main/java/` to `target/classes/`. The `module-info.java` at the root of the Java source tree is compiled as part of the named module `com.walgreens.rawxmldatapuller`.

### Step 2 — Package (plain JAR)

```powershell
mvn jar:jar
```

Creates `target/Raw-Xml-Data-Puller-1.0-SNAPSHOT.jar` from `target/classes/`. No dependencies are included.

### Step 3 — Copy runtime dependencies

```powershell
mvn dependency:copy-dependencies -DoutputDirectory=target/modules -DincludeScope=runtime -DexcludeGroupIds=org.openjfx
```

Copies all runtime-scope dependency JARs to `target/modules/`. JavaFX JARs are excluded because jlink receives them from the `.jmod` files directly.

### Step 4 — ProGuard

ProGuard is invoked by `maven-plugin` during `package` phase. To run standalone:

```powershell
$JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
java -jar proguard.jar @proguard.cfg `
  -injars  target/Raw-Xml-Data-Puller-1.0-SNAPSHOT.jar(!module-info.class) `
  -outjars target/Raw-Xml-Data-Puller-1.0-SNAPSHOT-proguarded.jar
```

The `(!module-info.class)` filter strips the compile-time module descriptor so moditect can inject the final runtime version in the next step.

### Step 5 — moditect (add module-info to deps)

moditect runs automatically during the Maven `package` phase. It reads the module descriptors embedded in `pom.xml` and writes patched JARs to `target/modules/`. There is no standalone CLI for this step.

### Step 6 — jlink (create custom JRE)

First, remove any stale output:

```powershell
if (Test-Path target\jlink-runtime) { Remove-Item target\jlink-runtime -Recurse -Force }
```

Then run jlink (adjust JAVAFX_JMODS path as needed):

```powershell
jlink `
  --module-path "C:/javafx-jmods-21.0.12" `
  --add-modules java.base,java.sql,java.xml,java.desktop,java.logging,java.naming,java.management,java.transaction.xa,java.security.jgss,java.prefs,java.compiler,jdk.crypto.ec,jdk.localedata,javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.media,javafx.swing `
  --output target/jlink-runtime `
  --no-header-files `
  --no-man-pages `
  --strip-debug `
  --compress=1 `
  --ignore-signing-information `
  --bind-services
```

### Step 7 — jpackage (create app-image)

First, remove any stale output:

```powershell
if (Test-Path target\installer) { Remove-Item target\installer -Recurse -Force }
```

Then run jpackage:

```powershell
jpackage `
  --type app-image `
  --runtime-image target/jlink-runtime `
  --input target/modules `
  --main-jar Raw-Xml-Data-Puller-1.0-SNAPSHOT-proguarded.jar `
  --main-class com.walgreens.rawxmldatapuller.Launcher `
  --java-options --add-modules=ALL-MODULE-PATH `
  --name Raw-Xml-Data-Puller `
  --app-version 1.0 `
  --vendor "Walgreens Pharmacy Systems" `
  --dest target/installer
```

The `--type app-image` flag creates a portable folder (no WiX Toolset required). The `--add-modules=ALL-MODULE-PATH` option makes JavaFX modules in the bundled JRE visible to the app running on the classpath.

### Step 8 — Create ZIP (optional)

```powershell
Compress-Archive -Path target\installer\Raw-Xml-Data-Puller `
                 -DestinationPath target\Raw-Xml-Data-Puller-1.0.zip
```

---

## Troubleshooting

### Credential email not sent after user create/reset

Check the following in order:

1. `mail.server.api` is configured and reachable from the desktop machine.
2. `mail.from` is non-empty.
3. `eRx-mail-server` WAR is deployed and responding on `/api/send-mail`.
4. Mail API server can reach relay `corpsmtprelay.walgreens.com:25`.

Quick endpoint check:

```powershell
Invoke-WebRequest -Uri "http://tla-w01rxm0101/erx-mail-server/api/send-mail" -Method GET
```

Quick form POST check:

```powershell
Invoke-RestMethod -Uri "http://tla-w01rxm0101/erx-mail-server/api/send-mail" -Method Post -Body @{
  mail_from = "rawxmldatapuller@walgreens.com"
  mail_to   = "recipient@example.com"
  subject   = "Test Email"
  body      = "Hello, this is a test email."
}
```

### `jlink: Error: directory already exists`

A previous build left stale output. The build script removes these automatically. If running Maven directly, delete `target/jlink-runtime` and `target/installer` before re-running.

```powershell
Remove-Item target\jlink-runtime -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item target\installer     -Recurse -Force -ErrorAction SilentlyContinue
mvn package -Djavafx.jmods="C:/javafx-jmods-21.0.12" --no-transfer-progress
```

### `jpackage: Invalid or unsupported type: [exe]`

This is an `.exe` installer type (not app-image). It requires WiX Toolset. The pom.xml is configured for `app-image` which needs no installer. If you see this, your pom.xml has been modified — revert `--type` to `app-image`.

### `Failed to launch JVM` when running the .exe

Occurs when the app is packaged as a named JPMS module (`--module` flag in jpackage). The fix is classpath mode: use `--input --main-jar --main-class` (which is what the current pom.xml does). Do not change jpackage arguments to use `--module`.

### `ProGuard: Can't read [path]` with spaces in path

The JDK is at `C:\Program Files\Zulu\zulu-21` (space in path). ProGuard resolves this via the built-in `<java.home>` variable in `proguard.cfg`. Do not replace `<java.home>` with a literal path that contains spaces.

### `moditect: File is already modular`

ProGuard preserved a `module-info.class` in its output. Ensure the pom.xml ProGuard configuration has `<inFilter>!module-info.class</inFilter>`. This strips the compile-time module-info so moditect can inject the runtime version.

### Maven downloads fail / offline environment

Ensure all dependencies are in the local Maven repository (`%USERPROFILE%\.m2\repository`) from a prior online build, then add `-o` for offline mode:

```powershell
mvn clean package -Djavafx.jmods="C:/javafx-jmods-21.0.12" -o
```

---

## Version Update Checklist

When updating the JavaFX version:
1. Update `<javafx.version>` in `pom.xml`
2. Update the jmods folder (download new JMODs, extract)
3. Update `$JAVAFX_JMODS` in `build.ps1`, `test.ps1`, `zip.ps1`
4. Update `-libraryjars` paths in `proguard.cfg`

When updating the JDK version:
1. Update `$JAVA_HOME_DIR` in `build.ps1`, `test.ps1`, `zip.ps1`
2. Update `<maven.compiler.release>` in `pom.xml`
3. Verify `proguard.cfg` jmod list still covers all required modules

## Mail API Dependency

The desktop app sends credential emails through the deployed `eRx-mail-server` API endpoint, not direct SMTP.

Before UAT/production validation, ensure this URL is reachable from the desktop host:

`http://tla-w01rxm0101/erx-mail-server/api/send-mail`

And configure these keys:

- `mail.server.api`
- `mail.from`

in `src/main/resources/application.properties` (or in `raw_xml_data_puller_app_config` overrides).
