-- Extend new operational history without changing applied V53 or inventing legacy reasons.
alter table admin_party_changes drop constraint chk_admin_party_domain;
alter table admin_party_changes add constraint chk_admin_party_domain
    check (domain in ('COURIER_WORKFORCE', 'RELAY_OPERATOR', 'DELIVERY_MISSION', 'COURIER_AVAILABILITY'));
alter table admin_party_changes drop constraint chk_admin_party_action;
alter table admin_party_changes add constraint chk_admin_party_action
    check (action in ('SET_WORKFORCE', 'ASSIGN_OPERATOR', 'REMOVE_OPERATOR', 'ASSIGN_COURIER',
        'REASSIGN_COURIER', 'OFFER_MISSION', 'UNPAUSE_COURIER', 'ASSIGN_POLICY'));
