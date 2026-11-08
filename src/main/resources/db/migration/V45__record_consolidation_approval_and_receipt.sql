alter table consolidation_manifests add column approved_by_user_id varchar(255) references users(id);
alter table consolidation_manifests add column cooperative_id varchar(128) references merchant_cooperatives(id);
alter table consolidation_manifests add column history_json text;
alter table merchant_cooperatives add column review_reason text;
alter table merchant_cooperatives add column review_history_json text;
alter table delivery_missions drop constraint chk_delivery_missions_status;
alter table delivery_missions add constraint chk_delivery_missions_status check (status in (
 'CREATED','OFFERED_TO_COURIER','ACCEPTED_BY_COURIER','PICKED_UP_FROM_SELLER','HANDED_TO_SEQUO',
 'DEPOSITED_AT_RELAY','DELIVERED_TO_CUSTOMER','RELEASED_BY_RELAY','PROBLEM_REPORTED','CANCELLED'));
