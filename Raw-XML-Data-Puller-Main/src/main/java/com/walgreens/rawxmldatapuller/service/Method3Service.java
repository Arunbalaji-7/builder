package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.XmlFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.function.BiConsumer;

/**
 * Service that implements Method 3 of the RAW XML retrieval workflow.
 *
 * <p>Two execution variants are provided:</p>
 * <ul>
 *   <li>{@link #execute} — full 4-step path (Method 2 failure).</li>
 *   <li>{@link #executeWithKnownMsgId} — 3-step path (Method 1 failure, MSG ID known).</li>
 * </ul>
 *
 * <h3>Path day-offset fallback</h3>
 * <p>Vision DB paths follow the pattern
 * {@code <base>/YYYY/MM/DD/<store>/<rx>}.  When the exact directory contains
 * no matching file the SSH step automatically retries the adjacent five days
 * forward (+1 … +5) and then five days backward (−1 … −5) before giving up.
 * Callers see each attempt in the terminal step message via {@code stepCallback}.
 * </p>
 */
public class Method3Service {

    private static final Logger log          = LoggerFactory.getLogger(Method3Service.class);
    private static final int    DAY_OFFSETS  = 5;

    private final DatabaseService db;

    public Method3Service(DatabaseService db) { this.db = db; }

    // ================================================================
    //  Public execution paths
    // ================================================================

    public SearchResult execute(String rxNbr, String storeNbr,
                                String sshUser, String sshPassword,
                                BiConsumer<Integer, String> stepCallback) throws Exception {
        stepCallback.accept(1, "Querying IC+ DB: TBF0_RX...");
        String imageId = getImageId(rxNbr, storeNbr);
        if (imageId == null)
            return SearchResult.notFound("No record in TBF0_RX for Rx: " + rxNbr + ", Store: " + storeNbr);
        log.info("Method3 Step1: IMAGE_ID={}", imageId);
        stepCallback.accept(1, "IMAGE_ID = " + imageId);

        stepCallback.accept(2, "Querying IC+ DB: TBF0_ERX_MSG_MAPPING...");
        String erxMsgId = getErxMsgId(rxNbr, storeNbr);
        if (erxMsgId == null)
            return SearchResult.notFound("No eRx mapping in TBF0_ERX_MSG_MAPPING for Rx: " + rxNbr + ", Store: " + storeNbr);
        log.info("Method3 Step2: EPBR_ERX_MSG_ID={}", erxMsgId);
        stepCallback.accept(2, "EPBR_ERX_MSG_ID = " + erxMsgId);

        stepCallback.accept(3, "Querying Vision DB: TCT0_RX_IMAGE...");
        String locPath = getLocPath(imageId);
        if (locPath == null)
            return SearchResult.notFound("No image path in Vision DB for IMAGE_ID: " + imageId);
        log.info("Method3 Step3: LOC_PATH_OR_ID={}", locPath);
        stepCallback.accept(3, "LOC_PATH_OR_ID = " + locPath);

        stepCallback.accept(4, "Connecting to SSH: " + ConfigLoader.getInstance().getSshServer() + "...");
        return runSshGrep(erxMsgId, locPath, sshUser, sshPassword, stepCallback, 4);
    }

    public SearchResult executeWithKnownMsgId(String rxNbr, String storeNbr,
                                              String erxMsgId,
                                              String sshUser, String sshPassword,
                                              BiConsumer<Integer, String> stepCallback) throws Exception {
        stepCallback.accept(1, "Querying IC+ DB: TBF0_RX...");
        String imageId = getImageId(rxNbr, storeNbr);
        if (imageId == null)
            return SearchResult.notFound("No record in TBF0_RX for Rx: " + rxNbr + ", Store: " + storeNbr);
        log.info("Method3 (3-step) Step1: IMAGE_ID={}", imageId);
        stepCallback.accept(1, "IMAGE_ID = " + imageId);

        stepCallback.accept(2, "Querying Vision DB: TCT0_RX_IMAGE...");
        String locPath = getLocPath(imageId);
        if (locPath == null)
            return SearchResult.notFound("No image path in Vision DB for IMAGE_ID: " + imageId);
        log.info("Method3 (3-step) Step2: LOC_PATH_OR_ID={}", locPath);
        stepCallback.accept(2, "LOC_PATH_OR_ID = " + locPath);

        stepCallback.accept(3, "Connecting to SSH: " + ConfigLoader.getInstance().getSshServer() + "...");
        return runSshGrep(erxMsgId, locPath, sshUser, sshPassword, stepCallback, 3);
    }

    // ================================================================
    //  SSH — main grep with day-offset fallback
    // ================================================================

