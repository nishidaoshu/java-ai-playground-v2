CREATE TABLE request_budgets (
 request_id UUID PRIMARY KEY REFERENCES request_traces ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES app_users,
 delegations INT NOT NULL DEFAULT 0, tools INT NOT NULL DEFAULT 0,
 retrievals INT NOT NULL DEFAULT 0, models INT NOT NULL DEFAULT 0,
 max_delegations INT NOT NULL, max_tools INT NOT NULL, max_retrievals INT NOT NULL,
 max_models INT NOT NULL, deadline TIMESTAMPTZ NOT NULL
);
CREATE TABLE agent_runs (
 id UUID PRIMARY KEY, request_id UUID NOT NULL REFERENCES request_traces ON DELETE CASCADE,
 parent_id UUID, agent VARCHAR(40) NOT NULL, skill_id VARCHAR(40), skill_version VARCHAR(40),
 state VARCHAR(20) NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ,
 tools INT NOT NULL DEFAULT 0, steps INT NOT NULL DEFAULT 0,
 max_tools INT NOT NULL, max_steps INT NOT NULL, error TEXT
);
CREATE INDEX agent_request ON agent_runs(request_id,started_at);
CREATE TABLE request_evidence (
 request_id UUID REFERENCES request_traces ON DELETE CASCADE,
 chunk_id UUID REFERENCES knowledge_chunks ON DELETE CASCADE,
 similarity DOUBLE PRECISION NOT NULL, rerank DOUBLE PRECISION NOT NULL,
 PRIMARY KEY(request_id,chunk_id)
);
CREATE INDEX trace_filter ON request_traces(user_id,status,path,started_at DESC);
