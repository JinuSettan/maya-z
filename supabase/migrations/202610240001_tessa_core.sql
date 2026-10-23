-- TESSA Turbo V1.2 initial schema.
-- Apply only to the dedicated tessa-turbo-v12 Supabase project after review.
-- No credentials or service-role keys belong in this file.

create extension if not exists pgcrypto;

create table if not exists public.tessa_tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 200),
  description text not null default '' check (char_length(description) <= 12000),
  status text not null default 'queued'
    check (status in ('queued', 'running', 'waiting_approval', 'succeeded', 'failed', 'cancelled')),
  result_summary text not null default '' check (char_length(result_summary) <= 12000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists tessa_tasks_user_created_idx
  on public.tessa_tasks (user_id, created_at desc);

create table if not exists public.tessa_memories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  scope text not null check (char_length(scope) between 1 and 120),
  kind text not null check (kind in ('preference', 'fact', 'lesson', 'task_outcome')),
  content text not null check (char_length(content) between 1 and 4000),
  source text not null default 'user_feedback' check (char_length(source) <= 300),
  consent boolean not null default false,
  status text not null default 'candidate' check (status in ('candidate', 'approved', 'rejected')),
  confidence real not null default 0.5 check (confidence >= 0 and confidence <= 1),
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  reviewed_at timestamptz
);

create index if not exists tessa_memories_user_scope_status_idx
  on public.tessa_memories (user_id, scope, status);

create table if not exists public.tessa_tool_approvals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  task_id uuid references public.tessa_tasks(id) on delete set null,
  tool_name text not null check (char_length(tool_name) between 1 and 100),
  action_summary text not null check (char_length(action_summary) between 1 and 2000),
  arguments jsonb not null default '{}'::jsonb,
  status text not null default 'pending' check (status in ('pending', 'approved', 'denied', 'expired', 'executed')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '15 minutes'),
  decided_at timestamptz
);

create index if not exists tessa_tool_approvals_user_status_idx
  on public.tessa_tool_approvals (user_id, status, created_at desc);

alter table public.tessa_tasks enable row level security;
alter table public.tessa_memories enable row level security;
alter table public.tessa_tool_approvals enable row level security;

drop policy if exists "Users manage own TESSA tasks" on public.tessa_tasks;
create policy "Users manage own TESSA tasks"
  on public.tessa_tasks for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "Users manage own TESSA memories" on public.tessa_memories;
create policy "Users manage own TESSA memories"
  on public.tessa_memories for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "Users manage own TESSA approvals" on public.tessa_tool_approvals;
create policy "Users manage own TESSA approvals"
  on public.tessa_tool_approvals for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

grant select, insert, update, delete on public.tessa_tasks to authenticated;
grant select, insert, update, delete on public.tessa_memories to authenticated;
grant select, insert, update, delete on public.tessa_tool_approvals to authenticated;
