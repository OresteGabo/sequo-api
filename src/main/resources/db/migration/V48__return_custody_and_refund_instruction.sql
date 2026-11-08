-- Preserve legacy receipt/refund fields; missing custody and inspection evidence is not backfilled.
alter table return_requests drop constraint chk_return_requests_status;
alter table return_requests add constraint chk_return_requests_status check (status in (
    'Requested', 'AwaitingRelayDropoff', 'DroppedAtRelay', 'CollectedBySequo',
    'ReceivedBySequo', 'RefundPending', 'Rejected'
));
alter table return_requests add column collected_by_sequo_at timestamp with time zone;
alter table return_requests add column collecting_operator_id varchar(255);
alter table return_requests add column inspection_decision varchar(64);
alter table return_requests add column inspected_at timestamp with time zone;
alter table return_requests add column inspecting_operator_id varchar(255);
alter table return_requests add column refund_instruction_id varchar(255) unique;
alter table return_requests add column refund_instructed_at timestamp with time zone;
alter table return_requests add column refund_instructing_operator_id varchar(255);
alter table return_requests add column custody_history_json text not null default '[]';
alter table return_requests add column receipt_condition_assessment varchar(1000);
alter table return_requests add column receipt_responsibility varchar(64);
-- Copy only existing recorded receipt context; do not infer an inspection decision or actor/time.
update return_requests set receipt_condition_assessment = condition_assessment,
    receipt_responsibility = responsibility where received_by_sequo_at is not null;
alter table return_requests add constraint chk_return_inspection_decision
    check (inspection_decision is null or inspection_decision in ('ACCEPTED', 'REJECTED'));
alter table return_requests add constraint chk_return_refund_instruction_evidence check (
    refund_instruction_id is null or (
        status = 'RefundPending' and received_by_sequo_at is not null
        and inspection_decision is not null and inspection_decision = 'ACCEPTED' and inspected_at is not null
        and inspecting_operator_id is not null
        and refund_instructed_at is not null and refund_instructing_operator_id is not null
        and refund_amount_cfa > 0 and refund_idempotency_key is not null
    )
);
