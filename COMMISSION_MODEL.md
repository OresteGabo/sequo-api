# Commission Model

The commission model determines how Sequo earns money from merchant sales and how merchants are paid. This is separate from customer delivery pricing, wallet provider payments, and settlement execution.

## Authoritative Rules

- Default merchant commission is 15%.
- Admin can configure a merchant-specific rate from 5% to 15%.
- Commission changes are audited and apply only to future pricing/order snapshots unless an admin correction is explicitly posted.
- Listing price equals `(base price + platform margin) + service fees + delivery fee`.
- Platform margin is tracked separately from commission.
- Cooperative orders are split by underlying merchant item ownership.

## Money Components

| Component | Description | Ledger treatment |
| --- | --- | --- |
| `base_price` | Merchant's product amount before commission | Merchant gross sale basis |
| `platform_margin` | Sequo markup added to item listing | Sequo revenue |
| `service_fee` | Operational/service fee charged to customer | Sequo revenue or provider cost recovery |
| `delivery_fee` | Customer-facing delivery charge | Delivery revenue or courier cost offset |
| `merchant_commission` | Sequo percentage retained from merchant base amount | Sequo revenue |
| `merchant_net` | Base amount minus commission and merchant-responsible adjustments | Merchant payable |
| `delivery_shortfall` | Courier fee not covered by customer delivery fee | Sequo expense |

## Commission Rate Resolution

```text
if merchant.commission_rate is set:
  rate = merchant.commission_rate
else:
  rate = 15%

validate 5% <= rate <= 15%
```

Rate examples:

| Merchant config | Effective rate |
| --- | ---: |
| No override | 15% |
| Override 5% | 5% |
| Override 12% | 12% |
| Override 15% | 15% |
| Override 20% | Invalid |

## Item Settlement Formula

For each order item:

```text
merchant_base = effective_base_amount
platform_margin = effective_platform_margin
commission_rate = resolved_merchant_commission_rate

merchant_commission = round(merchant_base * commission_rate)
merchant_net_before_adjustments = merchant_base - merchant_commission
sequo_item_revenue = platform_margin + merchant_commission
```

If no bargaining was used:

```text
effective_base_amount = base_price
effective_platform_margin = platform_margin
```

If bargaining was used, the pricing engine allocates the discount and stores effective values on the order item.

## Order-Level Settlement

```text
merchant_gross = sum(effective_base_amount for merchant's items)
merchant_commission = sum(item merchant_commission)
merchant_refund_liability = sum(refunds assigned to merchant)
merchant_holds = sum(active dispute holds)
merchant_net_payable = merchant_gross - merchant_commission - merchant_refund_liability - merchant_holds
```

Sequo revenue:

```text
sequo_revenue =
  sum(platform_margin)
  + sum(merchant_commission)
  + service_fees
  + relay_commission
  - delivery_shortfalls
  - sequo_responsible_refunds
  - provider_fees_not_recovered
```

## Platform Margin

Platform margin is an amount added to merchant base price before customer-facing service and delivery fees.

Rules:

- Stored per product/variant or policy.
- Included in listing price.
- Attributed to Sequo revenue.
- Discounted first when a bargaining offer is accepted.
- Must be visible to internal finance/admin reporting.

## Service Fees

Service fees cover operational costs such as wallet provider fees, SMS, WhatsApp, support, maps, and routing. They may be embedded in listing price or shown separately, but the database must store them as separate pricing components.

## Cooperative Market Split

For a cooperative such as Marche de Mulhouse:

| Step | Rule |
| --- | --- |
| 1 | Customer sees cooperative storefront and may receive one consolidated package |
| 2 | Each product keeps its true merchant owner |
| 3 | Checkout creates merchant sub-orders per member |
| 4 | Commission rate resolves per member merchant |
| 5 | Refund liability resolves per item owner unless Sequo/courier caused the issue |
| 6 | Payout is generated per member, not as one pooled merchant payment |

Cooperative reporting can show aggregate revenue, but individual member financial details are visible only to that member and authorized admins.

## Merchant Payout Schedule

Business rule: merchant payout is scheduled within 1 week of package receipt.

Recommended interpretation:

- Package receipt means confirmed delivery to customer, confirmed customer pickup, or Sequo-confirmed physical receipt in the relevant workflow.
- Eligible amounts may be held during the 72-hour return window if risk policy requires.
- Returns, refunds, disputes, and chargebacks can hold or reduce payable amounts.
- Payout batch records must include order IDs, item IDs, commission rate snapshots, refund deductions, and wallet provider references.

## Refund Liability

| Cause | Default liability |
| --- | --- |
| Wrong item, wrong size, inaccurate merchant catalog | Merchant |
| Damaged in Sequo custody | Sequo |
| Lost by courier | Courier or Sequo, depending on courier contract |
| Customer remorse within policy | Configurable policy |
| Fraud or abuse | Support/admin decision with audit |

When merchant is liable:

```text
merchant_refund_liability += refund_amount
merchant_net_payable -= refund_amount
```

If the merchant already received payout, the amount becomes a negative adjustment against future payouts or an admin-managed recovery.

## Delivery Shortfalls

If customer pays less than the courier fee:

```text
delivery_shortfall = courier_fee - customer_delivery_fee
```

Rules:

- Shortfall is a Sequo expense ledger entry.
- Shortfall does not reduce merchant payout by default.
- Shortfall is visible in admin finance reporting.
- Repeated shortfalls should feed pricing analytics.

## Required Ledger Entries

Each paid order should generate accounting entries for:

- Customer payment received.
- Merchant gross sale.
- Platform margin.
- Merchant commission.
- Service fee.
- Delivery fee collected.
- Courier payable.
- Relay payable if applicable.
- Delivery shortfall if applicable.
- Merchant payable.
- Return hold if applicable.
- Refund liability if applicable.

## Admin Controls

Admin can:

- Set merchant commission within 5% to 15%.
- Configure platform margin policies.
- Configure service fee policies.
- Place and release settlement holds.
- Correct settlement amounts through explicit adjustment entries.
- Approve payout batches.

Admin cannot:

- Mutate historical ledger entries.
- Change paid order snapshots silently.
- Configure commission outside allowed bounds without code/config policy change.
- Execute cash withdrawal operations.

## Required Tests

- Default commission equals 15%.
- Merchant override lower bound 5%.
- Merchant override upper bound 15%.
- Invalid commission rejected.
- Platform margin is separate from commission.
- Bargaining reduces margin first.
- Cooperative split pays each member separately.
- Merchant refund liability reduces payout.
- Delivery shortfall posts as Sequo expense.
- Commission change affects future orders only.

