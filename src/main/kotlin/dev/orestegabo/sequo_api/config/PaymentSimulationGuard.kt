package dev.orestegabo.sequo_api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PaymentSimulationGuard(
    @Value("\${sequo.payments.simulator.enabled:false}") enabled: Boolean,
) {
    init {
        require(!enabled) {
            "Runtime payment simulation is unsupported. Run the isolated fixtures from the test source set."
        }
    }
}