    private SearchResult runSshGrep(String erxMsgId, String locPath,
                                    String sshUser, String sshPassword,
                                    BiConsumer<Integer, String> stepCallback,
                                    int sshStepIdx) throws Exception {
        try (SshService ssh = new SshService()) {
            ssh.connect(sshUser, sshPassword);
            stepCallback.accept(sshStepIdx, "Connected. Searching in: " + locPath);

            String[] found = findFileWithDayFallback(ssh, erxMsgId, locPath, stepCallback, sshStepIdx);

            if (found == null) {
                return SearchResult.notFound(
                        "No file found for MSG ID '" + erxMsgId + "' in: " + locPath
                                + "  (checked ±" + DAY_OFFSETS + " day offsets)");
            }

            String foundPath  = found[0];
            String matchedFile = found[1];
            boolean isAdjusted = !foundPath.equals(locPath);

            stepCallback.accept(sshStepIdx, "Reading: " + matchedFile
                    + (isAdjusted ? "  (found in: " + foundPath + ")" : ""));

            String fullPath = foundPath.endsWith("/")
                    ? foundPath + matchedFile
                    : foundPath + "/" + matchedFile;
            String content = ssh.executeCommand("cat " + q(fullPath));

            if (content == null || content.isBlank())
                return SearchResult.notFound("File found but content is empty: " + matchedFile);
            if (XmlFormatter.isBinaryContent(content))
                return SearchResult.corrupted("Method 3 (SSH)",
                        "File '" + matchedFile + "' contains binary/corrupted data.");

            stepCallback.accept(sshStepIdx,
                    "Retrieved: " + matchedFile + " (" + content.length() + " chars)"
                            + (isAdjusted ? "  from " + foundPath : ""));
            return SearchResult.found(erxMsgId, content, "Method 3 (SSH — " + matchedFile + ")");
        }
    }

    /**
     * Tries the original path first, then +1…+{@value #DAY_OFFSETS} day offsets,
     * then -1…-{@value #DAY_OFFSETS} day offsets.
     *
     * <p>Path format assumed: {@code <base>/YYYY/MM/DD/<…>} — the YYYY/MM/DD
     * pattern is auto-detected so the method is robust to varying base depths.</p>
     *
     * @return {@code String[]{path, filename}} if found, {@code null} if not found
     *         in any of the searched paths
     */
    private String[] findFileWithDayFallback(SshService ssh, String erxMsgId,
                                             String locPath,
                                             BiConsumer<Integer, String> stepCallback,
                                             int sshStepIdx) throws Exception {
        // Search order: 0 (original), +1..+5, then -1..-5
        int[] deltas = buildDeltaSequence(DAY_OFFSETS);

        for (int delta : deltas) {
            String searchPath = (delta == 0) ? locPath : adjustPathDay(locPath, delta);
            if (searchPath == null) continue;

            if (delta != 0) {
                stepCallback.accept(sshStepIdx,
                        "Not found — trying " + (delta > 0 ? "+" : "") + delta
                                + " day: " + searchPath);
            }

            String findCmd = "cd " + q(searchPath)
                    + " && grep -il " + q(erxMsgId) + " * 2>/dev/null | head -1";
            try {
                String matched = ssh.executeCommand(findCmd).trim();
                if (!matched.isEmpty()) {
                    log.info("Method3 SSH: found '{}' in path '{}'", matched, searchPath);
                    return new String[]{searchPath, matched};
                }
            } catch (Exception e) {
                log.debug("SSH search error for path '{}': {}", searchPath, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Builds the search delta sequence: [0, +1, +2…+n, -1, -2…-n].
     */
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
     * Adjusts the <em>day</em> segment of a Vision DB path by {@code delta}.
     *
     * <p>Detects the {@code YYYY/MM/DD} pattern anywhere in the path — robust to
     * variable-depth base directories.  The day value is simply offset as an
     * integer; no calendar month-rollover is performed (directories are assumed
     * to exist only for days that were actually written).</p>
     *
     * @return the adjusted path string, or {@code null} if the date pattern was
     *         not found or the adjusted day would be &lt; 1
     */
    private static String adjustPathDay(String locPath, int delta) {
        String[] parts = locPath.split("/", -1);
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
                    // preserve original zero-padding width (e.g. "01" stays 2 digits)
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
    //  DB helpers
    // ================================================================

    private String getImageId(String rxNbr, String storeNbr) throws SQLException {
        String sql = "SELECT IMAGE_ID FROM TBF0_RX WHERE STORE_NBR = ? AND RX_NBR = ?";
        try (Connection c = db.getIcPlusConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeNbr.trim()); ps.setString(2, rxNbr.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("IMAGE_ID") : null; }
        }
    }

    private String getErxMsgId(String rxNbr, String storeNbr) throws SQLException {
        String sql = "SELECT EPBR_ERX_MSG_ID FROM TBF0_ERX_MSG_MAPPING WHERE STORE_NBR = ? AND RX_NBR = ?";
        try (Connection c = db.getIcPlusConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeNbr.trim()); ps.setString(2, rxNbr.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("EPBR_ERX_MSG_ID") : null; }
        }
    }

    private String getLocPath(String imageId) throws SQLException {
        String sql = "SELECT LOC_PATH_OR_ID FROM TCT0_RX_IMAGE WHERE IMAGE_ID = ?";
        try (Connection c = db.getVisionConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imageId.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("LOC_PATH_OR_ID") : null; }
        }
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

