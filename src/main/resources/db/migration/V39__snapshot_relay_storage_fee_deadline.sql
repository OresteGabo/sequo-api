-- Leave pre-upgrade deposits unassigned until SEQUO approves their fee treatment.
alter table relay_parcels add column fee_starts_at timestamp with time zone;
