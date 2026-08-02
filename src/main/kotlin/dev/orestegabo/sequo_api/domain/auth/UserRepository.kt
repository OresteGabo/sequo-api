package dev.orestegabo.sequo_api.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, String> {
    fun findByEmail(email: String): User?
    fun findByProviderAndProviderId(provider: AuthProvider, providerId: String): User?
    fun findByResetTokenHash(resetTokenHash: String): User?
}
