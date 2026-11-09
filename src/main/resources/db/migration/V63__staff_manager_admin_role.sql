-- Add an opt-in staff-management role. No account is promoted or reassigned.
alter table user_roles drop constraint chk_user_roles_role_code;
alter table user_roles add constraint chk_user_roles_role_code check (role_code in (
    'CUSTOMER', 'MERCHANT_OWNER', 'MERCHANT_STAFF', 'COURIER', 'RELAY_PARTNER',
    'SUPPORT_AGENT', 'ADMIN', 'STAFF_MANAGER', 'SUPER_ADMIN'
));
