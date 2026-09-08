alter table relay_parcels
    add column category varchar(64) not null default 'GeneralGoods';
