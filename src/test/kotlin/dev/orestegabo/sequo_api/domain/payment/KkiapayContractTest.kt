package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*

class KkiapayContractTest {
    private val mapper = ObjectMapper()
    private fun evidence() = mapper.readTree("""{"transactionId":"tx-123","status":"SUCCESS","amount":1500,"currency":"XOF","type":"DEBIT","source":"MOBILE_MONEY","source_common_name":"mixx-tg","externalTransactionId":"session-123"}""") as com.fasterxml.jackson.databind.node.ObjectNode
    @Test fun onlyContractedTogoWalletsCanAuthorizeAnOrder() {
        for (operator in listOf("mixx-tg", "tmoney-tg", "moov-tg")) {
            assertEquals(operator, parseKkiapayTransaction(evidence().put("source_common_name", operator), "tx-123").operator)
        }
        for (operator in listOf("mtn-benin", "moov-bj", "moov-ci", "mixx-sn", "visa", "", "unknown")) {
            assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("source_common_name", operator), "tx-123") }
        }
        for (source in listOf("CARD", "WALLET", "")) {
            assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("source", source), "tx-123") }
        }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("type", "CREDIT"), "tx-123") }
    }
    @Test fun malformedOrAmbiguousProviderEvidenceFailsClosed() {
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence(), "another-id") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("amount", 1.5), "tx-123") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("amount", "1500"), "tx-123") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("amount", 0), "tx-123") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("currency", "EUR"), "tx-123") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(evidence().put("partnerId", "contradiction"), "tx-123") }
        val noBinding = evidence().apply { remove("externalTransactionId") }
        assertFailsWith<IllegalArgumentException> { parseKkiapayTransaction(noBinding, "tx-123") }
    }
    @Test fun credentialsAloneCannotEnableLiveCollection() {
        fun config(enabled: Boolean = true, sandbox: Boolean = false, approved: Boolean = false, base: String = "https://api.sequoservice.com") =
            KkiapayConfiguration(enabled, sandbox, approved, "synthetic-public-123456", "synthetic-private-123456", "synthetic-secret-123456", "synthetic-webhook-12345678901234567890", base)
        assertFalse(config().ready())
        assertFalse(config(enabled = false, sandbox = true).ready())
        assertTrue(config(sandbox = true).ready())
        assertTrue(config(approved = true).ready())
        for (base in listOf("http://api.sequoservice.com", "https://user:password@example.com", "https://example.com?token=abc", "https://example.com/path")) {
            assertFalse(config(approved = true, base = base).ready())
        }
        assertFalse(KkiapayConfiguration(true, true, false, "", "", "", "", "https://api.sequoservice.com").ready())
    }
}
