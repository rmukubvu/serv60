-- Workshop execution: the stages a confirmed visit passes through on the floor, and extra work
-- proposed to the customer. Additive; older application versions ignore both tables.
create table visit_stage_event (
  id uuid primary key,
  hold_id uuid not null references visit_hold(id),
  stage varchar(24) not null,
  note varchar(300),
  recorded_by varchar(200) not null,
  recorded_at timestamptz not null,
  -- Each stage happens once per visit; a retry of the same stage is a conflict, not a duplicate.
  unique (hold_id, stage)
);
create index visit_stage_event_visit on visit_stage_event(hold_id, recorded_at);

create table scope_change (
  id uuid primary key,
  hold_id uuid not null references visit_hold(id),
  status varchar(16) not null check (status in ('PROPOSED', 'APPROVED', 'DECLINED')),
  items jsonb not null check (jsonb_typeof(items) = 'array'),
  note varchar(500),
  total_minor bigint not null check (total_minor >= 0),
  currency varchar(3) not null default 'ZAR',
  proposed_by varchar(200) not null,
  proposed_at timestamptz not null,
  decided_at timestamptz
);
create index scope_change_visit on scope_change(hold_id, proposed_at);
