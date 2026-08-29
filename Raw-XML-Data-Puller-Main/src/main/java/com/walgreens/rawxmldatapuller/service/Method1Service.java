package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Service that implements Method 1 of the RAW XML retrieval workflow.
 * <p>
 * Method 1 performs a direct lookup against the eRx Oracle database: given an
 * SST message ID ({@code SST_MSG_ID}), it queries
 * {@code ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE} and returns the associated
 * {@code RAW_XML_DOC} CLOB as a {@link SearchResult}.
 * </p>
 * <p>
 * This is the simplest and fastest retrieval path and should be attempted first
 * when the caller already has the eRx message ID available.
 * </p>
 */
public class Method1Service {

    private static final Logger log = LoggerFactory.getLogger(Method1Service.class);
    private final DatabaseService db;

    /**
     * Constructs a {@code Method1Service} using the supplied {@link DatabaseService}
     * for all database connectivity.
     *
     * @param db the shared database service; must not be {@code null}
     */
    public Method1Service(DatabaseService db) { this.db = db; }

    /**
     * Queries the eRx database for the {@code RAW_XML_DOC} associated with the
     * given {@code SST_MSG_ID} and returns the result.
     * <p>
     * Step 1: Connect to the eRx DB and execute a {@code SELECT} against
     * {@code ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE} using {@code SST_MSG_ID} as the
     * filter criterion.
     * </p>
     * <ul>
     *   <li>If a row is found and the CLOB is non-empty, a {@link SearchResult#found}
     *       result is returned.</li>
     *   <li>If a row is found but the CLOB is {@code null} or blank, a
     *       {@link SearchResult#notFound} result is returned with a descriptive
     *       message.</li>
     *   <li>If no row matches, a {@link SearchResult#notFound} result is returned.</li>
     * </ul>
     *
     * @param erxMsgId the SST message ID to look up; leading and trailing whitespace
     *                 is trimmed before the query is executed
     * @return a {@link SearchResult} describing whether the XML was found and, if so,
     *         containing the raw XML document
     * @throws Exception if a database error occurs during the query
     */
    public SearchResult execute(String erxMsgId) throws Exception {
        log.info("Method1: eRx DB lookup for SST_MSG_ID={}", erxMsgId);

        String sql = "SELECT SST_MSG_ID, RAW_XML_DOC "
                + "FROM ERXOWNER.ERX_RAW_MSG_XML_ARCHIVE "
                + "WHERE SST_MSG_ID = ?";

        try (Connection conn = db.getErxConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, erxMsgId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sstMsgId = rs.getString("SST_MSG_ID");
                    String rawXml   = DatabaseService.clobToString(rs.getClob("RAW_XML_DOC"));

                    if (rawXml == null || rawXml.isBlank()) {
                        return SearchResult.notFound(
                                "Record found but RAW_XML_DOC is empty for MSG ID: " + erxMsgId);
                    }
                    log.info("Method1: found, XML length={}", rawXml.length());
                    return SearchResult.found(sstMsgId, rawXml, "Method 1 (eRx DB)");
                }
                return SearchResult.notFound("No record in eRx DB for MSG ID: " + erxMsgId);
            }
        }
    }
}

