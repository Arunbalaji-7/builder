package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.XmlFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.function.BiConsumer;

/**
 * Service that implements Method 4 of the RAW XML retrieval workflow.
 *
 * <p>Triggered when all three prior methods have failed.  Instead of relying on
 * a DB-stored path for the specific Rx, this method:</p>
 * <ol>
 *   <li>Fetches <em>any</em> IMAGE_ID for the store from IC+ ({@code TBF0_RX})
 *       to obtain a representative LOC_PATH_OR_ID template.</li>
 *   <li>Retrieves that template path from Vision ({@code TCT0_RX_IMAGE}).</li>
 *   <li>Builds a custom path by substituting the user-supplied date and the
 *       actual Rx number into the template, preserving intermediate segments.</li>
 *   <li>SSH-greps the constructed path on {@code pvisapp1} for the MSG ID,
 *       with an automatic ±{@value #DAY_OFFSETS}-day fallback if the exact
 *       directory yields no match (identical strategy to Method 3).</li>
 * </ol>
 *
 * <p>If {@code erxMsgId} is {@code null} (Rx/Store input mode where Method 2
 * could not resolve it), the service falls back to querying
 * {@code TBF0_ERX_MSG_MAPPING} before the SSH step.</p>
 */
public class Method4Service {

    private static final Logger log         = LoggerFactory.getLogger(Method4Service.class);
    private static final int    DAY_OFFSETS = 5;

    private final DatabaseService db;

    public Method4Service(DatabaseService db) { this.db = db; }

    // ================================================================
    //  Public execution path
    // ================================================================

    /**
     * @param rxNbr       Rx number (always available)
     * @param storeNbr    Store number (always available)
     * @param erxMsgId    EPBR_ERX_MSG_ID / SST_MSG_ID, or {@code null} to auto-resolve
     * @param rxDate      User-provided date in {@code MM/DD/YYYY} format
     * @param sshUser     SSH username
     * @param sshPassword SSH password
     * @param stepCallback progress reporter — step index (1-based) + message
     */
    public SearchResult execute(String rxNbr, String storeNbr, String erxMsgId,
                                String rxDate,
                                String sshUser, String sshPassword,
                                BiConsumer<Integer, String> stepCallback) throws Exception {

        // Step 1 — IC+ DB: any IMAGE_ID for this store (template record)
        stepCallback.accept(1, "Querying IC+ DB: TBF0_RX (store=" + storeNbr + ")...");
        String imageId = getAnyImageId(storeNbr);
        if (imageId == null)
            return SearchResult.notFound("No records in TBF0_RX for Store: " + storeNbr);
        log.info("Method4 Step1: IMAGE_ID={}", imageId);
        stepCallback.accept(1, "IMAGE_ID = " + imageId + " (template record)");

        // Step 2 — Vision DB: get path template
        stepCallback.accept(2, "Querying Vision DB: TCT0_RX_IMAGE (IMAGE_ID=" + imageId + ")...");
        String locPath = getLocPath(imageId);
        if (locPath == null)
            return SearchResult.notFound("No image path in Vision DB for IMAGE_ID: " + imageId);
        log.info("Method4 Step2: LOC_PATH_OR_ID={}", locPath);
        stepCallback.accept(2, "LOC_PATH_OR_ID = " + locPath);

        // Step 3 — build custom path
        stepCallback.accept(3, "Building custom path (date=" + rxDate + ", store=" + storeNbr + ")...");
        String customPath = buildCustomPath(locPath, rxDate, storeNbr);
        if (customPath == null)
            return SearchResult.notFound(
                    "Could not build path — YYYY/MM/DD pattern not detected in template: " + locPath);
        log.info("Method4 Step3: customPath={}", customPath);
        stepCallback.accept(3, "Custom path = " + customPath);

        // Resolve MSG ID if not already known
        String grepId = (erxMsgId != null && !erxMsgId.isBlank()) ? erxMsgId : null;
        if (grepId == null) {
            stepCallback.accept(3, "Resolving EPBR_ERX_MSG_ID from IC+ DB...");
            grepId = getErxMsgId(rxNbr, storeNbr);
            if (grepId == null)
                return SearchResult.notFound(
                        "EPBR_ERX_MSG_ID not found in TBF0_ERX_MSG_MAPPING for Rx: " + rxNbr
                                + ", Store: " + storeNbr);
            log.info("Method4: resolved EPBR_ERX_MSG_ID={}", grepId);
        }

        // Step 4 — SSH grep with ±5-day fallback
        stepCallback.accept(4, "Connecting to SSH: " + ConfigLoader.getInstance().getSshServer() + "...");
        return runSshGrep(grepId, customPath, sshUser, sshPassword, stepCallback);
    }

