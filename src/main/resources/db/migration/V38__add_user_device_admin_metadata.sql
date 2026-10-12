alter table user_devices
    add column if not exists device_label varchar(255);

alter table user_devices
    add column if not exists browser_info varchar(255);

