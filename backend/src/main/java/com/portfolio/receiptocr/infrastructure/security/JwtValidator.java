package com.portfolio.receiptocr.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.portfolio.receiptocr.infrastructure.security.exception.JwtValidationException;

import java.util.Date;
import java.util.List;

import jakarta.annotation.PostConstruct;
import java.security.interfaces.RSAPublicKey;

@Component
public class JwtValidator {
    private static final Logger logger = LoggerFactory.getLogger(JwtValidator.class);

    private final JwtConfig jwtConfig;
    private final RestTemplate restTemplate;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private volatile RSAPublicKey publicKey;

    public JwtValidator(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    public void init() {
        try {
            loadPublicKey();
        } catch (Exception e) {
            logger.warn("JWKS is not available yet — public key will be loaded on first request. Cause: {}", e.getMessage());
        }
    }

    private synchronized void loadPublicKey() {
        String jwksUri = jwtConfig.getJwksUri();

        if (!jwksUri.startsWith("https://")) {
            logger.warn("JWKS URI is not using HTTPS: {}. This is insecure in production", jwksUri);
        }

        logger.info("Loading public key from JWKS: {}", jwksUri);

        String jwksJson = restTemplate.getForObject(jwksUri, String.class);

        if (jwksJson == null || jwksJson.isEmpty()) {
            throw new RuntimeException("JWKS endpoint returned empty response");
        }

        JWKSet jwkSet;
        try {
            jwkSet = JWKSet.parse(jwksJson);
        } catch (java.text.ParseException e) {
            throw new RuntimeException("Failed to parse JWKS response", e);
        }

        if (jwkSet.getKeys().isEmpty()) {
            throw new RuntimeException("JWKS endpoint returned no keys");
        }

        JWK jwk = jwkSet.getKeys().get(0);
        if (!(jwk instanceof RSAKey)) {
            throw new RuntimeException("JWKS must contain RSA key, found: " + jwk.getKeyType());
        }
        RSAKey rsaKey = (RSAKey) jwk;

        try {
            this.publicKey = rsaKey.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new RuntimeException("Failed to extract RSA public key", e);
        }

        logger.info("Public key loaded successfully (kid: {})", jwk.getKeyID());
    }

    private RSAPublicKey getPublicKey() {
        if (publicKey == null) {
            loadPublicKey();
        }
        return publicKey;
    }

    public String validateToken(String token)
            throws JwtValidationException, IllegalArgumentException {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token must not be null or empty");
        }

        SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(token);
        } catch (java.text.ParseException e) {
            throw new JwtValidationException("Invalid token format", e);
        }

        try {
            JWSVerifier verifier = new RSASSAVerifier(getPublicKey());
            if (!signedJWT.verify(verifier)) {
                throw new JwtValidationException("Invalid JWT signature");
            }
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new JwtValidationException("Invalid JWT signature");
        }

        try {
            String issuer = signedJWT.getJWTClaimsSet().getIssuer();
            if (issuer == null) {
                throw new JwtValidationException("Token missing issuer");
            }
            if (!jwtConfig.getIssuer().equals(issuer)) {
                throw new JwtValidationException("Invalid issuer");
            }

            Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (expirationTime == null) {
                throw new JwtValidationException("Token missing expiration time");
            }
            if (expirationTime.before(new Date())) {
                throw new JwtValidationException("Token has expired");
            }

            List<String> apps = signedJWT.getJWTClaimsSet().getStringListClaim("apps");
            if (apps == null || !apps.contains(jwtConfig.getRequiredApp())) {
                throw new JwtValidationException("Insufficient permissions");
            }

            String subject = signedJWT.getJWTClaimsSet().getSubject();
            if (subject == null) {
                throw new JwtValidationException("Token missing subject");
            }
            return subject;
        } catch (java.text.ParseException e) {
            throw new JwtValidationException("Failed to parse JWT claims", e);
        }
    }
}
