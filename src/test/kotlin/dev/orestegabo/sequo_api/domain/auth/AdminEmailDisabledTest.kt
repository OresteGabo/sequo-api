package dev.orestegabo.sequo_api.domain.auth

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest(properties = ["sequo.admin-auth.email.enabled=false"])
@Transactional
class AdminEmailDisabledTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val users: UserRepository,
) {
    @MockitoBean private lateinit var transport: AdminEmailTransport

    @Test
    fun realDisabledSenderRejectsKnownAndUnknownEmailsWithoutNetworkAccess() {
        users.save(User(email = "disabled-sender@sequo.test", roles = mutableSetOf(RoleCode.ADMIN)))
        for (email in listOf("disabled-sender@sequo.test", "unknown-disabled@sequo.test")) {
            val response = controller.requestEmailCode(AdminAuthController.EmailCodeRequest(email))
            assertEquals(503, response.statusCode.value())
            assertEquals("admin_email_unavailable", (response.body as AdminAuthController.AdminAuthErrorResponse).code)
        }
        Mockito.verifyNoInteractions(transport)
    }
}
