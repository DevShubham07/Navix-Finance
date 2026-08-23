package com.navix.verification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.exception.VerificationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the offline bureau demo fixture ({@code navix.bureau.fixture} / {@code NAVIX_BUREAU_FIXTURE}),
 * e.g. {@code classpath:samplepan.json}. Shared by every bureau client that honours the property —
 * {@code SignzyExperianClient} and {@code FintrixCrifClient} — so local dev, CI and the demo seed get a
 * bureau result offline no matter which provider is currently primary. Accepts a {@code classpath:}
 * prefix, an absolute/relative file path, or a bare classpath resource name.
 */
public final class BureauFixtureLoader {

    private BureauFixtureLoader() {
    }

    public static JsonNode load(ObjectMapper objectMapper, String fixturePath) {
        String p = fixturePath.trim();
        try {
            if (p.startsWith("classpath:")) {
                return readClasspath(objectMapper, p.substring("classpath:".length()));
            }
            File f = new File(p);
            if (f.isFile()) {
                return objectMapper.readTree(f);
            }
            JsonNode cp = readClasspathOrNull(objectMapper, p);
            if (cp != null) {
                return cp;
            }
            throw new VerificationException("Bureau fixture not found: " + p);
        } catch (IOException e) {
            throw new VerificationException("Failed to read bureau fixture " + p, e);
        }
    }

    private static JsonNode readClasspath(ObjectMapper objectMapper, String name) throws IOException {
        JsonNode n = readClasspathOrNull(objectMapper, name);
        if (n == null) {
            throw new VerificationException("Bureau fixture not on classpath: " + name);
        }
        return n;
    }

    private static JsonNode readClasspathOrNull(ObjectMapper objectMapper, String name) throws IOException {
        String resource = name.startsWith("/") ? name : "/" + name;
        try (InputStream in = BureauFixtureLoader.class.getResourceAsStream(resource)) {
            return in == null ? null : objectMapper.readTree(in);
        }
    }
}
