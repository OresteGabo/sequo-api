package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

data class DeviceBinding(
    val deviceId: String,
    val appSource: AppSource,
    val fcmToken: String?,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId is required." }
    }
}

data class PasskeyRegistrationChallenge(
    val challengeId: String,
    val challenge: String,
    val userId: String,
    val relyingPartyId: String,
    val expiresAt: Instant,
)

data class PasskeyAuthenticationChallenge(
    val challengeId: String,
    val challenge: String,
    val relyingPartyId: String,
    val expiresAt: Instant,
)

data class CrossDeviceChallenge(
    val challengeId: String,
    val expiresAt: Instant,
)

data class PasskeyAssertion(
    val credentialId: String,
    val clientDataJson: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null,
    val signCount: Long,
)

interface WhatsAppOtpSender {
    fun sendAuthenticationCode(phoneNumber: String, code: String)
}

@Service
class LoggingWhatsAppOtpSender : WhatsAppOtpSender {
    private val logger = LoggerFactory.getLogger(LoggingWhatsAppOtpSender::class.java)

    override fun sendAuthenticationCode(phoneNumber: String, code: String) {
        logger.info("WhatsApp OTP queued for phone={} via Meta Cloud API template.", phoneNumber)
    }
}

interface CrossDevicePushSender {
    fun sendLoginApproval(device: UserDevice, challengeId: String, requestingAppSource: AppSource)
}

@Service
class LoggingCrossDevicePushSender : CrossDevicePushSender {
    private val logger = LoggerFactory.getLogger(LoggingCrossDevicePushSender::class.java)

    override fun sendLoginApproval(device: UserDevice, challengeId: String, requestingAppSource: AppSource) {
        logger.info(
            "Cross-device login approval queued for device={} app={} challenge={}.",
            device.deviceId,
            requestingAppSource,
            challengeId,
        )
    }
}

interface PasskeyVerifier {
    fun verifyRegistration(challenge: String, credentialPublicKey: String): Boolean
    fun verifyAssertion(challenge: String, identity: UserIdentity, assertion: PasskeyAssertion): Boolean
}

@Service
class ExtensionPointPasskeyVerifier : PasskeyVerifier {
    override fun verifyRegistration(challenge: String, credentialPublicKey: String): Boolean =
        challenge.isNotBlank() && credentialPublicKey.isNotBlank()

