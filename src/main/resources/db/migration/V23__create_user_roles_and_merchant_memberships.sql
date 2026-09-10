create table user_roles (
    user_id varchar(255) not null,
    role_code varchar(255) not null,
    primary key (user_id, role_code)
);

alter table user_roles
    add constraint fk_user_roles_user
    foreign key (user_id) references users (id);

alter table user_roles
    add constraint chk_user_roles_role_code
    check (role_code in (
        'CUSTOMER',
        'MERCHANT_OWNER',
        'MERCHANT_STAFF',
        'COURIER',
        'RELAY_PARTNER',
        'SUPPORT_AGENT',
        'ADMIN',
        'SUPER_ADMIN'
    ));

insert into user_roles (user_id, role_code)
select id, 'CUSTOMER'
from users;

create table merchant_memberships (
    id varchar(255) not null,
    user_id varchar(255) not null,
    merchant_id varchar(255) not null,
    role_code varchar(255) not null,
    active boolean not null default true,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table merchant_memberships
    add constraint fk_merchant_memberships_user
    foreign key (user_id) references users (id);

alter table merchant_memberships
    add constraint uk_merchant_memberships_user_merchant_role
    unique (user_id, merchant_id, role_code);

alter table merchant_memberships
    add constraint chk_merchant_memberships_role_code
    check (role_code in ('MERCHANT_OWNER', 'MERCHANT_STAFF'));

create index idx_merchant_memberships_user_active
    on merchant_memberships (user_id, active);

create index idx_merchant_memberships_merchant_active
    on merchant_memberships (merchant_id, active);
