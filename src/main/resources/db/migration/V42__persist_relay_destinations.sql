-- Existing parcels and home missions remain unchanged; no inferred assignment or rerouting.
alter table relay_points add column latitude double precision;
alter table relay_points add column longitude double precision;
alter table delivery_missions add column relay_point_id varchar(255) references relay_points(id);
alter table delivery_missions add column relay_destination_payload text;
alter table delivery_missions add column relay_reroute_key varchar(128);
alter table delivery_missions add column relay_reroute_actor_id varchar(255) references users(id);
alter table delivery_missions add column relay_reroute_reason text;
alter table delivery_missions add column relay_rerouted_at timestamp with time zone;
