package org.twofold.app;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class Api {
    public static class Failure extends IOException {
        public final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    public static String normalize(String value) throws Exception {
        URI uri = new URI(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) throw new IOException("Use a complete HTTPS server address.");
        String result = uri.toASCIIString();
        while (result.endsWith("/")) result = result.substring(0, result.length()-1);
        return result;
    }
    public static HttpURLConnection connection(String base, String path, String authHeader, String credential) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(normalize(base) + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-store");
        c.setConnectTimeout(20000); c.setReadTimeout(45000);
        c.setRequestProperty("Accept", "application/json");
        if (credential != null && !credential.isEmpty()) c.setRequestProperty(authHeader, credential);
        return c;
    }
    public static HttpURLConnection relay(JSONObject config, String path) throws Exception {
        return connection(config.getString("server"), path, "Authorization", "Bearer " + config.getString("token"));
    }
    public static byte[] read(InputStream in, int max) throws IOException {
        try (InputStream source = in; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > max) throw new IOException("Server response is too large.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    public static byte[] result(HttpURLConnection c, int max) throws Exception {
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            String message = "Server returned HTTP " + status + ".";
            if (status == 401 || status == 403) message = "Access denied (HTTP " + status + "). Check this phone’s pairing.";
            if (status == 409) message = "Setup conflict. Check your invitation or retry shortly.";
            if (status >= 300 && status < 400) message = "Use the final HTTPS address; redirects are disabled to protect credentials.";
            throw new Failure(status, message);
        }
        return read(c.getInputStream(), max);
    }
    public static String json(HttpURLConnection c, String method, JSONObject body) throws Exception {
        try {
            c.setRequestMethod(method);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            return new String(result(c, 1024*1024), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
    public static JSONObject get(JSONObject config, String path) throws Exception {
        return new JSONObject(json(relay(config, path), "GET", null));
    }
    public static byte[] media(JSONObject config, String id, String part) throws Exception {
        HttpURLConnection c = relay(config, "/v1/media/" + UUID.fromString(id) + "/" + part);
        try { return result(c, 17*1024*1024); } finally { c.disconnect(); }
    }
    public static final class Part {
        final String name, filename, type;
        final byte[] bytes; final File file;
        public Part(String name, String filename, String type, byte[] bytes) {
            this.name=name; this.filename=filename; this.type=type; this.bytes=bytes; this.file=null;
        }
        public Part(String name, File file) { this.name=name; this.filename=name; this.type="application/octet-stream"; this.file=file; this.bytes=null; }
    }
    public static String multipart(HttpURLConnection c, List<Part> parts) throws Exception {
        String boundary = "Twofold" + UUID.randomUUID().toString().replace("-", "");
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setReadTimeout(120000);
            c.setChunkedStreamingMode(65536);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
                for (Part p : parts) {
                    String disposition = "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+p.name+"\"";
                    if (p.filename != null) disposition += "; filename=\""+p.filename+"\"";
                    out.write((disposition+"\r\nContent-Type: "+p.type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    if (p.file == null) out.write(p.bytes);
                    else Files.copy(p.file.toPath(), out);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            return new String(result(c, 1024*1024), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
    public static Part field(String name, String value) { return new Part(name, null, "text/plain", value.getBytes(StandardCharsets.UTF_8)); }
}
