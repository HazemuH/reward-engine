-- Baseline schema for the SaaS base. Mirrors the model in
-- docs/architecture/overview.md section 14, generalized with tenant_id so
-- one deployment can serve more than one tenant from day one.
--
-- Rows are never deleted (campaign/campaign_version/redemption history is
-- append-only or RESTRICT-only) — see the note on each table below.

create table tenant (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null unique,
    name varchar(200) not null,
    time_zone varchar(50) not null default 'UTC',
    active boolean not null default true,
    created_at timestamptz not null default now()
);

create table client_application (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    client_key varchar(100) not null unique,
    credential_hash varchar(255) not null,
    rate_limit_per_minute int not null default 600,
    active boolean not null default true,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create table client_application_allowed_channels (
    client_application_id uuid not null references client_application(id) on delete cascade,
    allowed_channels varchar(50) not null
);

create table campaign (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    name varchar(200) not null,
    status varchar(30) not null default 'DRAFT',
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

-- campaign is never deleted: RESTRICT, not CASCADE, from everything below.
create table campaign_version (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    campaign_id uuid not null references campaign(id) on delete restrict,
    version_number int not null,
    raw_definition text not null,
    optional_script text,
    approved_by varchar(100) not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (campaign_id, version_number)
);

create table campaign_version_channels (
    campaign_version_id uuid not null references campaign_version(id) on delete cascade,
    channels varchar(50) not null
);

-- The only table whose `used` column may change after insert, and only via
-- the conditional UPDATE in the redemption service — see
-- docs/architecture/overview.md section 09 for the exact statement shape.
create table counter (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    campaign_id uuid not null references campaign(id) on delete restrict,
    name varchar(100) not null,
    scope varchar(20) not null,
    unit varchar(20) not null,
    mode varchar(20) not null,
    scope_key varchar(200) not null default '',
    period_key varchar(20) not null default '',
    cap bigint not null,
    used bigint not null default 0,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (campaign_id, name, scope_key, period_key)
);

create table redemption (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    request_id varchar(100) not null unique,
    campaign_id uuid not null references campaign(id) on delete restrict,
    rule_version_id uuid not null references campaign_version(id) on delete restrict,
    user_key varchar(200) not null,
    transaction_ref varchar(100) not null,
    status varchar(20) not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create table idempotency_key (
    request_id varchar(100) not null,
    endpoint varchar(100) not null,
    request_hash varchar(64) not null,
    response_body text not null,
    created_at timestamptz not null default now(),
    primary key (request_id, endpoint)
);

create table shedlock (
    name varchar(64) not null primary key,
    lock_until timestamptz not null,
    locked_at timestamptz not null,
    locked_by varchar(255) not null
);

create index idx_campaign_tenant on campaign(tenant_id);
create index idx_campaign_version_campaign on campaign_version(campaign_id);
create index idx_counter_campaign on counter(campaign_id);
create index idx_redemption_tenant on redemption(tenant_id);
create index idx_redemption_transaction_ref on redemption(transaction_ref);
