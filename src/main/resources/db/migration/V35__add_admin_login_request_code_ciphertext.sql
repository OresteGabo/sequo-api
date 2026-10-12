alter table admin_login_requests
    add column if not exists code_ciphertext varchar(512);
