package dev.orestegabo.sequo_api.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, String> {
    fun findByEmail(email: String): User?
    fun findByPhoneNumber(phoneNumber: String): User?
}

interface UserIdentityRepository : JpaRepository<UserIdentity, String> {
    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): UserIdentity?
    fun existsByUserIdAndProvider(userId: String, provider: AuthProvider): Boolean
    fun findAllByUserIdAndProvider(userId: String, provider: AuthProvider): List<UserIdentity>
}

interface MerchantMembershipRepository : JpaRepository<MerchantMembership, String> {
    fun findAllByUserIdAndActiveTrue(userId: String): List<MerchantMembership>
    fun existsByUserIdAndMerchantIdAndActiveTrue(userId: String, merchantId: String): Boolean
}

interface UserDeviceRepository : JpaRepository<UserDevice, String> {
    fun findByDeviceId(deviceId: String): UserDevice?
    fun findByRefreshTokenHash(refreshTokenHash: String): UserDevice?
    fun findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId: String, expiresAt: java.time.Instant): List<UserDevice>
    fun findTop100ByUserIdOrderByCreatedAtDesc(userId: String): List<UserDevice>
    fun findByIdAndUserId(id: String, userId: String): UserDevice?
}

interface AuthChallengeRepository : JpaRepository<AuthChallenge, String> {
    fun findByIdAndPurpose(id: String, purpose: AuthChallengePurpose): AuthChallenge?
}
