-- Metadata only. No accounts, orders, payment evidence or fixtures are seeded by migration.
create table local_operational_fixture_runs (
    namespace varchar(64) primary key,
    seeded_at timestamp with time zone,
    manifest_json text
);
insert into local_operational_fixture_runs(namespace) values ('local-ops-v1');
