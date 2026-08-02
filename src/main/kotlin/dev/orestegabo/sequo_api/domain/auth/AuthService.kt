package dev.orestegabo.sequo_api.domain.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleVerifier: GoogleTokenVerifier,
    private val facebookVerifier: FacebookTokenVerifier,
    private val appleVerifier: AppleTokenVerifier,
    private val jwtService: JwtService,
    private val passwordPolicy: PasswordPolicy,
    private val passwordResetTokenService: PasswordResetTokenService
) {
    fun signUp(request: AuthController.SignUpRequest): AuthTokens {
        if (userRepository.findByEmail(request.email) != null) {
            throw IllegalArgumentException("Email already in use")
        }

        passwordPolicy.validateOrThrow(
            request.password,
            PasswordPolicyContext(email = request.email, displayName = request.name)
        )

        val user = User(
            email = request.email,
            passwordHash = passwordEncoder.encode(request.password),
            name = request.name,
            provider = AuthProvider.EMAIL
        )
        val savedUser = userRepository.save(user)
        return generateTokensForUser(savedUser)
    }

    fun login(request: AuthController.LoginWithEmailRequest): AuthTokens? {
        val user = userRepository.findByEmail(request.email) ?: return null
        if (!user.status.canAuthenticate()) return null
        if (user.provider != AuthProvider.EMAIL || user.passwordHash == null) return null
        
        if (!passwordEncoder.matches(request.password, user.passwordHash)) return null
        
        return generateTokensForUser(user)
    }

    fun loginWithSocialToken(provider: AuthProvider, token: String): AuthTokens? {
        val verifier = when (provider) {
            AuthProvider.GOOGLE -> googleVerifier
            AuthProvider.FACEBOOK -> facebookVerifier
            AuthProvider.APPLE -> appleVerifier
            else -> return null
        }

        val socialUser = verifier.verify(token) ?: return null
        
        var user = userRepository.findByProviderAndProviderId(provider, socialUser.providerId)
        if (user == null) {
            // Check if user exists with the same email
            val existingUser = socialUser.email?.let { userRepository.findByEmail(it) }
            if (existingUser != null) {
                // Link account if necessary or return existing (depends on policy)
                user = existingUser
            } else {
                user = userRepository.save(User(
                    email = socialUser.email ?: "${socialUser.providerId}@${provider.name.lowercase()}.com",
                    name = socialUser.name,
                    provider = provider,
                    providerId = socialUser.providerId
                ))
            }
        }

        if (!user.status.canAuthenticate()) return null

        return generateTokensForUser(user)
    }

    fun refreshTokens(refreshToken: String): AuthTokens? {
        val session = jwtService.parseRefreshToken(refreshToken) ?: return null
        val user = userRepository.findById(session.userId).orElse(null) ?: return null
        if (!user.status.canAuthenticate()) return null
        return generateTokensForUser(user)
    }

    fun forgotPassword(email: String) {
        val user = userRepository.findByEmail(email) ?: return
        if (user.provider != AuthProvider.EMAIL || !user.status.canAuthenticate()) return

        val token = passwordResetTokenService.generate()
        user.resetTokenHash = token.tokenHash
        user.resetTokenExpiry = Instant.now().plus(30, ChronoUnit.MINUTES)
        userRepository.save(user)

        // TODO(sequo-auth): Send token.rawToken through the approved email/SMS provider.
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
        val session = UserSession(
            userId = user.id!!,
            email = user.email,
            provider = user.provider
        )
        return jwtService.generateTokens(session)
    }
}
