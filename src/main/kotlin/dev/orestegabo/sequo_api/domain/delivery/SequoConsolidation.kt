package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant

enum class ConsolidationStatus {
    AwaitingSellerPackages,
    ReadyForSequoPickup,
    InSequoCustody,
    Consolidated,
    Dispatched,
    Cancelled,
}

enum class ConsolidationEvent {
    SellerPackageReady,
    SequoCollectsAllPackages,
    SequoCreatesFinalPackage,
    DispatchesFinalPackage,
    Cancel,
}

data class ConsolidationSellerPackage(
    val subOrderId: String,
    val merchantId: String,
    val packageCount: Int,
    val ready: Boolean,
) {
    init {
        require(subOrderId.isNotBlank()) { "subOrderId must not be blank." }
        require(merchantId.isNotBlank()) { "merchantId must not be blank." }
        require(packageCount > 0) { "packageCount must be positive." }
    }
}

data class SequoConsolidationManifest(
    val manifestId: String,
    val orderId: String,
    val customerId: String,
    val sellerPackages: List<ConsolidationSellerPackage>,
    val status: ConsolidationStatus = ConsolidationStatus.AwaitingSellerPackages,
    val finalPackageId: String? = null,
    val sequoCustodyAt: Instant? = null,
) {
    init {
        require(manifestId.isNotBlank()) { "manifestId must not be blank." }
        require(orderId.isNotBlank()) { "orderId must not be blank." }
        require(customerId.isNotBlank()) { "customerId must not be blank." }
        require(sellerPackages.isNotEmpty()) { "At least one seller package is required." }
        require(sellerPackages.map { it.subOrderId }.distinct().size == sellerPackages.size) {
            "A consolidation manifest cannot contain duplicate sub-orders."
        }
        require(sellerPackages.map { it.merchantId }.distinct().size == sellerPackages.size) {
            "Each seller must have one package entry in the manifest."
        }
        if (status == ConsolidationStatus.Consolidated || status == ConsolidationStatus.Dispatched) {
            require(!finalPackageId.isNullOrBlank()) { "A final package ID is required after consolidation." }
        }
        if (status == ConsolidationStatus.InSequoCustody || status == ConsolidationStatus.Consolidated || status == ConsolidationStatus.Dispatched) {
            require(sequoCustodyAt != null) { "Sequo custody time is required after collection." }
        }
    }

    val allSellerPackagesReady: Boolean
        get() = sellerPackages.all { it.ready }

    val totalPackageCount: Int
        get() = sellerPackages.sumOf { it.packageCount }
}

data class ConsolidationTransitionRequest(
    val manifest: SequoConsolidationManifest,
    val event: ConsolidationEvent,
    val at: Instant = Instant.now(),
    val finalPackageId: String? = null,
)

data class ConsolidationTransition(
    val accepted: Boolean,
    val manifest: SequoConsolidationManifest,
    val reason: String,
)

class SequoConsolidationService {
    fun transition(request: ConsolidationTransitionRequest): ConsolidationTransition {
        require(!request.at.toString().isBlank()) { "Transition time is required." }
        val manifest = request.manifest
        if (manifest.status == ConsolidationStatus.Cancelled) {
            return rejected(manifest, "Consolidation is already cancelled.")
        }
        if (manifest.status == ConsolidationStatus.Dispatched) {
            return rejected(manifest, "Consolidation has already been dispatched.")
        }
        if (request.event == ConsolidationEvent.Cancel) {
            return accepted(manifest.copy(status = ConsolidationStatus.Cancelled), "Consolidation cancelled.")
        }

        return when (manifest.status) {
            ConsolidationStatus.AwaitingSellerPackages -> when (request.event) {
                ConsolidationEvent.SellerPackageReady -> {
                    if (manifest.allSellerPackagesReady) {
                        accepted(manifest.copy(status = ConsolidationStatus.ReadyForSequoPickup), "All seller packages are ready for Sequo pickup.")
                    } else {
                        rejected(manifest, "Every seller package must be ready before Sequo pickup.")
                    }
                }
                else -> rejected(manifest, "The manifest is waiting for every seller package to be ready.")
            }
            ConsolidationStatus.ReadyForSequoPickup -> when (request.event) {
                ConsolidationEvent.SequoCollectsAllPackages -> accepted(
                    manifest.copy(status = ConsolidationStatus.InSequoCustody, sequoCustodyAt = request.at),
                    "Sequo took custody of all seller packages.",
                )
                else -> rejected(manifest, "All seller packages must be collected before consolidation.")
            }
            ConsolidationStatus.InSequoCustody -> when (request.event) {
                ConsolidationEvent.SequoCreatesFinalPackage -> {
                    if (request.finalPackageId.isNullOrBlank()) {
                        rejected(manifest, "A final package ID is required for consolidation.")
                    } else {
                        accepted(manifest.copy(status = ConsolidationStatus.Consolidated, finalPackageId = request.finalPackageId), "Sequo consolidated the seller packages into one customer package.")
                    }
                }
                else -> rejected(manifest, "Packages in Sequo custody must be consolidated before dispatch.")
            }
            ConsolidationStatus.Consolidated -> when (request.event) {
                ConsolidationEvent.DispatchesFinalPackage -> accepted(manifest.copy(status = ConsolidationStatus.Dispatched), "The consolidated package is ready for final delivery.")
                else -> rejected(manifest, "The consolidated package is waiting for final dispatch.")
            }
            ConsolidationStatus.ReadyForSequoPickup,
            ConsolidationStatus.InSequoCustody,
            ConsolidationStatus.Consolidated,
            ConsolidationStatus.Cancelled,
            ConsolidationStatus.Dispatched -> rejected(manifest, "This consolidation transition is not allowed.")
        }
    }

    private fun accepted(manifest: SequoConsolidationManifest, reason: String) =
        ConsolidationTransition(true, manifest, reason)

    private fun rejected(manifest: SequoConsolidationManifest, reason: String) =
        ConsolidationTransition(false, manifest, reason)
}
