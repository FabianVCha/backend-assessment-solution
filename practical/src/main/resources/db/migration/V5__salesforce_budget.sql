CREATE TABLE salesforce_budget (
    utc_day   date    PRIMARY KEY,
    calls     integer NOT NULL CHECK (calls BETWEEN 0 AND 150000),
    in_flight integer NOT NULL CHECK (in_flight BETWEEN 0 AND 25 AND in_flight <= calls)
);

CREATE INDEX salesforce_budget_active ON salesforce_budget (utc_day) WHERE in_flight > 0;

-- The UTC bucket alone would reset the allowance at midnight. Keep admission timestamps to
-- count just the part of yesterday still in the rolling 24 hours.
CREATE TABLE salesforce_budget_call (
    admitted_at timestamptz NOT NULL
);

CREATE INDEX salesforce_budget_call_time ON salesforce_budget_call (admitted_at);
