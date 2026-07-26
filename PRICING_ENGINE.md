# Pricing Engine

The pricing engine calculates what the customer sees before confirming an order and what the backend stores as the immutable price snapshot after payment. All money values are integer CFA amounts. Do not use floating point for money.

## Pricing Principles

- Pricing is server-side only.
- Every checkout quote records input data, formula version, configuration version, and calculated output.
- Pricing can be recalculated for drafts, but paid order items keep their original snapshot.
- Discounts must never make a payable line negative.
- Subscription and loyalty discounts apply only to eligible per-km delivery fees unless a tier explicitly configures broader benefits.
- Older legacy pricing values are superseded by the current 400 CFA minimum and 100 CFA/km rule.

## Currency And Rounding

| Rule | Decision |
| --- | --- |
| Currency | CFA |
| Storage | Integer amount in CFA |
| Distance | Calculate from provider result, store raw meters and billable km |
| Billable km | Round up to the next whole km for delivery pricing |
| Percent discount | Round final discounted fee to nearest whole CFA using half-up rounding |
| Minimum | Standard delivery fee cannot be below 400 CFA before subscription/loyalty policy unless configured otherwise |

## Standard Delivery Formula

Business rule:

- Standard economic rate: 100 CFA/km.
- Minimum price: 400 CFA for trips up to and including 5 km.
- Extra distance: 100 CFA per extra billable km after 5 km.

Formula:

```text
billable_km = ceil(distance_km)

if billable_km <= 5:
  delivery_fee = 400
else:
  delivery_fee = 400 + ((billable_km - 5) * 100)
```

Examples:

| Distance | Billable km | Delivery fee |
| --- | ---: | ---: |
| 1.2 km | 2 | 400 CFA |
| 5.0 km | 5 | 400 CFA |
| 5.1 km | 6 | 500 CFA |
| 9.4 km | 10 | 900 CFA |
| 20.0 km | 20 | 1,900 CFA |

## Subscription Discounts

Subscriptions are monthly tiers that apply percentage discounts to eligible per-km delivery fees.

Data model:

| Field | Meaning |
| --- | --- |
| `tier_code` | Stable tier identifier |
| `monthly_price_cfa` | Monthly subscription bill |
| `per_km_discount_percent` | Percentage reduction applied to eligible per-km fee |
| `eligible_delivery_modes` | Modes covered by tier |
| `max_discount_cfa_per_month` | Optional safety cap |
| `is_active` | Whether new customers can subscribe |

Discount formula:

```text
subscription_discount = eligible_delivery_fee * per_km_discount_percent
fee_after_subscription = eligible_delivery_fee - subscription_discount
```

If no active subscription exists, the discount is 0.

## Multi-Year Loyalty Multipliers

Loyalty multipliers further reduce eligible per-km fees after the monthly subscription discount. They are configured by tenure bracket.

Data model:

| Field | Meaning |
| --- | --- |
| `min_months_active` | Minimum paid tenure |
| `multiplier` | Decimal multiplier applied to discounted eligible delivery fee |
| `max_discount_cfa_per_order` | Optional cap |

Formula:

```text
fee_after_subscription = standard_delivery_fee - subscription_discount
fee_after_loyalty = fee_after_subscription * loyalty_multiplier
```

Examples of valid multipliers:

| Multiplier | Meaning |
| ---: | --- |
| `1.00` | No loyalty discount |
| `0.95` | 5% additional discount |
| `0.90` | 10% additional discount |

Exact tier percentages and loyalty multipliers are configuration data, not hard-coded constants.

## Final Delivery Fee Formula

```text
standard_fee = standard_delivery_fee(distance)
subscription_discount = resolve_subscription_discount(customer, standard_fee)
after_subscription = max(0, standard_fee - subscription_discount)
after_loyalty = round(after_subscription * loyalty_multiplier)
customer_delivery_fee = max(0, after_loyalty)
```

The original courier fee must still be recorded. If the customer delivery fee is lower than the courier fee, the settlement engine posts a Sequo shortfall expense.

```text
delivery_shortfall = max(0, courier_fee - customer_delivery_fee)
```

## Listing Price Formula

Business rule:

```text
Listing price = (Base price + platform margin) + service fees + delivery fee
```

Backend components:

| Component | Owner | Notes |
| --- | --- | --- |
| `base_price` | Merchant | Merchant's pre-commission product amount |
| `platform_margin` | Sequo | Explicit Sequo margin added to listed item price |
| `service_fee` | Sequo | Operational fees such as wallet, SMS, provider, support, routing |
| `delivery_fee` | Sequo/courier | Customer-facing delivery fee from pricing engine |

For multi-item orders:

```text
item_subtotal = sum(base_price + platform_margin for each item)
service_fees = calculate_service_fees(item_subtotal, order_context)
delivery_fee = calculate_delivery_fee(order_context)
customer_total = item_subtotal + service_fees + delivery_fee
```

## Bargained Price Handling

If a customer uses an accepted bargaining lock:

```text
effective_item_price = accepted_price
```

Allocation rule:

1. Reduce platform margin first.
2. If accepted price is still below `base_price`, reduce merchant base amount only because the merchant explicitly accepted that price.
3. Store accepted price lock ID on the order item.
4. Preserve original base price, original margin, effective base amount, effective margin, and bargaining discount in the order snapshot.

## Service Fees

Service fees should be configurable by policy:

| Type | Example |
| --- | --- |
| Percentage | 3% of eligible item subtotal |
| Fixed | 100 CFA per order |
| Provider | Wallet provider fee recovery |
| Notification | SMS/WhatsApp cost recovery |

Service fees can be shown as a line item or embedded into the listing price depending on product policy. The backend must still store each component separately for accounting.

## Pricing Snapshot

Each quote and order stores:

- Pricing version.
- Customer ID.
- Merchant IDs.
- Cooperative ID if applicable.
- Product/variant IDs.
- Base price.
- Platform margin.
- Bargaining lock ID if used.
- Service fee policy.
- Raw distance meters.
- Billable km.
- Delivery fee before discounts.
- Subscription tier and discount.
- Loyalty multiplier and discount.
- Customer delivery fee.
- Courier fee estimate if available.
- Delivery shortfall estimate.
- Final customer total.

## Failure Rules

| Case | Result |
| --- | --- |
| Missing delivery address | Quote rejected |
| Unsupported wallet provider | Checkout rejected |
| Expired bargaining lock | Requote required |
| Merchant suspended | Quote rejected |
| Product out of stock | Quote rejected or item removed by customer action |
| Subscription expired | Requote without subscription discount |
| Distance unavailable | Quote rejected unless admin fallback policy exists |

## Required Tests

- Distance under, equal to, and over 5 km.
- Rounding from meters to billable km.
- Minimum 400 CFA behavior.
- Subscription discount with and without cap.
- Loyalty multiplier order of operations.
- Bargaining accepted price allocation.
- Multi-merchant cooperative package quote.
- Customer delivery fee lower than courier fee creates shortfall estimate.
- Quote immutability after order payment.

