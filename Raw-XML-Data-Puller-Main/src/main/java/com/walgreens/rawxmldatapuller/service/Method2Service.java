package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Service that implements Method 2 of the RAW XML retrieval workflow.
 * <p>
 * Method 2 performs a two-step lookup entirely within the IC+ Oracle database:
 * </p>
 * <ol>
 *   <li><strong>Step 1</strong> — Query {@code TBF0_ERX_MSG_MAPPING} with the
 *       prescription number and store number to resolve the
 *       {@code EPBR_ERX_MSG_ID}.</li>
 *   <li><strong>Step 2</strong> — Use the resolved message ID to query
 *       {@code ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE} and retrieve the
 *       {@code RAW_XML_DOC} CLOB.</li>
 * </ol>
 * <p>
 * This method is used when the caller has an Rx number and store number but not
 * the eRx message ID.  The step-1 resolution is also exposed separately via
 * {@link #resolveMsgIdOnly(String, String)} so that the UI can report incremental
 * progress during multi-step execution.
 * </p>
 */
public class Method2Service {

    private static final Logger log = LoggerFactory.getLogger(Method2Service.class);
    private final DatabaseService db;

    /**
     * Constructs a {@code Method2Service} using the supplied {@link DatabaseService}
     * for all database connectivity.
     *
     * @param db the shared database service; must not be {@code null}
     */
    public Method2Service(DatabaseService db) { this.db = db; }

    /**
     * Executes the full two-step IC+ lookup and returns the raw XML result.
     * <p>
     * Step 1 resolves the {@code EPBR_ERX_MSG_ID} from {@code TBF0_ERX_MSG_MAPPING}.
     * If no mapping is found, a {@link SearchResult#notFound} is returned immediately.
     * Step 2 fetches the {@code RAW_XML_DOC} from
     * {@code ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE} using the resolved message ID.
     * </p>
     *
     * @param rxNbr    the prescription number used to identify the eRx record;
     *                 leading and trailing whitespace is trimmed before use
     * @param storeNbr the store number associated with the prescription;
     *                 leading and trailing whitespace is trimmed before use
     * @return a {@link SearchResult} containing the raw XML document on success,
     *         or a not-found result with a descriptive message on failure
     * @throws Exception if a database error occurs during either step
     */
    public SearchResult execute(String rxNbr, String storeNbr) throws Exception {
        log.info("Method2: rxNbr={}, storeNbr={}", rxNbr, storeNbr);

        String msgId = resolveMsgIdOnly(rxNbr, storeNbr);
        if (msgId == null) {
            return SearchResult.notFound(
                    "No eRx mapping in IC+ DB for Rx: " + rxNbr + ", Store: " + storeNbr);
        }
        log.info("Method2 Step1: EPBR_ERX_MSG_ID={}", msgId);

        return fetchXml(msgId);
    }

    /**
     * Reverse-lookup: given an eRx message ID, returns the {@code RX_NBR} and
     * {@code STORE_NBR} from {@code TBF0_ERX_MSG_MAPPING} in the IC+ database.
     *
     * <p>Used when Method 3 is entered via a failed Method 1 search and only the
     * MSG ID is available.  Query:
     * {@code SELECT RX_NBR, STORE_NBR FROM TBF0_ERX_MSG_MAPPING WHERE EPBR_ERX_MSG_ID = ?}</p>
     *
     * @param erxMsgId the {@code EPBR_ERX_MSG_ID} to look up
     * @return {@code String[]{rxNbr, storeNbr}} if found, or {@code null} if no row exists
     * @throws SQLException if a database error occurs
     */
    public String[] resolveRxStoreFromMsgId(String erxMsgId) throws SQLException {
        String sql = "SELECT RX_NBR, STORE_NBR FROM TBF0_ERX_MSG_MAPPING "
                + "WHERE EPBR_ERX_MSG_ID = ?";
        try (Connection conn = db.getIcPlusConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, erxMsgId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString("RX_NBR"), rs.getString("STORE_NBR")};
                }
                return null;
            }
        }
    }

    /**
     * Performs Step 1 only: resolves the {@code EPBR_ERX_MSG_ID} from the IC+
     * database's {@code TBF0_ERX_MSG_MAPPING} table for the given Rx and store.
     * <p>
     * This method is called separately by the controller when it needs to display
     * the resolved message ID to the user before proceeding to Step 2, allowing
     * incremental progress feedback in the UI.
     * </p>
     *
     * @param rxNbr    the prescription number; leading and trailing whitespace is trimmed
     * @param storeNbr the store number; leading and trailing whitespace is trimmed
     * @return the {@code EPBR_ERX_MSG_ID} string if a mapping row was found,
     *         or {@code null} if no matching record exists
     * @throws SQLException if a database error occurs during the query
     */
    public String resolveMsgIdOnly(String rxNbr, String storeNbr) throws SQLException {
        String sql = "SELECT EPBR_ERX_MSG_ID FROM TBF0_ERX_MSG_MAPPING "
                + "WHERE STORE_NBR = ? AND RX_NBR = ?";

        try (Connection conn = db.getIcPlusConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, storeNbr.trim());
            ps.setString(2, rxNbr.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("EPBR_ERX_MSG_ID") : null;
            }
        }
    }

    /**
     * Performs Step 2: fetches the {@code RAW_XML_DOC} CLOB from
     * {@code ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE} in the IC+ database using the
     * previously resolved message ID.
     *
     * @param msgId the {@code EPBR_ERX_MSG_ID} / {@code SST_MSG_ID} to look up
     * @return a {@link SearchResult} containing the raw XML on success, or a
     *         not-found result if no matching row or an empty CLOB is found
     * @throws Exception if a database error occurs during the query
     */
    private SearchResult fetchXml(String msgId) throws Exception {
        String sql = "SELECT SST_MSG_ID, RAW_XML_DOC "
                + "FROM ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE "
                + "WHERE SST_MSG_ID = ?";

        try (Connection conn = db.getIcPlusConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, msgId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sstMsgId = rs.getString("SST_MSG_ID");
                    String rawXml   = DatabaseService.clobToString(rs.getClob("RAW_XML_DOC"));

                    if (rawXml == null || rawXml.isBlank()) {
                        return SearchResult.notFound(
                                "RAW_XML_DOC is empty for MSG ID: " + msgId);
                    }
                    log.info("Method2 Step2: XML found, length={}", rawXml.length());
                    return SearchResult.found(sstMsgId, rawXml, "Method 2 (IC+ DB)");
                }
                return SearchResult.notFound("No XML record in IC+ DB for MSG ID: " + msgId);
            }
        }
    }
}

