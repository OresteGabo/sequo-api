package dev.orestegabo.sequo_api.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodCustomizationServiceTest {

    private val service = FoodCustomizationService()

    @Test
    fun acceptsRequiredAndOptionalFoodChoicesAndBuildsOrderSnapshot() {
        val result = service.validateAndSnapshot(
            menu = attiekeMenu(),
            selections = listOf(
                FoodCustomizationSelection("sauce", setOf("spicy")),
                FoodCustomizationSelection("toppings", setOf("egg", "extra-fish")),
            ),
        )

        assertTrue(result is FoodCustomizationResult.Accepted)
        assertEquals("attieke-poisson", result.snapshot.productId)
        assertEquals(450, result.snapshot.totalPriceDeltaCfa)
        assertEquals(
            listOf("Spicy sauce", "Boiled egg", "Extra fish"),
            result.snapshot.groups.flatMap { group -> group.selectedOptions.map { it.label } },
        )
    }

    @Test
    fun rejectsMissingRequiredChoiceBeforeCheckoutPayment() {
        val result = service.validateAndSnapshot(
            menu = attiekeMenu(),
            selections = listOf(
                FoodCustomizationSelection("toppings", setOf("egg")),
            ),
        )

        assertTrue(result is FoodCustomizationResult.Rejected)
        assertTrue(result.errors.any { it.code == "missing_required_selection" })
    }

    @Test
    fun rejectsTooManySingleChoiceOptions() {
        val result = service.validateAndSnapshot(
            menu = attiekeMenu(),
            selections = listOf(
                FoodCustomizationSelection("sauce", setOf("mild", "spicy")),
            ),
        )

        assertTrue(result is FoodCustomizationResult.Rejected)
        assertTrue(result.errors.any { it.code == "too_many_selections" })
    }

    @Test
    fun rejectsUnavailableAndUnknownOptions() {
        val result = service.validateAndSnapshot(
            menu = attiekeMenu(),
            selections = listOf(
                FoodCustomizationSelection("sauce", setOf("spicy")),
                FoodCustomizationSelection("toppings", setOf("sold-out-avocado", "unknown")),
            ),
        )

        assertTrue(result is FoodCustomizationResult.Rejected)
        assertTrue(result.errors.any { it.code == "unavailable_option" })
        assertTrue(result.errors.any { it.code == "unknown_option" })
    }

    @Test
    fun rejectsUnknownAndDuplicateGroups() {
        val result = service.validateAndSnapshot(
            menu = attiekeMenu(),
            selections = listOf(
                FoodCustomizationSelection("sauce", setOf("spicy")),
                FoodCustomizationSelection("sauce", setOf("mild")),
                FoodCustomizationSelection("unknown-group", setOf("x")),
            ),
        )

        assertTrue(result is FoodCustomizationResult.Rejected)
        assertTrue(result.errors.any { it.code == "duplicate_group_selection" })
        assertTrue(result.errors.any { it.code == "unknown_group" })
    }

    private fun attiekeMenu(): FoodCustomizationMenu =
        FoodCustomizationMenu(
            productId = "attieke-poisson",
            groups = listOf(
                FoodCustomizationGroup(
                    id = "sauce",
                    label = "Sauce level",
                    type = FoodCustomizationGroupType.RequiredSingleChoice,
                    options = listOf(
                        FoodCustomizationOption("mild", "Mild sauce"),
                        FoodCustomizationOption("spicy", "Spicy sauce", priceDeltaCfa = 100),
                    ),
                ),
                FoodCustomizationGroup(
                    id = "toppings",
                    label = "Toppings",
                    type = FoodCustomizationGroupType.OptionalMultiChoice,
                    maxSelections = 2,
                    options = listOf(
                        FoodCustomizationOption("egg", "Boiled egg", priceDeltaCfa = 150),
                        FoodCustomizationOption("extra-fish", "Extra fish", priceDeltaCfa = 200),
                        FoodCustomizationOption("sold-out-avocado", "Avocado", priceDeltaCfa = 100, available = false),
                    ),
                ),
            ),
        )
}
