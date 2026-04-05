create table workflow_instance (
    id uuid primary key,
    tenant_id varchar(100) not null,
    workflow_definition_id varchar(100) not null,
    work_item_system varchar(100) not null,
    work_item_key varchar(255) not null,
    phase varchar(64) not null,
    status varchar(64) not null,
    current_blocker_id uuid null,
    context_json jsonb null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create unique index uq_workflow_instance_work_item
    on workflow_instance (work_item_system, work_item_key);

create table workflow_blocker (
    id uuid primary key,
    workflow_id uuid not null references workflow_instance(id),
    phase varchar(64) not null,
    type varchar(64) not null,
    reason_code varchar(255) null,
    summary text not null,
    suggested_comment text null,
    requested_from varchar(64) not null,
    resume_trigger varchar(64) not null,
    details_json jsonb null,
    opened_at timestamptz not null,
    resolved_at timestamptz null,
    status varchar(64) not null
);

create index idx_workflow_blocker_workflow
    on workflow_blocker (workflow_id, status);

create table workflow_event (
    id uuid primary key,
    workflow_id uuid not null references workflow_instance(id),
    event_type varchar(128) not null,
    event_payload_json jsonb null,
    occurred_at timestamptz not null,
    source_system varchar(100) null,
    source_event_id varchar(255) null
);

create index idx_workflow_event_workflow
    on workflow_event (workflow_id, occurred_at desc);

create table external_reference (
    id uuid primary key,
    workflow_id uuid not null references workflow_instance(id),
    ref_type varchar(64) not null,
    system varchar(100) not null,
    external_id varchar(255) not null,
    url text null,
    metadata_json jsonb null,
    created_at timestamptz not null
);

create unique index uq_external_reference
    on external_reference (ref_type, system, external_id);

create table agent_run (
    id uuid primary key,
    workflow_id uuid not null references workflow_instance(id),
    phase varchar(64) not null,
    status varchar(64) not null,
    input_snapshot_json jsonb null,
    provider_run_ref varchar(255) null,
    started_at timestamptz null,
    ended_at timestamptz null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_agent_run_workflow
    on agent_run (workflow_id, created_at desc);

create unique index uq_single_active_agent_run
    on agent_run ((1))
    where status in ('STARTING', 'RUNNING');

create table agent_event (
    id uuid primary key,
    agent_run_id uuid not null references agent_run(id),
    event_id varchar(255) not null,
    event_type varchar(64) not null,
    event_payload_json jsonb null,
    occurred_at timestamptz not null,
    processed_at timestamptz null,
    status varchar(64) not null
);

create unique index uq_agent_event_event_id
    on agent_event (event_id);

create index idx_agent_event_run
    on agent_event (agent_run_id, occurred_at desc);

create table inbox_event (
    id uuid primary key,
    source_system varchar(100) not null,
    source_event_type varchar(128) not null,
    source_event_id varchar(255) null,
    payload_hash varchar(128) null,
    received_at timestamptz not null,
    processed_at timestamptz null,
    status varchar(64) not null
);

create unique index uq_inbox_source_event
    on inbox_event (source_system, source_event_type, source_event_id)
    where source_event_id is not null;

create table external_comment (
    id uuid primary key,
    source_system varchar(100) not null,
    comment_id varchar(255) not null,
    parent_type varchar(100) not null,
    parent_id varchar(255) not null,
    author_id varchar(255) null,
    payload_hash varchar(128) null,
    comment_created_at timestamptz null,
    comment_updated_at timestamptz null,
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null
);

create unique index uq_external_comment
    on external_comment (source_system, comment_id);

create table outbox_command (
    id uuid primary key,
    workflow_id uuid not null references workflow_instance(id),
    agent_run_id uuid null references agent_run(id),
    command_type varchar(64) not null,
    payload_json jsonb null,
    created_at timestamptz not null,
    processed_at timestamptz null,
    updated_at timestamptz not null,
    status varchar(64) not null,
    failure_reason text null
);

create index idx_outbox_command_status
    on outbox_command (status, created_at);
