create table delivery_problem_resolutions (
    id varchar(255) not null,
    mission_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    action varchar(64) not null,
    replacement_courier_id varchar(255),
    reason varchar(1000) not null,
    resolved_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_problem_resolutions
    add constraint fk_delivery_problem_resolutions_mission
    foreign key (mission_id) references delivery_missions (id);

alter table delivery_problem_resolutions
    add constraint chk_delivery_problem_resolutions_action
    check (action in ('REQUEUE_FOR_DISPATCH', 'CANCEL_MISSION'));

create index idx_delivery_problem_resolutions_mission
    on delivery_problem_resolutions (mission_id, resolved_at);
