package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.*

interface UserRepository : JpaRepository<User, String> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update User u set u.authorizationVersion = :version, u.recordVersion = u.recordVersion + 1 where u.id = :id")
    fun rotateAuthorizationVersion(id: String, version: String): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun lockCheckoutOwner(id: String): User?

    fun findByEmail(email: String): User?
    fun findByEmailIgnoreCase(email: String): User?
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
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update UserDevice d set d.revokedAt = :at where d.id = :id and d.revokedAt is null")
    fun revokeForRotation(id: String, at: java.time.Instant): Int

    fun findByDeviceId(deviceId: String): UserDevice?
    fun findByRefreshTokenHash(refreshTokenHash: String): UserDevice?
    fun findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId: String, expiresAt: java.time.Instant): List<UserDevice>
    fun findTop100ByUserIdOrderByCreatedAtDesc(userId: String): List<UserDevice>
    fun findByIdAndUserId(id: String, userId: String): UserDevice?
}

interface AuthChallengeRepository : JpaRepository<AuthChallenge, String> {
    fun findByIdAndPurpose(id: String, purpose: AuthChallengePurpose): AuthChallenge?
}


interface AdminLoginRequestRepository : JpaRepository<AdminLoginRequest, String> {
    fun findByIdAndStatus(id: String, status: AdminLoginRequestStatus): AdminLoginRequest?
    fun findAllByEmailNormalizedAndStatusAndExpiresAtAfterOrderByRequestedAtDesc(
        emailNormalized: String,
        status: AdminLoginRequestStatus,
        expiresAt: java.time.Instant,
    ): List<AdminLoginRequest>
}

interface AdminEmailCodeRepository : JpaRepository<AdminEmailCode, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByEmailNormalizedAndStatusOrderByRequestedAtDesc(
        emailNormalized: String,
        status: AdminEmailCodeStatus,
    ): AdminEmailCode?
}
