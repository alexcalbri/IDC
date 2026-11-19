BEGIN;

-- Boards: a company-wide board is seen by everyone with tareas.view; a private one only by its members
-- and by whoever holds tareas.boards.
CREATE TABLE tareas_boards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    description TEXT NOT NULL DEFAULT '',
    visibility VARCHAR(16) NOT NULL DEFAULT 'company' CHECK (visibility IN ('company', 'private')),
    position INTEGER NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tareas_board_members (
    board_id UUID NOT NULL REFERENCES tareas_boards (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users (id) ON DELETE CASCADE,
    PRIMARY KEY (board_id, user_id)
);

CREATE INDEX tareas_board_members_user_idx ON tareas_board_members (user_id);

-- Groups inside a board ("Esta semana", "Proxima semana", a department...), named freely.
CREATE TABLE tareas_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id UUID NOT NULL REFERENCES tareas_boards (id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL CHECK (btrim(name) <> ''),
    position INTEGER NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    -- Target of the composite foreign key of tasks: a task's group always belongs to the task's board.
    CONSTRAINT tareas_groups_id_board_key UNIQUE (id, board_id)
);

CREATE UNIQUE INDEX tareas_groups_name_idx ON tareas_groups (board_id, lower(name)) WHERE NOT archived;
CREATE INDEX tareas_groups_board_position_idx ON tareas_groups (board_id, position);

-- Tasks. customer_id has no foreign key: the customer is checked when it is written. source and
-- external_ref let another module own a task (one task per source + external_ref).
CREATE TABLE tareas_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id UUID NOT NULL REFERENCES tareas_boards (id) ON DELETE CASCADE,
    group_id UUID NULL,
    title VARCHAR(300) NOT NULL CHECK (btrim(title) <> ''),
    detail TEXT NOT NULL DEFAULT '' CHECK (length(detail) <= 20000),
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'in_progress', 'review', 'done', 'cancelled')),
    priority VARCHAR(8) NOT NULL DEFAULT 'medium' CHECK (priority IN ('urgent', 'high', 'medium', 'low')),
    due_date DATE NULL,
    reviewer_id UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    customer_id UUID NULL,
    source VARCHAR(40) NOT NULL DEFAULT 'manual' CHECK (source ~ '^[a-z][a-z0-9_]{0,39}$'),
    external_ref VARCHAR(200) NULL,
    position INTEGER NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ NULL,
    completed_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ NULL,
    -- Groups are only archived, never deleted, so there is no ON DELETE action.
    CONSTRAINT tareas_tasks_group_fk FOREIGN KEY (group_id, board_id) REFERENCES tareas_groups (id, board_id),
    CONSTRAINT tareas_tasks_done_check CHECK ((status = 'done') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX tareas_tasks_external_idx ON tareas_tasks (board_id, source, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX tareas_tasks_board_position_idx ON tareas_tasks (board_id, position);
CREATE INDEX tareas_tasks_board_status_idx ON tareas_tasks (board_id, status) WHERE archived_at IS NULL;
CREATE INDEX tareas_tasks_due_idx ON tareas_tasks (due_date) WHERE archived_at IS NULL AND status NOT IN ('done', 'cancelled');
CREATE INDEX tareas_tasks_customer_idx ON tareas_tasks (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX tareas_tasks_reviewer_idx ON tareas_tasks (reviewer_id) WHERE reviewer_id IS NOT NULL;

CREATE TABLE tareas_task_assignees (
    task_id UUID NOT NULL REFERENCES tareas_tasks (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX tareas_task_assignees_user_idx ON tareas_task_assignees (user_id, task_id);

CREATE TABLE tareas_checklist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tareas_tasks (id) ON DELETE CASCADE,
    text VARCHAR(300) NOT NULL CHECK (btrim(text) <> ''),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    done_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    done_at TIMESTAMPTZ NULL
);

CREATE INDEX tareas_checklist_task_idx ON tareas_checklist (task_id, position);

CREATE TABLE tareas_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tareas_tasks (id) ON DELETE CASCADE,
    author_id UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    author_name VARCHAR(120) NOT NULL,
    body TEXT NOT NULL CHECK (btrim(body) <> '' AND length(body) <= 5000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX tareas_comments_task_idx ON tareas_comments (task_id, created_at DESC);

-- Files live in the company file storage (backend + ref). task_id is NULL until the form that
-- uploaded the file is saved; delete_pending rows wait for the cleanup job.
CREATE TABLE tareas_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NULL REFERENCES tareas_tasks (id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(1000) NOT NULL DEFAULT '',
    content_type VARCHAR(200) NOT NULL DEFAULT 'application/octet-stream',
    size BIGINT NOT NULL DEFAULT 0,
    backend VARCHAR(40) NOT NULL,
    ref VARCHAR(500) NOT NULL,
    uploaded_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_pending BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX tareas_attachments_task_idx ON tareas_attachments (task_id);
CREATE INDEX tareas_attachments_cleanup_idx ON tareas_attachments (created_at) WHERE task_id IS NULL OR delete_pending;

CREATE TABLE tareas_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tareas_tasks (id) ON DELETE CASCADE,
    kind VARCHAR(40) NOT NULL,
    detail VARCHAR(1000) NOT NULL DEFAULT '',
    author_name VARCHAR(120) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX tareas_activity_task_idx ON tareas_activity (task_id, created_at DESC);

-- Due reminders already sent: one per task, user, kind and due date, so a job never repeats a notice.
CREATE TABLE tareas_reminders (
    task_id UUID NOT NULL REFERENCES tareas_tasks (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('due_soon', 'overdue')),
    due_date DATE NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id, kind, due_date)
);

COMMIT;
