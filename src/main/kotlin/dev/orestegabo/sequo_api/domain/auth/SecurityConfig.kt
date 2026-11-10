package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.config.ApiContentTypeFilter
import dev.orestegabo.sequo_api.config.ApiRequestRateLimitFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val inMemoryRateLimiter: InMemoryRateLimiter,
    @Value("\${sequo.security.cors.allowed-origins:}")
    private val corsAllowedOrigins: List<String>
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val paths = PathPatternRequestMatcher.withDefaults()
        val profilePaths = OrRequestMatcher(paths.matcher("/api/auth/me"), paths.matcher("/api/v1/auth/me"))
        val entryPoint = DelegatingAuthenticationEntryPoint(
            linkedMapOf(profilePaths to HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        ).apply { setDefaultEntryPoint(Http403ForbiddenEntryPoint()) }
        http
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .cors { }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.GET, "/relay-operator.html", "/relay-operator.js", "/api/demo/images/*").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/auth/logout-all").authenticated()
                    .requestMatchers("/api/v1/auth/me", "/api/v1/auth/sessions/**").authenticated()
                    .requestMatchers("/api/v1/auth/passkey/register/**").authenticated()
                    .requestMatchers("/api/v1/auth/cross-device/resolve").authenticated()
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/api/auth/logout-all").authenticated()
                    .requestMatchers("/api/auth/me", "/api/auth/sessions/**").authenticated()
                    .requestMatchers("/api/auth/passkey/register/**").authenticated()
                    .requestMatchers("/api/auth/cross-device/resolve").authenticated()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/partner-applications").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin-auth/login-requests").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/admin-auth/login-requests/*").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin-auth/login-requests/*/exchange").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin-auth/email-codes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin-auth/email-codes/verify").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/admin-auth/development-access").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin-auth/development-session").permitAll()
                    .requestMatchers("/api/admin-auth/mobile/**").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/yas_togo", "/api/payments/webhooks/moov_africa", "/api/payments/kkiapay/webhook", "/api/payments/kkiapay/complete").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/catalog/products/*/customizations/quote").permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/catalog/home",
                        "/api/catalog/product-references",
                        "/api/catalog/product-references/essentials",
                        "/api/catalog/categories",
                        "/api/catalog/merchants",
                        "/api/catalog/products",
                        "/api/catalog/products/*/related",
                        "/api/catalog/products/*/image",
                        "/api/catalog/products/*/customizations",
                        "/api/subscriptions/plans",
                        "/api/payments/providers",
                        "/payments/kkiapay",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(ApiContentTypeFilter(), JwtAuthenticationFilter::class.java)
            .addFilterAfter(ApiRequestRateLimitFilter(inMemoryRateLimiter), ApiContentTypeFilter::class.java)
        
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val origins = corsAllowedOrigins.map { it.trim() }.filter { it.isNotBlank() }
        val configuration = CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Requested-With")
            exposedHeaders = listOf("Retry-After")
            allowCredentials = true
            maxAge = 3600
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
