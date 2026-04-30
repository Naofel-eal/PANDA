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
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
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
            return cachedToken;
        }
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

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            String stripped = PEM_STRIP.matcher(pem).replaceAll("").replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) factory.generatePrivate(spec);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse GitHub App private key PEM", exception);
        }
    }
}
