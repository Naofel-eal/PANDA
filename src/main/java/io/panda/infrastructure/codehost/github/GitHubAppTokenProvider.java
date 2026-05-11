package io.panda.infrastructure.codehost.github;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jboss.logging.Logger;

public class GitHubAppTokenProvider implements GitHubTokenProvider {

    private static final Logger LOG = Logger.getLogger(GitHubAppTokenProvider.class);

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Duration JWT_VALIDITY = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern PEM_STRIP = Pattern.compile("-----[A-Z ]+-----");

    private final String appId;
    private final RSAPrivateKey privateKey;
    private final String installationId;
    private final String apiUrl;
    private final HttpClient client;
    private final ObjectMapper objectMapper;

    private String cachedToken;
    private Instant tokenExpiresAt;

    GitHubAppTokenProvider(String appId, String privateKeyPem, String installationId, String apiUrl) {
        this.appId = appId;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.installationId = installationId;
        this.apiUrl = apiUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_REFRESH_MARGIN))) {
            LOG.debugf("GitHub App token cached, expires at %s", tokenExpiresAt);
            return cachedToken;
        }
        LOG.infof("Refreshing GitHub App token (current expires at %s)", tokenExpiresAt);
        refreshInstallationToken();
        return cachedToken;
    }

    private void refreshInstallationToken() {
        try {
            String jwt = generateJwt();
            String url = apiUrl + "/app/installations/" + installationId + "/access_tokens";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(HTTP_TIMEOUT)
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException(
                    "GitHub App token request failed: HTTP " + response.statusCode() + " - " + response.body()
                );
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            cachedToken = (String) body.get("token");
            tokenExpiresAt = Instant.parse((String) body.get("expires_at"));
            LOG.infof("GitHub App installation token refreshed, expires at %s", tokenExpiresAt);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to refresh GitHub App installation token", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while refreshing GitHub App installation token", exception);
        }
    }

    private String generateJwt() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(appId)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plus(JWT_VALIDITY)))
                .build();

            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJwt.sign(new RSASSASigner(privateKey));
            return signedJwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate GitHub App JWT", exception);
        }
    }

    private static RSAPrivateKey parsePrivateKey(String rawPem) {
        String pem = rawPem.replace("\\n", "\n");
        try {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                String base64 = pem
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
                byte[] der = Base64.getDecoder().decode(base64);
                return parsePkcs1(der);
            }

            String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) factory.generatePrivate(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GitHub App private key", e);
        }
    }

    private static RSAPrivateKey parsePkcs1(byte[] der) throws Exception {
        DerParser parser = new DerParser(der);
        parser.readSequence();
        parser.readInteger(); // version
        BigInteger n = parser.readInteger();
        BigInteger e = parser.readInteger();
        BigInteger d = parser.readInteger();
        BigInteger p = parser.readInteger();
        BigInteger q = parser.readInteger();
        BigInteger dp = parser.readInteger();
        BigInteger dq = parser.readInteger();
        BigInteger qi = parser.readInteger();
        RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static class DerParser {
        private final byte[] data;
        private int pos;

        DerParser(byte[] data) {
            this.data = data;
            this.pos = 0;
        }

        void readSequence() {
            int tag = data[pos++] & 0xFF;
            if (tag != 0x30) throw new IllegalArgumentException("Expected SEQUENCE tag 0x30, got 0x" + Integer.toHexString(tag));
            readLength();
        }

        BigInteger readInteger() {
            int tag = data[pos++] & 0xFF;
            if (tag != 0x02) throw new IllegalArgumentException("Expected INTEGER tag 0x02, got 0x" + Integer.toHexString(tag));
            int length = readLength();
            byte[] value = new byte[length];
            System.arraycopy(data, pos, value, 0, length);
            pos += length;
            return new BigInteger(1, value);
        }

        private int readLength() {
            int firstByte = data[pos++] & 0xFF;
            if (firstByte < 0x80) return firstByte;
            int numBytes = firstByte & 0x7F;
            int length = 0;
            for (int i = 0; i < numBytes; i++) {
                length = (length << 8) | (data[pos++] & 0xFF);
            }
            return length;
        }
    }
}
