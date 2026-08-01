package dev.orestegabo.sequo_api.domain.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleVerifier: GoogleTokenVerifier,
    private val facebookVerifier: FacebookTokenVerifier,
    private val appleVerifier: AppleTokenVerifier,
    private val jwtService: JwtService
) {
    fun signUp(request: AuthController.SignUpRequest): AuthTokens {
        if (userRepository.findByEmail(request.email) != null) {
            throw IllegalArgumentException("Email already in use")
        }

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

        return generateTokensForUser(user!!)
    }

    fun refreshTokens(refreshToken: String): AuthTokens? {
        val session = jwtService.parseToken(refreshToken) ?: return null
        val user = userRepository.findById(session.userId).orElse(null) ?: return null
        return generateTokensForUser(user)
    }

    fun forgotPassword(email: String): String? {
        val user = userRepository.findByEmail(email) ?: return null
        if (user.provider != AuthProvider.EMAIL) return null

        val token = UUID.randomUUID().toString()
        user.resetToken = token
        user.resetTokenExpiry = Instant.now().plus(1, ChronoUnit.HOURS)
        userRepository.save(user)

        // In a real app, send an email here
        return token
    }

    fun resetPassword(request: AuthController.ResetPasswordRequest): Boolean {
        val user = userRepository.findByResetToken(request.token) ?: return false
        if (user.resetTokenExpiry?.isBefore(Instant.now()) == true) return false

        user.passwordHash = passwordEncoder.encode(request.newPassword)
        user.resetToken = null
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
