package dev.orestegabo.sequo_api.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, String> {
    fun findByEmail(email: String): User?
    fun findByProviderAndProviderId(provider: AuthProvider, providerId: String): User?
    fun findByResetTokenHash(resetTokenHash: String): User?
}

interface SocialIdentityRepository : JpaRepository<SocialIdentity, String> {
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): SocialIdentity?
    fun existsByUserIdAndProvider(userId: String, provider: AuthProvider): Boolean
}

interface MerchantMembershipRepository : JpaRepository<MerchantMembership, String> {
    fun findAllByUserIdAndActiveTrue(userId: String): List<MerchantMembership>
    fun existsByUserIdAndMerchantIdAndActiveTrue(userId: String, merchantId: String): Boolean
}
