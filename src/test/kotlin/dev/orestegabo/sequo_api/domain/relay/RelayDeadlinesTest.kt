package dev.orestegabo.sequo_api.domain.relay

import java.time.Instant
import kotlin.test.*

class RelayDeadlinesTest {
    @Test fun exactSignedContractBoundariesAndUnknownLegacyPolicy() {
        val deposit = Instant.parse("2026-09-01T00:00:00Z")
        val parcel = (RelayParcelService().createParcel(RelayParcelCreateCommand("boundary", "site", orderId = "order",
            category = RelayParcelCategory.GeneralGoods, depositCode = "deposit", availableLockers = listOf(RelayLocker("slot", "site", true, false)), createdAt = deposit)) as RelayParcelServiceResult.Accepted).value.parcel
        val eight = deposit.plusSeconds(8L * 86400); val thirty = deposit.plusSeconds(30L * 86400)
        assertFalse(parcel.deadlines(eight.minusNanos(1))!!.storageFeeEligible)
        assertFalse(parcel.deadlines(eight)!!.storageFeeEligible)
        assertTrue(parcel.deadlines(eight.plusNanos(1))!!.storageFeeEligible)
        assertFalse(parcel.deadlines(thirty.minusNanos(1))!!.reviewDue)
        assertTrue(parcel.deadlines(thirty)!!.reviewDue)
        assertTrue(parcel.deadlines(thirty.plusNanos(1))!!.reviewDue)
        assertFalse(parcel.copy(feeStartsAt = null).deadlines(thirty)!!.storageFeeEligible)
        assertFalse(parcel.copy(feeStartsAt = null).deadlines(thirty)!!.feePolicyKnown)
        assertFalse(parcel.copy(status = RelayParcelStatus.PickedUp, pickedUpAt = thirty).deadlines(thirty.plusSeconds(1))!!.reviewDue)
    }
}
