-- Baseline schema for the SaaS base (architecture overview, "Rule model" and
-- "Redemption lifecycle"). Every tenant-owned table carries tenant_id; JPA
-- stamps and filters it through @TenantId.
--
-- Pre-release: this file may still be edited in place. Once any environment
-- has applied it, every change goes into a new V<n>__*.sql instead.
--
-- Rows are never deleted: history tables are append-only and every reference
-- to campaign/campaign_version is ON DELETE RESTRICT.

create table tenant (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    time_zone varchar(50) not null,
    currency varchar(3) not null,
    active boolean not null default true,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

-- Global, not tenant-filtered: resolving a request's tenant starts here.
create table client_application (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    client_key varchar(100) not null unique,
    credential_hash varchar(255) not null,
    rate_limit_per_minute int not null default 600,
    active boolean not null default true,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create table client_application_channel (
    client_application_id uuid not null references client_application(id) on delete cascade,
    channel varchar(50) not null,
    primary key (client_application_id, channel)
);

create table campaign (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    name varchar(200) not null,
    status varchar(30) not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create table campaign_version (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    campaign_id uuid not null references campaign(id) on delete restrict,
    version_number int not null,
    status varchar(30) not null,
    raw_definition jsonb not null,
    optional_script text,
    approved_by varchar(100),
    approved_at timestamptz,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (campaign_id, version_number),
    constraint campaign_version_approval_recorded check (
        status in ('DRAFT', 'PENDING_APPROVAL', 'REJECTED')
        or (approved_by is not null and approved_at is not null))
);

-- At most one serving version, one version awaiting approval and one open
-- draft per campaign.
create unique index uq_campaign_version_active
    on campaign_version (campaign_id) where status = 'ACTIVE';
create unique index uq_campaign_version_pending
    on campaign_version (campaign_id) where status = 'PENDING_APPROVAL';
create unique index uq_campaign_version_draft
    on campaign_version (campaign_id) where status = 'DRAFT';

-- Rule content is frozen once a version leaves DRAFT (a change is a new
-- version). The entity enforces this too; this catches native SQL, and
-- refusing the way back to DRAFT closes "flip status, then edit".
--
-- The child-table triggers below (channels, counter_def) check the parent's
-- status when each row is written. Hibernate flushes entity inserts (counter_def)
-- before entity updates, but collection rows (channels) after them, so a flow
-- that edits a draft and moves it out of DRAFT in one transaction must flush
-- the edit before changing status (CampaignService.editDraft does); otherwise
-- the channel write is rejected and the transaction rolls back.
create function reject_frozen_version_content() returns trigger as $$
begin
    if new.campaign_id is distinct from old.campaign_id
       or new.version_number is distinct from old.version_number then
        raise exception 'campaign_version % cannot be moved or renumbered', old.id;
    end if;
    if old.status <> 'DRAFT'
       and (new.status = 'DRAFT'
            or new.raw_definition is distinct from old.raw_definition
            or new.optional_script is distinct from old.optional_script) then
        raise exception 'campaign_version % is %; its content is frozen', old.id, old.status;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_campaign_version_frozen_content
    before update on campaign_version
    for each row execute function reject_frozen_version_content();

create table campaign_version_channel (
    campaign_version_id uuid not null references campaign_version(id) on delete cascade,
    channel varchar(50) not null,
    primary key (campaign_version_id, channel)
);

-- Shared by every table whose rows are part of a version's content. Checks
-- the old parent too when a row is moved (moving it out of a frozen version
-- changes that version).
--
-- Locks the parent FOR NO KEY UPDATE — the lock an UPDATE of it takes anyway —
-- so a concurrent status change waits for this write instead of racing past
-- it, and a transaction that writes children then updates the version never
-- has to upgrade its lock (FOR SHARE would deadlock two such writers). FK
-- checks on the reserve path take KEY SHARE, which this doesn't block.
create function reject_frozen_version_child() returns trigger as $$
declare
    version_id uuid;
    parent_status varchar(30);
begin
    foreach version_id in array array_remove(array[
            case when tg_op = 'DELETE'
                   or (tg_op = 'UPDATE' and old.campaign_version_id is distinct from new.campaign_version_id)
                 then old.campaign_version_id end,
            case when tg_op <> 'DELETE' then new.campaign_version_id end], null) loop
        select status into parent_status from campaign_version where id = version_id for no key update;
        if found and parent_status <> 'DRAFT' then
            raise exception 'campaign_version % has left DRAFT; its % rows are frozen', version_id, tg_table_name;
        end if;
    end loop;
    return coalesce(new, old);
end;
$$ language plpgsql;

create trigger trg_campaign_version_channel_frozen
    before insert or update or delete on campaign_version_channel
    for each row execute function reject_frozen_version_child();

-- Limits as approved in one version: the only place cap/unit/mode live.
create table counter_def (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    campaign_version_id uuid not null references campaign_version(id) on delete restrict,
    name varchar(100) not null,
    scope varchar(20) not null,
    unit varchar(20) not null,
    period varchar(20) not null,
    mode varchar(20) not null,
    -- same bounds as RuleAmount: positive, and small enough that used + amount fits bigint
    cap bigint not null check (cap > 0 and cap <= 1000000000000000),
    sort_order int not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (campaign_version_id, name),
    -- sort_order is the evaluation order (a PARTIAL counter shrinks what the next
    -- one is offered); a tie would make it depend on how rows come back
    unique (campaign_version_id, sort_order)
);

-- The approved cap is version content too: frozen with its version.
create trigger trg_counter_def_frozen
    before insert or update or delete on counter_def
    for each row execute function reject_frozen_version_child();

-- Usage per (campaign, name, scope key, period key), carried across versions.
-- `used` only moves through the conditional UPDATE in CounterRepository.
create table counter (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    campaign_id uuid not null references campaign(id) on delete restrict,
    name varchar(100) not null,
    scope_key varchar(200) not null default '',
    period_key varchar(20) not null default '',
    used bigint not null default 0 check (used >= 0),
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (campaign_id, name, scope_key, period_key)
);

create table redemption (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    request_id varchar(100) not null,
    campaign_id uuid not null references campaign(id) on delete restrict,
    rule_version_id uuid not null references campaign_version(id) on delete restrict,
    user_key varchar(200) not null,
    transaction_ref varchar(100) not null,
    status varchar(20) not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    unique (tenant_id, request_id)
);

-- Keyed per tenant: the same requestId from two tenants must never share a stored response.
create table idempotency_key (
    tenant_id uuid not null references tenant(id),
    request_id varchar(100) not null,
    endpoint varchar(100) not null,
    request_hash varchar(64) not null,
    response_body text not null,
    created_at timestamptz not null default now(),
    primary key (tenant_id, request_id, endpoint)
);

create table shedlock (
    name varchar(64) not null primary key,
    lock_until timestamptz not null,
    locked_at timestamptz not null,
    locked_by varchar(255) not null
);

create index idx_campaign_tenant on campaign(tenant_id);
create index idx_campaign_version_campaign on campaign_version(campaign_id);
create index idx_counter_def_version on counter_def(campaign_version_id);
create index idx_counter_campaign on counter(campaign_id);
create index idx_redemption_tenant on redemption(tenant_id);
create index idx_redemption_transaction_ref on redemption(transaction_ref);
