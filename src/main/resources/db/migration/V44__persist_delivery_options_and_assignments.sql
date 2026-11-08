alter table delivery_missions add column delivery_option_payload text;
alter table delivery_missions add column subscriber_priority boolean not null default false;
alter table delivery_missions add column assignment_workforce_type varchar(64);
alter table delivery_missions add column assignment_reason text;
alter table delivery_missions add column freelancer_fee_estimate_cfa integer;