    // ================================================================
    //  SSH — main grep with day-offset fallback
    // ================================================================

    private SearchResult runSshGrep(String erxMsgId, String customPath,
                                    String sshUser, String sshPassword,
                                    BiConsumer<Integer, String> stepCallback) throws Exception {
        try (SshService ssh = new SshService()) {
            ssh.connect(sshUser, sshPassword);
            stepCallback.accept(4, "Connected. Searching in: " + customPath);

            String[] found = findFileWithDayFallback(ssh, erxMsgId, customPath, stepCallback);

            if (found == null) {
                return SearchResult.notFound(
                        "No file found for MSG ID '" + erxMsgId + "' in: " + customPath
                                + "  (checked ±" + DAY_OFFSETS + " day offsets)");
            }

            String foundPath   = found[0];
            String matchedFile = found[1];
            boolean isAdjusted = !foundPath.equals(customPath);

            stepCallback.accept(4, "Reading: " + matchedFile
                    + (isAdjusted ? "  (found in: " + foundPath + ")" : ""));

            String fullPath = foundPath.endsWith("/")
                    ? foundPath + matchedFile
                    : foundPath + "/" + matchedFile;
            String content = ssh.executeCommand("cat " + q(fullPath));

            if (content == null || content.isBlank())
                return SearchResult.notFound("File found but content is empty: " + matchedFile);
            if (XmlFormatter.isBinaryContent(content))
                return SearchResult.corrupted("Method 4 (SSH)",
                        "File '" + matchedFile + "' contains binary/corrupted data.");

            stepCallback.accept(4,
                    "Retrieved: " + matchedFile + " (" + content.length() + " chars)"
                            + (isAdjusted ? "  from " + foundPath : ""));
            return SearchResult.found(erxMsgId, content, "Method 4 (SSH — " + matchedFile + ")");
        }
    }

    /**
     * Tries the constructed path first, then +1…+{@value #DAY_OFFSETS} day offsets,
     * then -1…-{@value #DAY_OFFSETS} day offsets (same strategy as Method 3).
     *
     * @return {@code String[]{path, filename}} if found, {@code null} otherwise
     */
    private String[] findFileWithDayFallback(SshService ssh, String erxMsgId,
                                             String customPath,
                                             BiConsumer<Integer, String> stepCallback) throws Exception {
        int[] deltas = buildDeltaSequence(DAY_OFFSETS);

        for (int delta : deltas) {
            String searchPath = (delta == 0) ? customPath : adjustPathDay(customPath, delta);
            if (searchPath == null) continue;

            if (delta != 0) {
                stepCallback.accept(4,
                        "Not found — trying " + (delta > 0 ? "+" : "") + delta
                                + " day: " + searchPath);
            }

            String findCmd = "cd " + q(searchPath)
                    + " && grep -il " + q(erxMsgId) + " * 2>/dev/null | head -1";
            try {
                String matched = ssh.executeCommand(findCmd).trim();
                if (!matched.isEmpty()) {
                    log.info("Method4 SSH: found '{}' in path '{}'", matched, searchPath);
                    return new String[]{searchPath, matched};
                }
            } catch (Exception e) {
                log.debug("SSH search error for path '{}': {}", searchPath, e.getMessage());
            }
        }
        return null;
    }

    /** Builds the search delta sequence: [0, +1, +2…+n, -1, -2…-n]. */
    private static int[] buildDeltaSequence(int n) {
        int[] seq = new int[1 + 2 * n];
        seq[0] = 0;
        for (int i = 1; i <= n; i++) {
            seq[i]     =  i;
            seq[n + i] = -i;
        }
        return seq;
    }

