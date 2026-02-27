package com.portfolio.receiptocr.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Validated
public class JwtConfig {
    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @NotBlank(message = "jwt.issuer must not be blank")
    private String issuer;

    @NotBlank(message = "jwt.jwksUri must not be blank")
    private String jwksUri;

    @NotBlank(message = "jwt.requiredApp must not be blank")
    private String requiredApp;

    @PostConstruct
    public void logConfig() {
        log.info("=================================================");
        log.info("JWT Configuration loaded:");
        log.info("  issuer:       {}", issuer);
        log.info("  jwksUri:      {}", jwksUri);
        log.info("  requiredApp:  {}", requiredApp);
        log.info("=================================================");
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getRequiredApp() {
        return requiredApp;
    }

    public void setRequiredApp(String requiredApp) {
        this.requiredApp = requiredApp;
    }
}
