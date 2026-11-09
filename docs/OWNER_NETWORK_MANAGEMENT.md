# Owner management of the SEQUO network

Owners can manage vendors, riders, hubs and personnel from the admin website.
Operations and Staff managers retain their existing permissions. The additional
account creation and profile lifecycle workspaces are Owner-only, enforced by
both the interface and current persisted server authority.

## Where to work

| Record | Add | Edit | Remove / restore |
| --- | --- | --- | --- |
| Personnel and administrators | Users → Add personnel account | Select Accounts → Edit personnel profile | Account access and roles → Suspend / Restore personnel access; grant or revoke individual roles |
| Vendors | Add an account with Vendor owner role, then use the existing merchant enrollment form in Merchants | Select vendor → Edit / remove vendor; Store settings handles location and hours | Vendor profile and removal → Remove / Restore vendor to service |
| Riders | Add an account with Rider role, then use the existing rider registration form | Select rider → Edit / remove rider for vehicle; Riders and workforce for workforce classification; Users for name | Rider profile and removal → Remove / Restore rider to service |
| Hubs | Relay points → existing hub setup form; assign an authorized relay operator | Hub setup handles name, address and slots; Relay sites and assigned operators handles personnel assignment | Hub profile and removal → Remove / Restore hub to service |

Existing list views remain the starting workspace. Select a record to open its
related profile editor. Review the displayed details, choose an action, enter a
reason, and confirm. The interface retains the exact server revision and retry
key when a response is uncertain; refresh and inspect history before changing a
saved intent.

## New personnel

Use the exact approved email or international phone number and an explicit
personnel role. Administrator tiers require an email for website email-code
sign-in. Business personnel can use a phone identity for normal application
sign-in. A contact already belonging to an account produces a conflict: load
that account and manage its roles instead. Accounts are never silently merged.

Creating a record sends no invitation and issues no login session. The person
must prove control of their contact through normal authentication. Owner creation
does not reactivate an existing suspended, deleted or pending identity. Existing
profile editing changes display names, shop names or vehicles; credential email
and phone changes still require the identity verification workflows.

Registering a shop or rider profile remains separate from its account role. Shop
location, hours and hub operators must be configured using the existing forms.
No addresses, earnings, wallets or past activity are invented.

## Removal and ongoing work

Removing a vendor, rider or hub sets its operational profile to INACTIVE. Its
identity and historical orders, earnings, parcels and actor records remain.
Restoration requires an active linked account with the appropriate personnel role.

- Vendor removal waits for open merchant orders and pending customer checkouts.
- Rider removal or vehicle changes wait for missions still requiring the rider.
- Hub removal waits for unresolved parcels, incoming/active relay missions and
  pending customer checkouts. Checkout, deposit and reroute writers take the
  same hub-row lock so removal cannot race a newly selected destination.
- Vendor checkout and rider assignment use their existing shared profile-row locks.

Suspending a person's account is a separate action that blocks authentication
and revokes prior sessions. Existing safeguards still protect sole vendor owners,
assigned hub operators, self access changes, and the last active SEQUO Owner.
Revoking a role does not erase the person's operational history.

## History and deployment

New creation/profile changes save actor, target, action, before/after snapshots,
reason, retry identity and timestamp in owner_directory_operations. Account
creation also records the grant in admin_access_changes. The existing Activity
journal records authenticated request attempts and outcomes. V65 adds the new
history table and its append-only protection without editing earlier migrations.

No production personnel, vendor, rider or hub records need to be changed to
deploy these controls. Account provisioning requires an explicit Owner action.
