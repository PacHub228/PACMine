package com.voxel;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Verifies a launcher login token against the PACMine auth backend. */
public class AuthClient {
    public static final String BACKEND = "http://185.218.137.116";

    /** Returns {name, premium("true"/"false")} for a valid token, or null. */
    public static String[] verify(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            URL url = new URL(BACKEND + "/api/me?token=" + URLEncoder.encode(token, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "PACMine");
            if (c.getResponseCode() != 200) return null;
            String body;
            try (InputStream in = c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // crude JSON: {"name":"X","premium":true}
            java.util.regex.Matcher mn = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            boolean premium = body.contains("\"premium\":true") || body.contains("\"premium\": true");
            String name = mn.find() ? mn.group(1) : null;
            if (name == null || name.isEmpty()) return null;
            return new String[]{name, String.valueOf(premium)};
        } catch (Exception e) {
            return null;
        }
    }
}
