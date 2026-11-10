package dev.orestegabo.sequo_api.domain.account

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import org.springframework.http.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.sql.*
import java.time.*
import java.util.zip.*

private const val MAX_ROWS = 10000
private const val MAX_ARCHIVE_BYTES = 48 * 1024 * 1024
private val mapper = ObjectMapper().findAndRegisterModules()

data class AccountExportRequest(val devicePreferences: Map<String, String>? = null)

@RestController
@RequestMapping("/api/account")
class AccountDataController(private val exports: AccountDataExportService, private val limiter: InMemoryRateLimiter) {
    @PostMapping("/data-export", produces = ["application/zip"])
    fun download(@AuthenticationPrincipal userId: String?, @RequestBody request: AccountExportRequest): ResponseEntity<ByteArray> {
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val decision = limiter.consume("account-export", userId, RateLimitRule(3, Duration.ofHours(1)))
        if (!decision.allowed) throw RateLimitExceededException(decision.retryAfterSeconds)
        val bytes = exports.archive(userId, request.devicePreferences.orEmpty())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache").header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "attachment; filename=\"sequo-data-${LocalDate.now(ZoneOffset.UTC)}.zip\"")
            .contentType(MediaType.parseMediaType("application/zip")).body(bytes)
    }

    @GetMapping("/deletion-requests")
    fun deletionStatus(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.deletions(userId))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(error: ResponseStatusException): ResponseEntity<Void> = ResponseEntity.status(error.statusCode).cacheControl(CacheControl.noStore()).build()
}

/** Explicit field and ownership lists: a new DB secret is never automatically included in an archive. */
@Service
class AccountDataExportService(private val jdbc: JdbcTemplate, private val users: UserRepository) {
    private val ownedOrders = "select id from customer_orders where customer_id=?"
    private val ownedThreads = "select id from bargaining_threads where customer_id=?"

