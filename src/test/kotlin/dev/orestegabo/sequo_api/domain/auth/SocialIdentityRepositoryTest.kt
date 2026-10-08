package dev.orestegabo.sequo_api.domain.auth

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest
@Transactional
class SocialIdentityRepositoryTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val identityRepository: UserIdentityRepository,
) {
    @Test
    fun storesAndFindsIdentityByProviderUserId() {
        val user = userRepository.save(
            User(
                email = "social-identity@sequo.test",
                name = "Social User",
                provider = AuthProvider.GOOGLE,
            )
        )
        val saved = identityRepository.save(
            UserIdentity(
                userId = requireNotNull(user.id),
                provider = AuthProvider.GOOGLE,
                providerUserId = "google-subject-1",
            )
        )

        val found = identityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "google-subject-1")

        assertNotNull(saved.id)
        assertEquals(user.id, found?.userId)
        assertEquals(true, identityRepository.existsByUserIdAndProvider(requireNotNull(user.id), AuthProvider.GOOGLE))
    }

    @Test
    fun providerUserIdCannotBeLinkedToTwoUsers() {
        val first = userRepository.save(User(email = "social-one@sequo.test", provider = AuthProvider.EMAIL))
        val second = userRepository.save(User(email = "social-two@sequo.test", provider = AuthProvider.EMAIL))
        val subject = "shared-subject"
        identityRepository.save(UserIdentity(userId = requireNotNull(first.id), provider = AuthProvider.APPLE, providerUserId = subject))

        assertFailsWith<DataIntegrityViolationException> {
            identityRepository.saveAndFlush(UserIdentity(userId = requireNotNull(second.id), provider = AuthProvider.APPLE, providerUserId = subject))
        }
    }

    @Test
    fun emailProviderIsRejectedForUserIdentity() {
        assertFailsWith<IllegalArgumentException> {
            UserIdentity(userId = "user-1", provider = AuthProvider.EMAIL, providerUserId = "email-subject")
        }
    }
}
