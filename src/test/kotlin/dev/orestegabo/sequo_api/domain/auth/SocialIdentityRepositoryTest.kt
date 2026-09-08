package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class SocialIdentityRepositoryTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val identityRepository: SocialIdentityRepository,
) {
    @Test
    fun storesAndFindsIdentityByProviderSubject() {
        val user = userRepository.save(
            User(
                email = "social-identity@sequo.test",
                name = "Social User",
                provider = AuthProvider.GOOGLE,
            )
        )
        val saved = identityRepository.save(
            SocialIdentity(
                userId = requireNotNull(user.id),
                provider = AuthProvider.GOOGLE,
                providerSubject = "google-subject-1",
                verifiedEmail = user.email,
            )
        )

        val found = identityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-subject-1")

        assertNotNull(saved.id)
        assertEquals(user.id, found?.userId)
        assertEquals(true, identityRepository.existsByUserIdAndProvider(requireNotNull(user.id), AuthProvider.GOOGLE))
    }

    @Test
    fun providerSubjectCannotBeLinkedToTwoUsers() {
        val first = userRepository.save(User(email = "social-one@sequo.test", provider = AuthProvider.EMAIL))
        val second = userRepository.save(User(email = "social-two@sequo.test", provider = AuthProvider.EMAIL))
        val subject = "shared-subject"
        identityRepository.save(SocialIdentity(userId = requireNotNull(first.id), provider = AuthProvider.APPLE, providerSubject = subject))

        assertFailsWith<DataIntegrityViolationException> {
            identityRepository.saveAndFlush(SocialIdentity(userId = requireNotNull(second.id), provider = AuthProvider.APPLE, providerSubject = subject))
        }
    }

    @Test
    fun emailProviderIsRejectedForSocialIdentity() {
        assertFailsWith<IllegalArgumentException> {
            SocialIdentity(userId = "user-1", provider = AuthProvider.EMAIL, providerSubject = "email-subject")
        }
    }
}
