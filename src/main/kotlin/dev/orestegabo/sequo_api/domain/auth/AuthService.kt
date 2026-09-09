package dev.orestegabo.sequo_api.domain.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val socialIdentityRepository: SocialIdentityRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleVerifier: GoogleTokenVerifier,
    private val facebookVerifier: FacebookTokenVerifier,
    private val appleVerifier: AppleTokenVerifier,
    private val jwtService: JwtService,
    private val passwordPolicy: PasswordPolicy,
    private val passwordResetTokenService: PasswordResetTokenService,
    private val passwordResetTokenNotifier: PasswordResetTokenNotifier,
    private val refreshSessionService: RefreshSessionService,
) {
    fun signUp(request: AuthController.SignUpRequest): AuthTokens {
        val email = normalizeEmail(request.email)
        val existingUser = userRepository.findByEmail(email)
        if (existingUser != null) {
            if (existingUser.provider != AuthProvider.EMAIL || existingUser.passwordHash == null) {
                throw AuthProviderRequiredException(existingUser.provider)
            }
            throw EmailAlreadyRegisteredException()
        }

        passwordPolicy.validateOrThrow(
            request.password,
            PasswordPolicyContext(email = email, displayName = request.name)
        )

        val user = User(
            email = email,
            passwordHash = passwordEncoder.encode(request.password),
            name = request.name,
            provider = AuthProvider.EMAIL
        )
        val savedUser = userRepository.save(user)
        return generateTokensForUser(savedUser)
    }

    fun login(request: AuthController.LoginWithEmailRequest): AuthTokens? {
        val user = userRepository.findByEmail(normalizeEmail(request.email)) ?: return null
        if (!user.status.canAuthenticate()) return null
        if (user.provider != AuthProvider.EMAIL || user.passwordHash == null) {
            throw AuthProviderRequiredException(user.provider)
        }
        
        if (!passwordEncoder.matches(request.password, user.passwordHash)) return null
        
        return generateTokensForUser(user)
    }

    @Transactional
    fun loginWithSocialToken(provider: AuthProvider, token: String): AuthTokens? {
        val verifier = when (provider) {
            AuthProvider.GOOGLE -> googleVerifier
            AuthProvider.FACEBOOK -> facebookVerifier
            AuthProvider.APPLE -> appleVerifier
            else -> return null
        }

        val socialUser = verifier.verify(token) ?: return null
        if (socialUser.provider != provider) return null
        if (socialUser.email != null && !socialUser.emailVerified) return null
        
        val now = Instant.now()
        val linkedIdentity = socialIdentityRepository.findByProviderAndProviderSubject(provider, socialUser.providerId)
        var user = linkedIdentity?.let { userRepository.findById(it.userId).orElse(null) }
        if (linkedIdentity != null && user == null) return null

        // Keep reading the legacy column while existing accounts are migrated to the identity table.
        user = user ?: userRepository.findByProviderAndProviderId(provider, socialUser.providerId)
        if (user == null) {
            val email = socialUser.email?.let(::normalizeEmail)
            val existingUser = email?.let { userRepository.findByEmail(it) }
            if (existingUser != null) {
                throw AccountLinkRequiredException(
                    existingProvider = existingUser.provider,
                    attemptedProvider = provider
                )
            } else {
                user = userRepository.save(User(
                    email = email ?: providerScopedEmail(socialUser),
                    name = socialUser.name,
                    provider = provider,
                    providerId = socialUser.providerId
                ))
            }
        }

        if (!user.status.canAuthenticate()) return null

        if (linkedIdentity == null) {
            socialIdentityRepository.save(
                SocialIdentity(
                    userId = requireNotNull(user.id),
                    provider = provider,
                    providerSubject = socialUser.providerId,
                    verifiedEmail = socialUser.email?.let(::normalizeEmail),
                    lastLoginAt = now,
                )
            )
        } else {
            linkedIdentity.lastLoginAt = now
            socialIdentityRepository.save(linkedIdentity)
        }

        return generateTokensForUser(user)
    }

    @Transactional
    fun refreshTokens(refreshToken: String): AuthTokens? {
        val session = jwtService.parseRefreshToken(refreshToken) ?: return null
        val user = userRepository.findById(session.userId).orElse(null) ?: return null
        val storedSession = refreshSessionService.findByToken(refreshToken)
        if (storedSession == null) {
            // A known revoked token is a replay: invalidate the remaining session set.
            refreshSessionService.revokeAllForUser(session.userId)
            return null
        }
        if (storedSession.userId != session.userId || storedSession.revokedAt != null || storedSession.expiresAt.isBefore(Instant.now())) return null
        if (!user.status.canAuthenticate()) {
            refreshSessionService.revokeAllForUser(user.id!!)
            return null
        }

        val now = Instant.now()
        storedSession.lastUsedAt = now
        refreshSessionService.revoke(storedSession, now)
        val tokens = generateJwtTokensForUser(user)
        refreshSessionService.create(requireNotNull(user.id), tokens.refreshToken, jwtService.refreshExpiresAt(now), now)
        return tokens
    }

    @Transactional
    fun logout(refreshToken: String) {
        refreshSessionService.findByToken(refreshToken)?.let { refreshSessionService.revoke(it) }
    }

    @Transactional
    fun logoutAll(userId: String) = refreshSessionService.revokeAllForUser(userId)

    fun forgotPassword(email: String) {
        val user = userRepository.findByEmail(normalizeEmail(email)) ?: return
        if (user.provider != AuthProvider.EMAIL || !user.status.canAuthenticate()) return

        val token = passwordResetTokenService.generate()
        user.resetTokenHash = token.tokenHash
        user.resetTokenExpiry = Instant.now().plus(30, ChronoUnit.MINUTES)
        val savedUser = userRepository.save(user)
        passwordResetTokenNotifier.send(
            userId = requireNotNull(savedUser.id) { "Persisted user id is required before password reset notification." },
            email = savedUser.email,
            rawToken = token.rawToken,
        )
    }

    fun resetPassword(request: AuthController.ResetPasswordRequest): Boolean {
        val tokenHash = passwordResetTokenService.hash(request.token)
        val user = userRepository.findByResetTokenHash(tokenHash) ?: return false
        val resetTokenExpiry = user.resetTokenExpiry ?: return false
        if (resetTokenExpiry.isBefore(Instant.now())) return false

        passwordPolicy.validateOrThrow(
            request.newPassword,
            PasswordPolicyContext(email = user.email, displayName = user.name)
        )

        user.passwordHash = passwordEncoder.encode(request.newPassword)
        user.resetTokenHash = null
        user.resetTokenExpiry = null
        userRepository.save(user)
        return true
    }

    private fun generateTokensForUser(user: User): AuthTokens {
        val tokens = generateJwtTokensForUser(user)
        refreshSessionService.create(
            userId = requireNotNull(user.id) { "Persisted user id is required before token generation." },
            rawToken = tokens.refreshToken,
            expiresAt = jwtService.refreshExpiresAt(),
        )
        return tokens
    }

    private fun generateJwtTokensForUser(user: User): AuthTokens {
        val session = UserSession(
            userId = requireNotNull(user.id) { "Persisted user id is required before token generation." },
            email = user.email,
            provider = user.provider
        )
        return jwtService.generateTokens(session)
    }

    private fun normalizeEmail(email: String): String =
        email.trim().lowercase()

    private fun providerScopedEmail(socialUser: SocialUser): String =
        "${socialUser.providerId}@${socialUser.provider.name.lowercase()}.sequo.local"
}
