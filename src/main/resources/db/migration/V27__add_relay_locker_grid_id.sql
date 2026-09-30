alter table relay_lockers
    add column relay_locker_grid_id varchar(255);

update relay_lockers
set relay_locker_grid_id = relay_point_id
where relay_locker_grid_id is null;

alter table relay_lockers
    alter column relay_locker_grid_id set not null;

create index idx_relay_lockers_grid
    on relay_lockers (relay_locker_grid_id);
