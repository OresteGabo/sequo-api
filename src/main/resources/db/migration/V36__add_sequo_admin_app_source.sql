alter table user_devices drop constraint if exists chk_user_devices_app_source;
alter table user_devices
    add constraint chk_user_devices_app_source
    check (app_source in ('SEQUO_APP', 'SEQUO_HUB', 'SEQUO_RIDER', 'SEQUO_ADMIN'));

alter table auth_challenges drop constraint if exists chk_auth_challenges_requesting_app;
alter table auth_challenges
    add constraint chk_auth_challenges_requesting_app
    check (
        requesting_app_source is null
        or requesting_app_source in ('SEQUO_APP', 'SEQUO_HUB', 'SEQUO_RIDER', 'SEQUO_ADMIN')
    );