    /**
     * Adjusts the <em>day</em> segment of a Vision-style path by {@code delta}.
     * Detects the YYYY/MM/DD pattern; preserves zero-padding (e.g. "05" stays 2 digits).
     * Returns {@code null} if the pattern is absent or the adjusted day would be &lt; 1.
     */
    private static String adjustPathDay(String path, int delta) {
        String[] parts = path.split("/", -1);
        for (int i = 2; i < parts.length; i++) {
            try {
                int year  = Integer.parseInt(parts[i - 2]);
                int month = Integer.parseInt(parts[i - 1]);
                int day   = Integer.parseInt(parts[i]);
                if (year  >= 1900 && year  <= 2100
                        && month >= 1  && month <= 12
                        && day   >= 1  && day   <= 99) {
                    int newDay = day + delta;
                    if (newDay < 1) return null;
                    String[] adjusted = parts.clone();
                    int width = parts[i].length();
                    adjusted[i] = width == 2
                            ? String.format("%02d", newDay)
                            : String.valueOf(newDay);
                    return String.join("/", adjusted);
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // ================================================================
    //  Path builder
    // ================================================================

    /**
     * Replaces the {@code YYYY/MM/DD} date segments in {@code locPath} with the
     * user-supplied date and replaces the last path segment with {@code storeNbr}.
     *
     * <p>Path structure: {@code /hsm/vision/YYYY/MM/DD/40/store_nbr}.  The segment
     * after DD (e.g. "40") is preserved unchanged; only the date segments and the
     * final store-number segment are substituted.</p>
     *
     * <p>Example: {@code /hsm/vision/2023/11/18/40/12345}, date {@code 12/05/2023},
     * store {@code 06789}  →  {@code /hsm/vision/2023/12/05/40/06789}.</p>
     *
     * @param locPath  template path from Vision DB (contains YYYY/MM/DD pattern)
     * @param rxDate   user-supplied date in MM/DD/YYYY format
     * @param storeNbr Store Number to place as the last path segment
     * @return the customised path, or {@code null} if the date pattern was not found
     */
    static String buildCustomPath(String locPath, String rxDate, String storeNbr) {
        String[] dateParts = rxDate.split("/", -1);
        if (dateParts.length != 3) return null;
        String mm   = dateParts[0];
        String dd   = dateParts[1];
        String yyyy = dateParts[2];
        if (mm.length() != 2 || dd.length() != 2 || yyyy.length() != 4) return null;

        String[] parts = locPath.split("/", -1);
        int ddIdx = -1;
        for (int i = 2; i < parts.length; i++) {
            try {
                int year  = Integer.parseInt(parts[i - 2]);
                int month = Integer.parseInt(parts[i - 1]);
                int day   = Integer.parseInt(parts[i]);
                if (year  >= 1900 && year  <= 2100
                        && month >= 1  && month <= 12
                        && day   >= 1  && day   <= 31) {
                    ddIdx = i;
                    break;
                }
            } catch (NumberFormatException ignored) {}
        }
        if (ddIdx < 0) return null;

        String[] result = parts.clone();
        result[ddIdx - 2]        = yyyy;
        result[ddIdx - 1]        = mm;
        result[ddIdx]            = dd;
        result[result.length - 1] = storeNbr;
        return String.join("/", result);
    }

    // ================================================================
    //  DB helpers
    // ================================================================

    private String getAnyImageId(String storeNbr) throws SQLException {
        String sql = "SELECT IMAGE_ID FROM TBF0_RX WHERE STORE_NBR = ? FETCH FIRST 1 ROWS ONLY";
        try (Connection c = db.getIcPlusConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeNbr.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("IMAGE_ID") : null; }
        }
    }

    private String getLocPath(String imageId) throws SQLException {
        String sql = "SELECT LOC_PATH_OR_ID FROM TCT0_RX_IMAGE WHERE IMAGE_ID = ?";
        try (Connection c = db.getVisionConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imageId.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("LOC_PATH_OR_ID") : null; }
        }
    }

    private String getErxMsgId(String rxNbr, String storeNbr) throws SQLException {
        String sql = "SELECT EPBR_ERX_MSG_ID FROM TBF0_ERX_MSG_MAPPING WHERE STORE_NBR = ? AND RX_NBR = ?";
        try (Connection c = db.getIcPlusConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeNbr.trim()); ps.setString(2, rxNbr.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("EPBR_ERX_MSG_ID") : null; }
        }
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

