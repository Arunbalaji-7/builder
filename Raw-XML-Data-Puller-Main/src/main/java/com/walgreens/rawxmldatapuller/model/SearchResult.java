package com.walgreens.rawxmldatapuller.model;

/**
 * Value object returned by each retrieval method service.
 *
 * <p>Use the static factory methods to construct instances:
 * <ul>
 *   <li>{@link #found} — data was retrieved successfully.</li>
 *   <li>{@link #corrupted} — file found but binary/corrupt content detected.</li>
 *   <li>{@link #notFound} / {@link #error} — data could not be located.</li>
 * </ul>
 */
public class SearchResult {

    private boolean found;
    private String  sstMsgId;
    private String  rawXmlDoc;
    private String  sourceMethod;
    private boolean corrupted;
    private String  errorMessage;

    private SearchResult() {}

    /**
     * Creates a successful result with retrieved XML content.
     *
     * @param sstMsgId     the SST_MSG_ID value from the DB
     * @param rawXmlDoc    the raw (possibly unformatted) XML content
     * @param sourceMethod human-readable label of the method that succeeded
     * @return a found, non-corrupted result
     */
    public static SearchResult found(String sstMsgId, String rawXmlDoc, String sourceMethod) {
        SearchResult r = new SearchResult();
        r.found        = true;
        r.sstMsgId     = sstMsgId;
        r.rawXmlDoc    = rawXmlDoc;
        r.sourceMethod = sourceMethod;
        return r;
    }

    /**
     * Creates a result indicating the file was located but contains
     * binary or otherwise corrupt content that cannot be displayed.
     *
     * @param sourceMethod where the corruption was detected
     * @param message      description of the corruption
     * @return a found-but-corrupted result
     */
    public static SearchResult corrupted(String sourceMethod, String message) {
        SearchResult r  = new SearchResult();
        r.found         = true;
        r.corrupted     = true;
        r.sourceMethod  = sourceMethod;
        r.errorMessage  = message;
        return r;
    }

    /**
     * Creates a not-found result — no record matched the search criteria.
     *
     * @param errorMessage explanation of why the search failed
     * @return a not-found result
     */
    public static SearchResult notFound(String errorMessage) {
        SearchResult r  = new SearchResult();
        r.found         = false;
        r.errorMessage  = errorMessage;
        return r;
    }

    /**
     * Alias for {@link #notFound} used when an exception caused the failure.
     *
     * @param errorMessage the exception message
     * @return a not-found/error result
     */
    public static SearchResult error(String errorMessage) {
        return notFound(errorMessage);
    }

    /** @return {@code true} if the record was located (may still be {@link #isCorrupted()}) */
    public boolean isFound()        { return found; }

    /** @return {@code true} if the content is binary/corrupted and cannot be displayed */
    public boolean isCorrupted()    { return corrupted; }

    /** @return the SST_MSG_ID column value, or {@code null} */
    public String getSstMsgId()     { return sstMsgId; }

    /** @return the raw XML string, or {@code null} if not found */
    public String getRawXmlDoc()    { return rawXmlDoc; }

    /** @return label of the retrieval method (e.g. {@code "Method 1 (eRx DB)"}) */
    public String getSourceMethod() { return sourceMethod; }

    /** @return failure/error message, or {@code null} if the result is a success */
    public String getErrorMessage() { return errorMessage; }
}