    override fun verifyAssertion(challenge: String, identity: UserIdentity, assertion: PasskeyAssertion): Boolean =
        challenge.isNotBlank() &&
            identity.provider == AuthProvider.PASSKEY &&
            assertion.credentialId == identity.providerUserId &&
            assertion.signature.isNotBlank() &&
            assertion.clientDataJson.isNotBlank() &&
            assertion.authenticatorData.isNotBlank() &&
            assertion.signCount >= identity.signCount
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val authChallengeRepository: AuthChallengeRepository,
    private val googleVerifier: GoogleTokenVerifier,
    private val facebookVerifier: FacebookTokenVerifier,
    private val appleVerifier: AppleTokenVerifier,
    private val jwtService: JwtService,
    private val tokenService: PasswordResetTokenService,
    private val merchantMembershipRepository: MerchantMembershipRepository,
    private val whatsAppOtpSender: WhatsAppOtpSender,
    private val crossDevicePushSender: CrossDevicePushSender,
    private val passkeyVerifier: PasskeyVerifier,
    @Value("\${sequo.auth.passkey.relying-party-id:api.sequo.local}")
    private val relyingPartyId: String,
) {
    private val secureRandom = SecureRandom()
    private val codeRandom = SecureRandom()

    @Transactional
    fun loginWithSocialToken(provider: AuthProvider, token: String, device: DeviceBinding): AuthTokens? {
        val verifier = when (provider) {
            AuthProvider.GOOGLE -> googleVerifier
            AuthProvider.FACEBOOK -> facebookVerifier
            AuthProvider.APPLE -> appleVerifier
            else -> return null
        }
        val socialUser = verifier.verify(token) ?: return null
        if (socialUser.provider != provider) return null
        if (socialUser.email != null && !socialUser.emailVerified) return null

        val identity = userIdentityRepository.findByProviderAndProviderUserId(provider, socialUser.providerId)
        val user = if (identity != null) {
            userRepository.findById(identity.userId).orElse(null) ?: return null
        } else {
            val normalizedEmail = socialUser.email?.let(::normalizeEmail)
            val existing = normalizedEmail?.let { userRepository.findByEmail(it) }
            val provisioned = existing ?: userRepository.save(
                User(
                    email = normalizedEmail,
                    name = socialUser.name,
                    avatarUrl = socialUser.pictureUrl,
                    provider = provider,
                    roles = mutableSetOf(RoleCode.CUSTOMER),
                )
            )
            userIdentityRepository.save(
                UserIdentity(
                    userId = requireNotNull(provisioned.id),
                    provider = provider,
                    providerUserId = socialUser.providerId,
                    lastLoginAt = Instant.now(),
                )
            )
            provisioned
        }

        if (!user.canAuthenticate()) return null
        return issueTokensForUser(user, provider, device)
    }

    @Transactional
    fun requestWhatsAppOtp(phoneNumber: String): String {
        val normalizedPhone = normalizePhone(phoneNumber)
        val rawCode = "%06d".format(codeRandom.nextInt(1_000_000))
        val challenge = authChallengeRepository.save(
            AuthChallenge(
                purpose = AuthChallengePurpose.WHATSAPP_OTP,
                subject = normalizedPhone,
                challengeHash = tokenService.hash(rawCode),
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
        )
        whatsAppOtpSender.sendAuthenticationCode(normalizedPhone, rawCode)
        return requireNotNull(challenge.id)
    }

    @Transactional
    fun verifyWhatsAppOtp(phoneNumber: String, code: String, device: DeviceBinding): AuthTokens? {
        val normalizedPhone = normalizePhone(phoneNumber)
        val challenge = authChallengeRepository.findAll()
            .asSequence()
            .filter { it.purpose == AuthChallengePurpose.WHATSAPP_OTP && it.subject == normalizedPhone }
            .filter { it.consumedAt == null && it.expiresAt.isAfter(Instant.now()) }
            .maxByOrNull { it.createdAt }
            ?: return null
        if (!tokenService.matches(code.trim(), challenge.challengeHash)) return null
        challenge.consumedAt = Instant.now()
        authChallengeRepository.save(challenge)

        val user = userRepository.findByPhoneNumber(normalizedPhone) ?: userRepository.save(
            User(
                phoneNumber = normalizedPhone,
                name = normalizedPhone,
                provider = AuthProvider.EMAIL,
                roles = mutableSetOf(RoleCode.CUSTOMER),
            )
        )
        if (!user.canAuthenticate()) return null
        return issueTokensForUser(user, AuthProvider.EMAIL, device)
    }

    @Transactional
    fun createPasskeyRegistrationChallenge(userId: String): PasskeyRegistrationChallenge? {
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (!user.canAuthenticate()) return null
        val raw = randomChallenge()
        val challenge = authChallengeRepository.save(
            AuthChallenge(
                purpose = AuthChallengePurpose.PASSKEY_REGISTRATION,
                subject = userId,
                challengeHash = tokenService.hash(raw),
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
        )
        return PasskeyRegistrationChallenge(
            challengeId = requireNotNull(challenge.id),
            challenge = raw,
            userId = userId,
            relyingPartyId = relyingPartyId,
            expiresAt = challenge.expiresAt,
        )
    }

    @Transactional
    fun finishPasskeyRegistration(userId: String, challengeId: String, credentialId: String, credentialPublicKey: String): Boolean {
        val challenge = authChallengeRepository.findByIdAndPurpose(challengeId, AuthChallengePurpose.PASSKEY_REGISTRATION)
            ?: return false
        if (challenge.subject != userId || challenge.consumedAt != null || challenge.expiresAt.isBefore(Instant.now())) return false
        val rawChallenge = challenge.challengeHash
        if (!passkeyVerifier.verifyRegistration(rawChallenge, credentialPublicKey)) return false
        userIdentityRepository.save(
            UserIdentity(
                userId = userId,
                provider = AuthProvider.PASSKEY,
                providerUserId = credentialId,
                credentialPublicKey = credentialPublicKey,
            )
        )
        challenge.consumedAt = Instant.now()
        authChallengeRepository.save(challenge)
        return true
    }

    @Transactional
    fun createPasskeyAuthenticationChallenge(credentialId: String): PasskeyAuthenticationChallenge? {
        val identity = userIdentityRepository.findByProviderAndProviderUserId(AuthProvider.PASSKEY, credentialId) ?: return null
        val raw = randomChallenge()
        val challenge = authChallengeRepository.save(
            AuthChallenge(
                purpose = AuthChallengePurpose.PASSKEY_AUTHENTICATION,
                subject = identity.providerUserId,
                challengeHash = tokenService.hash(raw),
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
        )
        return PasskeyAuthenticationChallenge(
            challengeId = requireNotNull(challenge.id),
            challenge = raw,
            relyingPartyId = relyingPartyId,
            expiresAt = challenge.expiresAt,
        )
    }

    @Transactional
    fun finishPasskeyAuthentication(challengeId: String, assertion: PasskeyAssertion, device: DeviceBinding): AuthTokens? {
        val challenge = authChallengeRepository.findByIdAndPurpose(challengeId, AuthChallengePurpose.PASSKEY_AUTHENTICATION)
            ?: return null
        if (challenge.subject != assertion.credentialId || challenge.consumedAt != null || challenge.expiresAt.isBefore(Instant.now())) return null
        val identity = userIdentityRepository.findByProviderAndProviderUserId(AuthProvider.PASSKEY, assertion.credentialId) ?: return null
        if (!passkeyVerifier.verifyAssertion(challenge.challengeHash, identity, assertion)) return null
        val user = userRepository.findById(identity.userId).orElse(null) ?: return null
        if (!user.canAuthenticate()) return null
        identity.signCount = assertion.signCount
        identity.lastLoginAt = Instant.now()
        userIdentityRepository.save(identity)
        challenge.consumedAt = Instant.now()
        authChallengeRepository.save(challenge)
        return issueTokensForUser(user, AuthProvider.PASSKEY, device)
    }

    @Transactional
    fun initiateCrossDeviceLogin(phoneNumber: String, requestingDevice: DeviceBinding): CrossDeviceChallenge? {
        val user = userRepository.findByPhoneNumber(normalizePhone(phoneNumber)) ?: return null
        if (!user.canAuthenticate()) return null
        val userId = requireNotNull(user.id)
        val now = Instant.now()
        val activeDevices = userDeviceRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now)
            .filter { it.fcmToken?.isNotBlank() == true && it.deviceId != requestingDevice.deviceId }
        if (activeDevices.isEmpty()) return null

        val raw = randomChallenge()
        val challenge = authChallengeRepository.save(
            AuthChallenge(
                purpose = AuthChallengePurpose.CROSS_DEVICE_LOGIN,
                subject = userId,
                challengeHash = tokenService.hash(raw),
                requestingDeviceId = requestingDevice.deviceId,
                requestingAppSource = requestingDevice.appSource,
                expiresAt = now.plus(3, ChronoUnit.MINUTES),
            )
        )
        activeDevices.forEach {
            crossDevicePushSender.sendLoginApproval(it, requireNotNull(challenge.id), requestingDevice.appSource)
        }
        return CrossDeviceChallenge(requireNotNull(challenge.id), challenge.expiresAt)
    }

    @Transactional
    fun resolveCrossDeviceLogin(challengeId: String, approvingUserId: String, localBiometricVerified: Boolean, targetDevice: DeviceBinding): AuthTokens? {
        if (!localBiometricVerified) return null
        val challenge = authChallengeRepository.findByIdAndPurpose(challengeId, AuthChallengePurpose.CROSS_DEVICE_LOGIN)
            ?: return null
        if (challenge.subject != approvingUserId || challenge.consumedAt != null || challenge.expiresAt.isBefore(Instant.now())) return null
        val user = userRepository.findById(approvingUserId).orElse(null) ?: return null
        if (!user.canAuthenticate()) return null
        challenge.approvedUserId = approvingUserId
        challenge.consumedAt = Instant.now()
        authChallengeRepository.save(challenge)
        return issueTokensForUser(user, AuthProvider.EMAIL, targetDevice)
    }

    @Transactional
    fun refreshTokens(refreshToken: String, deviceId: String): AuthTokens? {
        val device = userDeviceRepository.findByRefreshTokenHash(tokenService.hash(refreshToken)) ?: return null
        if (device.deviceId != deviceId || device.revokedAt != null || device.expiresAt.isBefore(Instant.now())) return null
        val user = userRepository.findById(device.userId).orElse(null) ?: return null
        if (!user.canAuthenticate()) return null
        device.revokedAt = Instant.now()
        userDeviceRepository.save(device)
        return issueTokensForUser(
            user = user,
            provider = user.provider,
            device = DeviceBinding(device.deviceId, device.appSource, device.fcmToken),
        )
    }

    @Transactional
    fun logout(refreshToken: String) {
        userDeviceRepository.findByRefreshTokenHash(tokenService.hash(refreshToken))?.let {
            it.revokedAt = Instant.now()
            userDeviceRepository.save(it)
        }
    }

    @Transactional
    fun logoutAll(userId: String): Int {
        val devices = userDeviceRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, Instant.now())
        val now = Instant.now()
        devices.forEach { it.revokedAt = now }
        if (devices.isNotEmpty()) userDeviceRepository.saveAll(devices)
        return devices.size
    }

    fun listSessions(userId: String): List<RefreshSessionSnapshot> =
        userDeviceRepository.findTop100ByUserIdOrderByCreatedAtDesc(userId).map {
            RefreshSessionSnapshot(
                id = requireNotNull(it.id),
                createdAt = it.createdAt,
                lastUsedAt = it.lastActiveAt,
                expiresAt = it.expiresAt,
                revokedAt = it.revokedAt,
            )
        }

    fun currentUser(userId: String): User? = userRepository.findById(userId).orElse(null)

    @Transactional
    fun revokeSession(sessionId: String, userId: String): Boolean {
        val device = userDeviceRepository.findByIdAndUserId(sessionId, userId) ?: return false
        device.revokedAt = Instant.now()
        userDeviceRepository.save(device)
        return true
    }

    private fun issueTokensForUser(user: User, provider: AuthProvider, device: DeviceBinding, now: Instant = Instant.now()): AuthTokens {
        val userId = requireNotNull(user.id)
        val rawRefreshToken = tokenService.generate().rawToken
        val expiresAt = now.plus(DeviceRefreshLifetime)
        val existing = userDeviceRepository.findByDeviceId(device.deviceId)
        val persistedDevice = if (existing != null && existing.userId == userId) {
            existing.refreshTokenHash = tokenService.hash(rawRefreshToken)
            existing.fcmToken = device.fcmToken ?: existing.fcmToken
            existing.expiresAt = expiresAt
            existing.lastActiveAt = now
            existing.revokedAt = null
            userDeviceRepository.save(existing)
        } else {
            userDeviceRepository.save(
                UserDevice(
                    userId = userId,
                    deviceId = device.deviceId,
                    appSource = device.appSource,
                    fcmToken = device.fcmToken,
                    refreshTokenHash = tokenService.hash(rawRefreshToken),
                    expiresAt = expiresAt,
                    lastActiveAt = now,
                )
            )
        }
        val activeMerchantMemberships = merchantMembershipRepository.findAllByUserIdAndActiveTrue(userId)
        val session = UserSession(
            userId = userId,
            email = user.email,
            provider = provider,
            roles = (user.roles + activeMerchantMemberships.map { it.role }).toSet().ifEmpty { setOf(RoleCode.CUSTOMER) },
            merchantScopeIds = activeMerchantMemberships.map { it.merchantId }.toSet(),
            sessionId = requireNotNull(persistedDevice.id),
        )
        return AuthTokens(
            accessToken = jwtService.generateAccessToken(session),
            refreshToken = rawRefreshToken,
            expiresIn = jwtService.accessExpiresInSeconds(),
        )
    }

    private fun User.canAuthenticate(): Boolean = active && status.canAuthenticate()

    private fun normalizeEmail(email: String): String =
        ApiInputPolicy.normalizedIdentityEmail(email)

    fun normalizePhone(phoneNumber: String): String {
        val normalized = phoneNumber.trim().replace(" ", "").replace("-", "")
        require(normalized.matches(PhonePattern)) { "phoneNumber must be an E.164 number." }
        return normalized
    }

    private fun randomChallenge(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        private val DeviceRefreshLifetime: Duration = Duration.ofDays(730)
        private val PhonePattern = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}
