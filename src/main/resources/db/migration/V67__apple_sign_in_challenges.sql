alter table auth_challenges drop constraint chk_auth_challenges_purpose;
alter table auth_challenges add constraint chk_auth_challenges_purpose
    check (purpose in ('WHATSAPP_OTP', 'PASSKEY_REGISTRATION', 'PASSKEY_AUTHENTICATION', 'CROSS_DEVICE_LOGIN', 'APPLE_SIGN_IN'));
