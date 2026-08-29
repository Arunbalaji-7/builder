package com.walgreens.rawxmldatapuller.controller;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.service.Method1Service;
import com.walgreens.rawxmldatapuller.service.Method2Service;
import com.walgreens.rawxmldatapuller.service.Method3Service;
import com.walgreens.rawxmldatapuller.service.Method4Service;
import com.walgreens.rawxmldatapuller.service.SshCredentialCache;
import com.walgreens.rawxmldatapuller.ui.AppDialogs;
import com.walgreens.rawxmldatapuller.ui.MethodBlock;
import com.walgreens.rawxmldatapuller.ui.TerminalLine;
import com.walgreens.rawxmldatapuller.ui.TerminalPanel;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.SessionContext;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Encapsulates all three retrieval-method execution flows, keeping the FXML
 * controller thin (SRP — controller handles view; this class handles logic).
 *
 * <p>Has no dependency on any JavaFX UI node — only {@link TerminalPanel}
 * (terminal accordion), the three service classes, and a {@code statusUpdater}
 * callback for pushing status-bar text back to the controller.</p>
 *
 * <h3>Public entry points</h3>
 * <ul>
 *   <li>{@link #runErxMsgIdFlow} — MSG ID → Method 1 → auto-resolve → Method 3</li>
 *   <li>{@link #runRxNbrFlow}    — Rx/Store → Method 2 → Method 3</li>
 * </ul>
 */
public class SearchFlowHandler {

    @FunctionalInterface
    private interface ServiceCall {
        SearchResult invoke(BiConsumer<Integer, String> callback) throws Exception;
    }

    private final TerminalPanel       terminal;
    private final Method1Service      method1;
    private final Method2Service      method2;
    private final Method3Service      method3;
    private final Method4Service      method4;
    private final Consumer<String>    statusUpdater;
    private final SshCredentialCache  credentialCache;
    private final SshCredentialCache  businessCredentialCache;

    public SearchFlowHandler(TerminalPanel terminal,
                             Method1Service method1,
                             Method2Service method2,
                             Method3Service method3,
                             Method4Service method4,
                             Consumer<String> statusUpdater,
                             SshCredentialCache credentialCache,
                             SshCredentialCache businessCredentialCache) {
        this.terminal                 = terminal;
        this.method1                  = method1;
        this.method2                  = method2;
        this.method3                  = method3;
        this.method4                  = method4;
        this.statusUpdater            = statusUpdater;
        this.credentialCache          = credentialCache;
        this.businessCredentialCache  = businessCredentialCache;
    }

    /**
     * Returns SSH credentials: BUSINESS and ADMIN users get pre-configured credentials
     * from app_config (cached 5 min); USER role is prompted via dialog (cached 10 min).
     * Returns {@code null} if credentials are unavailable or the user cancels.
     */
    private String[] resolveCredentials() throws InterruptedException {
        User user = SessionContext.getCurrentUser();
        if (user != null && (user.isBusiness() || user.isAdmin())) {
            if (businessCredentialCache.isCached()) return businessCredentialCache.use();
            String sshUser = ConfigLoader.getInstance().getSshBusinessUsername();
            String sshPass = ConfigLoader.getInstance().getSshBusinessPassword();
            if (sshUser == null || sshUser.isBlank() || sshPass == null || sshPass.isBlank())
                return null;
            businessCredentialCache.store(sshUser, sshPass);
            return businessCredentialCache.use();
        }
        return AppDialogs.askSshCredentials(credentialCache);
    }

    // ================================================================
    //  Public entry points
    // ================================================================

    public SearchResult runErxMsgIdFlow(String erxMsgId) throws Exception {
        MethodBlock m1 = terminal.getBlock1();
        MethodBlock m2 = terminal.getBlock2();
        MethodBlock m3 = terminal.getBlock3();

        m1.setHeaderRunning("eRx Direct DB Lookup");
        TerminalLine s1 = m1.addRunningStep("Querying eRx DB — SST_MSG_ID = " + erxMsgId);
        long t0 = System.currentTimeMillis();

        SearchResult r1 = null;
        try {
            r1 = method1.execute(erxMsgId);
        } catch (Exception ex) {
            s1.fail(ex.getMessage()); m1.setHeaderFailed();
        }

        if (r1 != null && r1.isFound()) {
            s1.succeed("RAW_XML_DOC found (" + r1.getRawXmlDoc().length() + " chars) in "
                    + (System.currentTimeMillis() - t0) + "ms");
            m1.setHeaderSuccess();
            m2.setHeaderSkipped("not applicable for this input");
            m3.setHeaderSkipped("not applicable — data found via Method 1");
            terminal.getBlock4().setHeaderSkipped("not applicable — data found via Method 1");
            return r1;
        }

        if (r1 != null) { s1.fail(r1.getErrorMessage()); m1.setHeaderFailed(); }
        m2.setHeaderSkipped("not applicable for this input");
        return runMethod3FromMsgId(erxMsgId);
    }

    public SearchResult runRxNbrFlow(String rxNbr, String storeNbr) throws Exception {
        MethodBlock m1 = terminal.getBlock1();
        MethodBlock m2 = terminal.getBlock2();

        m1.setHeaderSkipped("not applicable — input is Rx Nbr/Store");
        m2.setHeaderRunning("IC+ DB Lookup");

        TerminalLine s2a = m2.addRunningStep(
                "Step 1 — IC+ DB: TBF0_ERX_MSG_MAPPING (Store=" + storeNbr + ", Rx=" + rxNbr + ")");

        String msgId = null;
        try {
            msgId = method2.resolveMsgIdOnly(rxNbr, storeNbr);
        } catch (Exception ex) {
            s2a.fail(ex.getMessage()); m2.setHeaderFailed();
            return runMethod3Flow(rxNbr, storeNbr, null);
        }

        if (msgId == null) {
            s2a.fail("No eRx mapping found for Rx=" + rxNbr + ", Store=" + storeNbr);
            m2.setHeaderFailed();
            return runMethod3Flow(rxNbr, storeNbr, null);
        }

        s2a.succeed("EPBR_ERX_MSG_ID = " + msgId);
        TerminalLine s2b = m2.addRunningStep(
                "Step 2 — IC+ DB: ERX_RAW_MSG_XML_ARCHIVE (MSG_ID=" + msgId + ")");
        long t0 = System.currentTimeMillis();

        SearchResult r2 = null;
        try {
            r2 = method2.execute(rxNbr, storeNbr);
        } catch (Exception ex) {
            s2b.fail(ex.getMessage()); m2.setHeaderFailed();
            return runMethod3Flow(rxNbr, storeNbr, msgId);
        }

        if (r2 != null && r2.isFound()) {
            s2b.succeed("RAW_XML_DOC found (" + r2.getRawXmlDoc().length() + " chars) in "
                    + (System.currentTimeMillis() - t0) + "ms");
            m2.setHeaderSuccess();
            terminal.getBlock3().setHeaderSkipped("not applicable — data found via Method 2");
            terminal.getBlock4().setHeaderSkipped("not applicable — data found via Method 2");
            return r2;
        }

        s2b.fail(r2 != null ? r2.getErrorMessage() : "Unknown error");
        m2.setHeaderFailed();
        return runMethod3Flow(rxNbr, storeNbr, msgId);
    }

    // ================================================================
    //  Method 3 entry points
    // ================================================================

    private SearchResult runMethod3FromMsgId(String erxMsgId) throws Exception {
        MethodBlock m3 = terminal.getBlock3();
        MethodBlock m4 = terminal.getBlock4();
        statusUpdater.accept("Attempting Method 3 — auto-resolving Rx/Store from IC+ DB...");
        m3.setHeaderRunning("Vision DB + SSH grep");

        TerminalLine s0 = m3.addRunningStep(
                "Step 1 — IC+ DB: TBF0_ERX_MSG_MAPPING  →  Rx/Store for " + erxMsgId);

        String rxNbr = null, storeNbr = null;
        boolean alreadyFailed = false;
        String[] resolved = null;
        try {
            resolved = method2.resolveRxStoreFromMsgId(erxMsgId);
        } catch (Exception ex) {
            s0.fail("IC+ DB error: " + ex.getMessage());
            alreadyFailed = true;
        }

        if (resolved != null) {
            s0.succeed("Rx=" + resolved[0] + ", Store=" + resolved[1]);
            rxNbr = resolved[0]; storeNbr = resolved[1];
        } else {
            if (!alreadyFailed) s0.fail("No Rx/Store mapping found in IC+ DB — manual input required");
            String[] manual = AppDialogs.askRxAndStoreNbr();
            if (manual == null) {
                m3.setHeaderSkipped("cancelled by user");
                m4.setHeaderSkipped("not applicable — Rx/Store details not provided");
                return SearchResult.notFound("Method 3 skipped — Rx/Store details not provided.");
            }
            rxNbr = manual[0]; storeNbr = manual[1];
        }

        String[] creds = resolveCredentials();
        if (creds == null) {
            m3.setHeaderSkipped("cancelled — SSH credentials not provided");
            m4.setHeaderSkipped("not applicable — SSH credentials not provided");
            return SearchResult.notFound("Method 3 cancelled — SSH credentials not provided.");
        }

        SearchResult m3Result = executeMethod3StepsFromMsgId(rxNbr, storeNbr, erxMsgId, creds, m3);
        if (m3Result != null && m3Result.isFound()) {
            m4.setHeaderSkipped(m3Result.isCorrupted()
                    ? "not applicable — corrupted data detected via Method 3"
                    : "not applicable — data found via Method 3");
            return m3Result;
        }
        return runMethod4Flow(rxNbr, storeNbr, erxMsgId);
    }

    private SearchResult runMethod3Flow(String rxNbr, String storeNbr, String erxMsgId) throws Exception {
        MethodBlock m3 = terminal.getBlock3();
        MethodBlock m4 = terminal.getBlock4();
        statusUpdater.accept("Attempting Method 3 (Vision DB + SSH)...");

        String[] creds = resolveCredentials();
        if (creds == null) {
            m3.setHeaderSkipped("cancelled — SSH credentials not provided");
            m4.setHeaderSkipped("not applicable — SSH credentials not provided");
            return SearchResult.notFound("Method 3 cancelled — SSH credentials not provided.");
        }
        m3.setHeaderRunning("Vision DB + SSH grep");

        SearchResult m3Result = executeMethod3Steps(rxNbr, storeNbr, creds, m3);
        if (m3Result != null && m3Result.isFound()) {
            m4.setHeaderSkipped(m3Result.isCorrupted()
                    ? "not applicable — corrupted data detected via Method 3"
                    : "not applicable — data found via Method 3");
            return m3Result;
        }
        return runMethod4Flow(rxNbr, storeNbr, erxMsgId);
    }

    // ================================================================
    //  Method 4 entry point
    // ================================================================

    private SearchResult runMethod4Flow(String rxNbr, String storeNbr, String erxMsgId) throws Exception {
        MethodBlock m4 = terminal.getBlock4();
        statusUpdater.accept("Attempting Method 4 — date-based path construction...");

        String rxDate = AppDialogs.askRxDate();
        if (rxDate == null) {
            m4.setHeaderSkipped("cancelled — Rx date not provided");
            return SearchResult.notFound("Method 4 cancelled — Rx date not provided.");
        }

        String[] creds = resolveCredentials();
        if (creds == null) {
            m4.setHeaderSkipped("cancelled — SSH credentials not provided");
            return SearchResult.notFound("Method 4 cancelled — SSH credentials not provided.");
        }

        m4.setHeaderRunning("Date-based path + SSH grep");
        TerminalLine[] steps = {
                m4.addRunningStep("Step 1 — IC+ DB: TBF0_RX (any IMAGE_ID for store " + storeNbr + ")"),
                m4.addRunningStep("Step 2 — Vision DB: TCT0_RX_IMAGE (LOC_PATH_OR_ID template)"),
                m4.addRunningStep("Step 3 — Build custom path (date=" + rxDate + ", store=" + storeNbr + ")"),
                m4.addRunningStep("Step 4 — SSH grep on " + ConfigLoader.getInstance().getSshServer())
        };

        final String finalRxDate = rxDate;
        final String finalErxMsgId = erxMsgId;
        return executeSteps(steps, m4,
                cb -> method4.execute(rxNbr, storeNbr, finalErxMsgId, finalRxDate, creds[0], creds[1], cb));
    }

    // ================================================================
    //  Step executors
    // ================================================================

    private SearchResult executeMethod3StepsFromMsgId(String rxNbr, String storeNbr,
                                                      String erxMsgId, String[] creds,
                                                      MethodBlock m3) throws Exception {
        TerminalLine[] steps = {
                m3.addRunningStep("Step 2 — IC+ DB: TBF0_RX (IMAGE_ID)"),
                m3.addRunningStep("Step 3 — Vision DB: TCT0_RX_IMAGE (LOC_PATH_OR_ID)"),
                m3.addRunningStep("Step 4 — SSH grep on pvisapp1")
        };
        return executeSteps(steps, m3,
                cb -> method3.executeWithKnownMsgId(rxNbr, storeNbr, erxMsgId, creds[0], creds[1], cb));
    }

    private SearchResult executeMethod3Steps(String rxNbr, String storeNbr,
                                             String[] creds, MethodBlock m3) throws Exception {
        TerminalLine[] steps = {
                m3.addRunningStep("Step 1 — IC+ DB: TBF0_RX (IMAGE_ID)"),
                m3.addRunningStep("Step 2 — IC+ DB: TBF0_ERX_MSG_MAPPING (EPBR_ERX_MSG_ID)"),
                m3.addRunningStep("Step 3 — Vision DB: TCT0_RX_IMAGE (LOC_PATH_OR_ID)"),
                m3.addRunningStep("Step 4 — SSH grep on pvisapp1")
        };
        return executeSteps(steps, m3,
                cb -> method3.execute(rxNbr, storeNbr, creds[0], creds[1], cb));
    }

    /**
     * Shared step-progression driver: wires the service callback to terminal step
     * lines, advances them as each step completes, and finalises the block header.
     */
    private SearchResult executeSteps(TerminalLine[] steps, MethodBlock m3,
                                      ServiceCall serviceCall) throws Exception {
        int[] lastStep = {0};
        SearchResult result = null;

        try {
            result = serviceCall.invoke((stepIdx, msg) -> {
                if (lastStep[0] > 0 && lastStep[0] < stepIdx)
                    steps[lastStep[0] - 1].succeed("Completed");
                if (stepIdx - 1 < steps.length) {
                    steps[stepIdx - 1].updateMessage(msg);
                    lastStep[0] = stepIdx;
                }
            });
        } catch (Exception ex) {
            int fi = Math.max(lastStep[0] - 1, 0);
            steps[fi].fail(ex.getMessage());
            for (int i = fi + 1; i < steps.length; i++) steps[i].skip("Not reached");
            m3.setHeaderFailed();
            return SearchResult.error(ex.getMessage());
        }

        if (result != null && result.isFound() && !result.isCorrupted()) {
            for (TerminalLine step : steps) if (!step.isSucceeded()) step.succeed("Completed");
            m3.setHeaderSuccess();
        } else {
            int fi = Math.max(lastStep[0] - 1, 0);
            steps[fi].fail((result != null && result.isCorrupted()) ? "Binary/corrupted data"
                    : (result != null ? result.getErrorMessage() : "Unknown error"));
            for (int i = fi + 1; i < steps.length; i++) steps[i].skip("Not reached");
            m3.setHeaderFailed();
        }
        return result != null ? result : SearchResult.error("No result produced");
    }
}

