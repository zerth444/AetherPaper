package com.aetherport.aether;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Minimal built-in HTTP server that hosts the bundled/configured resource pack so
 * players can download it without an external web server.
 */
public final class ResourcePackHost {

    private final Logger logger;
    private HttpServer server;
    private byte[] data;
    private String sha1;
    private int port;

    public ResourcePackHost(Logger logger) {
        this.logger = logger;
    }

    public void start(Path packFile, int requestedPort) throws IOException {
        if (!Files.isRegularFile(packFile)) {
            logger.warning("Resource pack file not found: " + packFile.toAbsolutePath());
            return;
        }
        this.data = Files.readAllBytes(packFile);
        this.sha1 = sha1Hex(this.data);
        this.port = requestedPort;
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", requestedPort), 0);
        this.server.createContext("/aether.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });
        this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AetherResourcePackHost");
            t.setDaemon(true);
            return t;
        }));
        this.server.start();
        logger.info("Resource pack hosted on port " + requestedPort + " (" + data.length + " bytes, sha1=" + sha1 + ")");
    }

    public boolean isRunning() {
        return server != null;
    }

    public String sha1() {
        return sha1;
    }

    public int port() {
        return port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
