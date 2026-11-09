package dev.orestegabo.sequo_api.domain.platform

import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationSelection
import java.math.BigDecimal
import java.math.RoundingMode

enum class ProductOptionType { PREPARATION, SIZE, COLOR, MEASURE, OTHER }
enum class ProductMeasureUnit(val symbol: String) { KG("kg"), G("g"), L("L"), ML("mL"), M("m"), CM("cm"), UNIT("pc") }

internal fun parseProductMeasureAmount(raw: String?): BigDecimal {
    require(raw != null && raw.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,3})?"))) { "Choose a positive amount with at most three decimal places." }
    val amount = raw.toBigDecimal()
    require(amount > BigDecimal.ZERO && amount <= BigDecimal("1000")) { "Measurement amount must be greater than zero and at most 1000 units." }
    return amount
}

/** CFA is integral. Apply one documented half-up rounding per portion before multiplying cart portions. */
internal fun priceForProductMeasure(baseCfa: Int, amount: String?): Int {
    val value = BigDecimal.valueOf(baseCfa.toLong()).multiply(amount?.let(::parseProductMeasureAmount) ?: BigDecimal.ONE)
        .setScale(0, RoundingMode.HALF_UP)
    require(value > BigDecimal.ZERO && value <= BigDecimal.valueOf(Int.MAX_VALUE.toLong())) { "Selected amount has an invalid or excessive price." }
    return value.intValueExact()
}

internal fun selectedProductMeasurement(groups: List<ProductCustomizationGroupDto>, selections: List<FoodCustomizationSelection>): Pair<String, ProductMeasureUnit>? {
    val measured = groups.filter { it.active && it.type == ProductOptionType.MEASURE }
    require(measured.size <= 1) { "A product can have only one active measurement group." }
    val group = measured.singleOrNull() ?: return null
    val selection = selections.singleOrNull { it.groupId == group.id }
    require(selection?.optionIds?.size == 1) { "Choose one available measured amount." }
    val option = group.options.singleOrNull { it.id in selection.optionIds && it.active }
    val amount = parseProductMeasureAmount(option?.measureAmount).stripTrailingZeros().toPlainString()
    return amount to requireNotNull(group.unit) { "Measurement pricing unit is missing." }
}

internal fun ProductCustomizationGroupDto.optionLabel(option: ProductCustomizationOptionDto): String = when (type) {
    ProductOptionType.MEASURE -> {
        val amount = "${requireNotNull(option.measureAmount)} ${requireNotNull(unit).symbol}"
        if (option.name.replace(" ", "").equals(amount.replace(" ", ""), true)) amount else "${option.name} ($amount)"
    }
    ProductOptionType.PREPARATION -> option.name
    else -> "$name: ${option.name}"
}
