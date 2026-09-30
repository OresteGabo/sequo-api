alter table delivery_missions
    add column pickup_proof_actor_id varchar(255);

alter table delivery_missions
    add column dropoff_proof_actor_id varchar(255);

alter table delivery_missions
    add column relay_deposit_proof_metadata text;

alter table delivery_missions
    add column relay_deposit_proof_actor_id varchar(255);
