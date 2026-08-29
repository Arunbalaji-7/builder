package com.walgreens.rawxmldatapuller.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Sends email by calling the remote mail API endpoint using form-urlencoded POST. */
public final class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final int TIMEOUT_MS = 20_000;

    private MailService() {}

    /**
     * Sends email by posting required fields to an API endpoint.
     */
    public static void send(String apiUrl,
                            String from, String to,
                            String subject, String body) throws Exception {

        log.info("Calling mail API endpoint {} for recipient {}", apiUrl, to);

        String formBody =
                encode("mail_from", from) + "&"
                + encode("mail_to", to) + "&"
                + encode("subject", subject) + "&"
                + encode("body", body);

        byte[] payload = formBody.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = readBody(conn.getErrorStream());
                throw new Exception("Mail API request failed with HTTP " + status
                        + (errorBody.isEmpty() ? "" : " - " + errorBody));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }

        log.info("Email sent via mail API to {}", to);
    }

    private static String encode(String key, String value) throws Exception {
        String safeValue = value == null ? "" : value;
        return URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(safeValue, "UTF-8");
    }

    private static String readBody(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
}
