# Partner onboarding and applicant submissions

Vendors, riders and hubs can now be registered from their own admin pages.
The contact/account step and operational profile are handled together; the
reviewer no longer needs to visit Users first.

## Staff workflow

Owners and Staff managers see **Add a vendor**, **Add a rider**, or **Add a hub**
on Merchants, Riders, or Relay points. Operations retains its existing operational
permissions and cannot register partners or review applications.

1. Choose **Add directly** and enter the person's contact and partner details.
2. Review the preview. A matching existing account is shown automatically; its
   identity fields are retained. Otherwise, an account will be created.
3. Confirm the reason and registration. Account, business role, profile and audit
   records commit together. **Open settings** opens the shop availability editor,
   rider details or hub setup/storage controls.

Only the business roles MERCHANT_OWNER, COURIER and RELAY_PARTNER can be granted
by this workflow. Staff managers cannot target administrator accounts. Self
registration by a reviewer is denied. Administrator appointment remains Owner-only
through the existing account-role interface.

Existing suspended, deleted or pending identities are not reactivated. Conflicting
email/phone matches cannot be merged. New login contacts are not attached to an
existing identity without its normal verification flow. The preview is bound to
the exact account revision so concurrent signups or edits require a fresh review.

## Applicants fill their own forms

The public website has French and English forms on:

| Partner | French | English |
| --- | --- | --- |
| Vendor | /commercants#partner-application | /en/merchants#partner-application |
| Rider | /livreurs#partner-application | /en/riders#partner-application |
| Hub | /devenir-point-relais#partner-application | /en/become-a-relay-point#partner-application |

Applicants provide their name, a reachable email or international phone, city,
neighborhood and partner details. Riders select a vehicle. Shops/hubs provide a
business name and can optionally share coordinates. Device location is requested
only after the applicant clicks the location button and permits it. No identity
documents, passwords, personal codes or bank details are requested.

Submitting stores a **PENDING application only**. It creates no account, role,
merchant, rider or hub, and sends no automatic email/SMS. The response contains an
opaque receipt, not private application details. The receipt can be used when
contacting SEQUO. There is no public directory or contact/status lookup.

Submission details are saved in the applicant's browser tab only once they submit,
so a lost response can be retried with the same key; this copy is cleared after
confirmation. The public privacy notice describes the database processing and
optional geolocation.

## Review and validation

Each admin partner page has an **Applications** inbox with pending, approved and
declined filters. Selecting an application pre-fills the registration form.
Reviewers can correct the approval copy, add a reason, and approve or decline.
The applicant's original submission remains unchanged and its history remains
visible. Hub approval requires confirmed coordinates. Rider employment/workforce
classification is selected by staff; applicants cannot grant themselves salaried
status or administrator roles.

Approval performs the same atomic registration as the staff direct flow. Decline
records a decision without creating any authority or profile. Competing approvals
or rejection serialize through the existing access-administration mutex and the
application lock. One terminal decision wins; a stale decision returns HTTP 409.
An already reviewed application cannot be silently reopened.

New or changed roles invalidate prior sessions. The approved person signs in
normally to the appropriate application to obtain current access. Shop setup,
catalogue publication and hub storage positions retain their existing controls;
onboarding does not invent opening hours, capacity, earnings or payouts.

## Persistence, retries and history

Migration V66 adds partner_applications and the append-only
partner_onboarding_operations table. Operations record submitted/reviewed details,
actor, reason, application/identity/profile links and timestamps. Original public
submission actors are anonymous claims, not verified identities. Role grants also
use admin_access_changes, existing enrollment/relay histories remain intact, and
authenticated requests continue to use the Activity journal.

The same review key and identical data return the original operation, including
after a lost response. A changed actor, reason, target or payload with that key
returns a conflict. Public and staff retry keys have separate namespaces. A failed
profile or hub assignment rolls back its new identity, role, sessions and audit
changes together. Stored before/after operational history is retained.

API routes:

- POST /api/partner-applications — anonymous submission with explicit acknowledgement.
- POST /api/admin/web/onboarding/preview — automatic account match and normalized details.
- POST /api/admin/web/onboarding/{VENDOR|RIDER|HUB} — direct registration.
- GET /api/admin/web/onboarding/operations/{key} — recover a saved staff result.
- GET /api/admin/web/partner-applications?kind=…&state=… — private review queue.
- GET /api/admin/web/partner-applications/{id} — private original details and history.
- POST /api/admin/web/partner-applications/{id}/approve or /reject — guarded review.

Public submissions have the general API limit plus a separate in-memory limit of
30 submissions per observed network peer per hour. Application fields are bounded.
Only the official public website origins are added to production CORS; protected
review APIs still require current Staff-manager/Owner authority. No sending or
payment credentials are exposed to either website.

## Validation before deployment

Run PartnerOnboardingHttpTest plus the existing enrollment, account-access, Owner
directory, relay administration, checkout and custody tests. Build both websites;
run the admin partner, permission/session and Owner-directory checks and public
application payload checks. Validate V66 and an anonymous-submission-to-approval
cycle on an isolated PostgreSQL copy before production. Never edit already-applied
Flyway migrations.
