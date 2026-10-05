package com.erfeamor.cvdomain.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Validates AWS Cognito JWTs on every request except the health probe. Reads accept any pool
 * token; writes (POST, PUT, PATCH, DELETE) require a user token, i.e. scope {@code openid}
 * (T-116).
 * The issuer URI comes from `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Granted from the JWT's {@code scope} claim; only user tokens carry {@code openid}. */
    static final String WRITE_AUTHORITY = "SCOPE_openid";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            @Value("${app.auth.enabled}") boolean authEnabled) throws Exception {
        http.cors(Customizer.withDefaults());

        if (!authEnabled) {
            // Local/dev stacks run without a Cognito user pool; mirrors the
            // AUTH_ENABLED toggle in cv-bff-node. Never disable in production.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        http
                .authorizeHttpRequests(auth -> auth
                        // T-106: only the liveness probe is anonymous.
                        //
                        // /v3/api-docs, /swagger-ui/** and /actuator/prometheus
                        // used to sit in this list, which is why the deployed
                        // box served its full OpenAPI document to anyone who
                        // asked (200, including "servers":[{"url":"http://<eip>:8080"}]).
                        // T-022 removed the network path to it; this removes
                        // the reason it answered at all, which still matters
                        // because the CloudFront origin-facing prefix list is
                        // shared by every CloudFront customer (see T-025).
                        //
                        // Local stacks are unaffected: they run
                        // AUTH_ENABLED=false and take the permitAll branch
                        // above, so cv-observability's Prometheus keeps
                        // scraping /actuator/prometheus and Swagger UI keeps
                        // working on :8080. Deploying a scraper against this
                        // service means giving it a token -- deliberately, so
                        // that decision is taken rather than inherited.
                        .requestMatchers("/actuator/health").permitAll()
                        // T-116: writes need a user token. Cognito puts "openid"
                        // in the scope of hosted-UI (user) access tokens only;
                        // a client-credentials token (the BFF's: cv-domain/read)
                        // can never carry it, so every machine token is
                        // read-only by construction. The default
                        // JwtGrantedAuthoritiesConverter maps the "scope" claim
                        // to SCOPE_* authorities. A write without it is 403.
                        .requestMatchers(HttpMethod.POST).hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT).hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.PATCH).hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.DELETE).hasAuthority(WRITE_AUTHORITY)
                        // GET, HEAD, OPTIONS: any valid pool token. CORS
                        // preflight never reaches here (CorsFilter answers it).
                        .anyRequest().authenticated())
                // Bearer-only and stateless: no cookie or session ever
                // authenticates a request, so there is nothing for CSRF to
                // protect. Left on, it answered a token-less write with 403
                // before authentication could answer 401.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    /**
     * Browser clients (cv-admin-react on :5173) call this API cross-origin.
     * Origins are configured via the app.cors.allowed-origins property /
     * CORS_ALLOWED_ORIGINS env var.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
