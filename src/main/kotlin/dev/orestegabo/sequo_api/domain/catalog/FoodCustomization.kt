package dev.orestegabo.sequo_api.domain.catalog

enum class FoodCustomizationGroupType {
    RequiredSingleChoice,
    OptionalSingleChoice,
    OptionalMultiChoice,
}

data class FoodCustomizationOption(
    val id: String,
    val label: String,
    val priceDeltaCfa: Int = 0,
    val available: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "option id is required." }
        require(label.isNotBlank()) { "option label is required." }
        require(priceDeltaCfa >= 0) { "option price delta cannot be negative." }
    }
}

data class FoodCustomizationGroup(
    val id: String,
    val label: String,
    val type: FoodCustomizationGroupType,
    val options: List<FoodCustomizationOption>,
    val minSelections: Int = when (type) {
        FoodCustomizationGroupType.RequiredSingleChoice -> 1
        FoodCustomizationGroupType.OptionalSingleChoice,
        FoodCustomizationGroupType.OptionalMultiChoice -> 0
    },
    val maxSelections: Int = when (type) {
        FoodCustomizationGroupType.RequiredSingleChoice,
        FoodCustomizationGroupType.OptionalSingleChoice -> 1
        FoodCustomizationGroupType.OptionalMultiChoice -> options.size
    },
) {
    init {
        require(id.isNotBlank()) { "group id is required." }
        require(label.isNotBlank()) { "group label is required." }
        require(options.isNotEmpty()) { "group must contain at least one option." }
        require(options.map { it.id }.toSet().size == options.size) { "option ids must be unique in a group." }
        require(minSelections >= 0) { "min selections cannot be negative." }
        require(maxSelections >= minSelections) { "max selections must be greater than or equal to min selections." }
        require(maxSelections <= options.size) { "max selections cannot exceed available options." }
    }
}

data class FoodCustomizationMenu(
    val productId: String,
    val groups: List<FoodCustomizationGroup>,
) {
    init {
        require(productId.isNotBlank()) { "product id is required." }
        require(groups.map { it.id }.toSet().size == groups.size) { "group ids must be unique in a menu." }
    }
}

data class FoodCustomizationSelection(
    val groupId: String,
    val optionIds: Set<String>,
) {
    init {
        require(groupId.isNotBlank()) { "group id is required." }
    }
}

data class FoodCustomizationOptionSnapshot(
    val optionId: String,
    val label: String,
    val priceDeltaCfa: Int,
)

data class FoodCustomizationGroupSnapshot(
    val groupId: String,
    val label: String,
    val selectedOptions: List<FoodCustomizationOptionSnapshot>,
)

data class FoodCustomizationSnapshot(
    val productId: String,
    val groups: List<FoodCustomizationGroupSnapshot>,
) {
    val totalPriceDeltaCfa: Int =
        groups.sumOf { group -> group.selectedOptions.sumOf { it.priceDeltaCfa } }
}

data class FoodCustomizationValidationError(
    val code: String,
    val message: String,
)

sealed class FoodCustomizationResult {
    data class Accepted(val snapshot: FoodCustomizationSnapshot) : FoodCustomizationResult()
    data class Rejected(val errors: List<FoodCustomizationValidationError>) : FoodCustomizationResult()
}

class FoodCustomizationService {
    fun validateAndSnapshot(
        menu: FoodCustomizationMenu,
        selections: List<FoodCustomizationSelection>,
    ): FoodCustomizationResult {
        val errors = mutableListOf<FoodCustomizationValidationError>()
        val selectionsByGroup = selections.associateBy { it.groupId }

        if (selectionsByGroup.size != selections.size) {
            errors += FoodCustomizationValidationError(
                code = "duplicate_group_selection",
                message = "Each customization group can be submitted only once.",
            )
        }

        val knownGroupIds = menu.groups.map { it.id }.toSet()
        selections
            .filter { it.groupId !in knownGroupIds }
            .forEach {
                errors += FoodCustomizationValidationError(
                    code = "unknown_group",
                    message = "Customization group ${it.groupId} does not exist for product ${menu.productId}.",
                )
            }

        val groupSnapshots = menu.groups.map { group ->
            val selectedOptionIds = selectionsByGroup[group.id]?.optionIds.orEmpty()
            validateGroupSelection(group, selectedOptionIds, errors)
            FoodCustomizationGroupSnapshot(
                groupId = group.id,
                label = group.label,
                selectedOptions = selectedOptionIds.mapNotNull { optionId ->
                    group.options.firstOrNull { it.id == optionId }?.let { option ->
                        FoodCustomizationOptionSnapshot(
                            optionId = option.id,
                            label = option.label,
                            priceDeltaCfa = option.priceDeltaCfa,
                        )
                    }
                },
            )
        }

        if (errors.isNotEmpty()) return FoodCustomizationResult.Rejected(errors)

        return FoodCustomizationResult.Accepted(
            FoodCustomizationSnapshot(
                productId = menu.productId,
                groups = groupSnapshots,
            )
        )
    }

    private fun validateGroupSelection(
        group: FoodCustomizationGroup,
        selectedOptionIds: Set<String>,
        errors: MutableList<FoodCustomizationValidationError>,
    ) {
        if (selectedOptionIds.size < group.minSelections) {
            errors += FoodCustomizationValidationError(
                code = "missing_required_selection",
                message = "${group.label} requires at least ${group.minSelections} selection(s).",
            )
        }
        if (selectedOptionIds.size > group.maxSelections) {
            errors += FoodCustomizationValidationError(
                code = "too_many_selections",
                message = "${group.label} allows at most ${group.maxSelections} selection(s).",
            )
        }

        val optionsById = group.options.associateBy { it.id }
        selectedOptionIds.forEach { optionId ->
            val option = optionsById[optionId]
            when {
                option == null -> errors += FoodCustomizationValidationError(
                    code = "unknown_option",
                    message = "Option $optionId does not exist in ${group.label}.",
                )
                !option.available -> errors += FoodCustomizationValidationError(
                    code = "unavailable_option",
                    message = "Option ${option.label} is not currently available.",
                )
            }
        }
    }
}