    @Transactional(readOnly = true)
    fun deletions(userId: String): List<Map<String, Any?>> = rows("account_deletion_requests", "id,status,requested_at,reviewed_at", "user_id=?", userId, "requested_at desc,id desc").map {
        mapOf("id" to it["id"], "status" to it["status"], "requestedAt" to it["requested_at"], "reviewedAt" to it["reviewed_at"])
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun archive(userId: String, devicePreferences: Map<String, String>): ByteArray {
        val user = users.findById(userId).orElse(null)
        if (user == null || !user.active || !user.status.canAuthenticate()) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val sections = linkedMapOf<String, Any>()
        fun add(name: String, table: String, fields: String, ownership: String = "user_id=?") {
            sections[name] = rows(table, fields, ownership, userId)
        }
        add("Account", "users", "id,email,phone_number,display_name,avatar_url,provider,status,created_at,updated_at", "id=?")
        add("Roles", "user_roles", "role_code")
        add("Profile", "customer_profiles", "display_name,phone_number,receipt_email,default_address_id,marketing_opt_in,created_at,updated_at")
        add("Saved addresses", "customer_addresses", "id,kind,label,recipient_name,phone_number,city,area,street_hint,latitude,longitude,active,created_at,updated_at")
        add("App preferences", "user_app_preferences", "app_family,theme,language,quick_scan_on_open,sound_feedback,large_locker_labels,created_at,updated_at")
        add("Notification preferences", "notification_preferences", "app_family,event_type,push_enabled,in_app_enabled,sms_enabled,quiet_hours_start,quiet_hours_end,created_at,updated_at")
        add("Sign-in providers", "user_identities", "provider,provider_user_id,created_at,last_login_at")
        add("Devices", "user_devices", "device_id,app_source,created_at,last_active_at,expires_at,revoked_at")
        add("Cart", "customer_cart_items", "id,product_id,quantity,selections_json,bargaining_thread_id,created_at,updated_at")
        add("Checkouts", "pending_payment_checkouts", "checkout_id,payment_provider,payment_reference,amount_cfa,status,request_payload,created_at,updated_at,completed_at", "customer_id=?")
        add("Orders", "customer_orders", "id,checkout_id,service_level,route,customer_facing_status,order_status,item_subtotal_cfa,delivery_fee_cfa,total_cfa,payment_provider,payment_status,payment_reference,created_at,updated_at,delivered_at,return_window_ends_at", "customer_id=?")
        add("Order items", "customer_order_lines", "order_id,product_id,seller_name,product_name,category,quantity,unit_price_cfa,negotiated_unit_price_cfa,effective_unit_price_cfa,line_total_cfa,option_label,created_at", "order_id in ($ownedOrders)")
        add("Order totals", "order_pricing_snapshots", "order_id,currency,item_subtotal_cfa,service_fee_total_cfa,delivery_base_fee_cfa,subscription_discount_cfa,loyalty_discount_cfa,referral_credit_applied_cfa,customer_delivery_fee_cfa,total_cfa,created_at", "customer_id=?")
        add("Shop preparation", "merchant_sub_orders", "order_id,sub_order_code,status,package_count,accepted_at,preparing_at,packed_ready_at,handed_to_courier_at,rejection_reason,created_at,updated_at", "order_id in ($ownedOrders)")
        add("Delivery history", "delivery_missions", "order_id,delivery_code,delivery_mode,destination_type,status,assigned_at,accepted_at,pickup_at,relay_deposited_at,delivered_at,created_at,updated_at", "order_id in ($ownedOrders)")
        add("Relay parcels", "relay_parcels", "id,relay_point_id,order_id,status,deposited_at,picked_up_at,collected_at,created_at,updated_at", "order_id in ($ownedOrders)")
        add("Order events", "order_events", "order_id,event_type,source_type,created_at", "order_id in ($ownedOrders)")
        add("Pickup confirmations", "customer_pickup_confirmations", "order_id,proof_metadata,confirmed_at", "customer_id=?")
        add("Relay storage charges", "relay_storage_fee_assessments", "relay_parcel_id,daily_fee_cfa,chargeable_days,total_fee_cfa,fee_starts_at,measured_until,created_at,updated_at", "relay_parcel_id in (select id from relay_parcels where order_id in ($ownedOrders))")
        add("Notification delivery status", "notification_deliveries", "message_id,channel,status,attempt_count,sent_at,created_at,updated_at", "message_id in (select id from notification_messages where recipient_user_id=?)")
        add("Notification devices", "device_fcm_tokens", "device_id,app_family,platform,app_version,locale,timezone,status,last_seen_at,revoked_at,created_at,updated_at")
        add("Returns", "return_requests", "id,order_id,status,reason,requested_refund_cfa,condition_assessment,responsibility,refund_amount_cfa,created_at,updated_at", "customer_id=?")
        add("Negotiations", "bargaining_threads", "id,merchant_id,product_id,status,opened_at,closed_at,expires_at,last_offer_cfa,accepted_price_cfa", "customer_id=?")
        add("Negotiation messages", "bargaining_offers", "thread_id,actor_type,offer_type,amount_cfa,message,created_at", "thread_id in ($ownedThreads)")
        add("Notifications", "notification_messages", "app_family,event_type,severity,title,body,read_at,archived_at,created_at", "recipient_user_id=?")
        add("Subscriptions", "customer_subscriptions", "id,plan_id,status,started_at,current_period_ends_at,cancel_at_period_end,created_at,updated_at")
        add("Referral credits", "referral_delivery_credits", "id,credit_cfa,remaining_cfa,status,created_at,expires_at", "referrer_user_id=?")
        add("Delivery benefits", "delivery_benefit_applications", "checkout_id,subscription_discount_cfa,loyalty_discount_cfa,credit_applied_cfa,status,created_at,applied_at,released_at", "customer_id=?")
        add("Deletion requests", "account_deletion_requests", "id,status,reason,requested_at,reviewed_at")
        sections["Preferences on this device"] = devicePreferences.filterKeys { it in setOf("language", "theme", "largerText", "tapFeedback", "pinnedCategories", "categoryUsage") }.mapValues { (_, v) -> v.take(4000) }

        val attachments = mutableListOf<Map<String, Any?>>()
        val photos = linkedMapOf<String, ByteArray>()
        val products = jdbc.queryForList("""select distinct p.id,p.name,p.image_url from products p where p.id in
            (select product_id from customer_order_lines where order_id in ($ownedOrders)) or p.id in
            (select product_id from customer_cart_items where user_id=?) limit ${MAX_ROWS + 1}""", userId, userId)
        requireSize(products.size <= MAX_ROWS)
        var photoBytes = 0L
        for (product in products) {
            val id = product["id"].toString()
            // Bytes come only from owned-order/cart product joins, never from a client-supplied URL.
            val bytes = jdbc.query("select jpeg from catalog_camera_payloads where product_id=?", { rs, _ -> rs.getBytes(1) }, id).firstOrNull()
            val path = "attachments/product-${java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)}.jpg"
            if (bytes != null) {
                photoBytes += bytes.size
                requireSize(photoBytes <= MAX_ARCHIVE_BYTES / 2)
                photos[path] = bytes
            }
            attachments += mapOf("productId" to id, "name" to product["name"], "path" to if (bytes != null) path else null,
                "sourceUrl" to product["image_url"], "status" to if (bytes != null) "included" else "reference-only: no stored file available")
        }
        sections["Attachment index"] = attachments
        val document = linkedMapOf("formatVersion" to 1, "exportedAt" to Instant.now().toString(), "scope" to "Sequo customer account", "sections" to sections)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document)
        requireSize(json.size + photoBytes <= MAX_ARCHIVE_BYTES / 2)
        val html = report(sections)
        val text = json.toString(Charsets.UTF_8)
        requireSize(json.size.toLong() + html.toByteArray().size + text.toByteArray().size + photoBytes <= MAX_ARCHIVE_BYTES)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry("START_HERE.html", html.toByteArray(Charsets.UTF_8))
                entry("data.json", json)
                entry("data.txt", text.toByteArray(Charsets.UTF_8))
                entry("README.txt", """SEQUO — YOUR DATA / VOS DONNÉES
Open START_HERE.html for a readable report. data.json is reusable structured data; data.txt is plain UTF-8 text.
Ouvrez START_HERE.html pour le rapport. data.json contient les données structurées ; data.txt est en texte UTF-8.
Attachments are original stored product photos referenced by your cart/orders. The attachment index lists included files and reference-only images. Customer file uploads are not currently supported.
This is customer-app data, not a merchant/hub/rider business workspace export. Authentication secrets, active pickup/PIN credentials, internal fraud/security records and other people's private information are excluded. Catalog images may be current rather than historical.
Preferences on this device are supplied by this app; server records are authoritative. Temporary in-memory UI state, browser cookies and the phone's system settings are not account records.
This ZIP contains personal data. Keep it in a private location. Use your browser's Print function to save the readable report as PDF if desired.
If an account is too large for the 48 MiB interactive export, the API fails explicitly instead of silently omitting rows; request an assisted export.
""".toByteArray(Charsets.UTF_8))
                photos.forEach { (name, bytes) -> entry(name, bytes) }
            }
            output.toByteArray()
        }
    }

    private fun rows(table: String, columns: String, ownership: String, userId: String, orderBy: String = ""): List<Map<String, Any?>> {
        val raw = jdbc.queryForList("select $columns from $table where $ownership ${if (orderBy.isBlank()) "" else "order by $orderBy"} limit ${MAX_ROWS + 1}", userId)
        requireSize(raw.size <= MAX_ROWS)
        return raw.map { row -> row.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }.mapValues { (_, value) -> when (value) {
            is Clob -> value.getSubString(1, value.length().toInt())
            is java.util.Date, is java.time.temporal.TemporalAccessor -> value.toString()
            else -> value
        } } }
    }

    private fun requireSize(ok: Boolean) { if (!ok) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "An assisted export is required; no partial archive was generated.") }

    private fun report(sections: Map<String, Any>): String = buildString {
        append("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:"><title>Your Sequo data · Vos données Sequo</title><style>body{font:16px system-ui,sans-serif;background:#f5f8f6;color:#203d31;margin:0;padding:24px}main{max-width:960px;margin:auto}h1{font-size:32px}h2{margin-top:40px}article{background:white;border:1px solid #d5e4dc;border-radius:16px;padding:18px;margin:12px 0}dl{margin:0}dt{font-weight:600;margin-top:10px}dd{margin:4px 0;white-space:pre-wrap;overflow-wrap:anywhere;color:#39594b}a{color:#16724a}nav{display:flex;flex-wrap:wrap;gap:12px}small{color:#526d60}@media print{body{background:white}article{break-inside:avoid}}</style><main><h1>SEQUO · Your data / Vos données</h1><p>A private copy of your customer account. Une copie privée de votre compte client.</p><p>Open data.txt for plain text or data.json for reusable data. See README.txt for scope and exclusions.</p><nav>""")
        sections.keys.forEachIndexed { i, name -> append("<a href=\"#section-$i\">${escape(name)}</a>") }
        append("</nav>")
        sections.entries.forEachIndexed { i, (name, value) ->
            append("<section id=\"section-$i\"><h2>${escape(name)}</h2>")
            val records = if (value is List<*>) value else listOf(value)
            if (records.isEmpty()) append("<p>No records / Aucune donnée</p>")
            records.forEach { record ->
                append("<article><dl>")
                if (record is Map<*, *>) record.forEach { (field, fieldValue) ->
                    append("<dt>${escape(field.toString().replace('_', ' '))}</dt><dd>${escape(fieldValue?.toString() ?: "—")}</dd>")
                } else append("<dd>${escape(record.toString())}</dd>")
                append("</dl>")
                if (record is Map<*, *> && record["status"] == "included") append("<p><a href=\"${escape(record["path"].toString())}\">Open attachment / Ouvrir la pièce jointe</a></p>")
                append("</article>")
            }
            append("</section>")
        }
        append("</main></html>")
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
