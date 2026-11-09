-- Stands in for real production rows: a small book over the last three completed months.
create role navix_app login password 'old';

insert into loan (customer_id, principal, processing_fee, gst, net_disbursed, daily_interest_rate,
                  disbursed_on, due_date, total_repayable, outstanding, status, created_at, closed_on)
select 100 + g, 1000000, 100000, 18000, 882000, 0.01,
       (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 2,
       (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 20,
       1180000, case when g % 2 = 0 then 0 else 1180000 end,
       case when g % 2 = 0 then 'CLOSED' else 'OVERDUE' end, now(),
       case when g % 2 = 0 then (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 20 end
from generate_series(1, 12) g;

insert into loan_application (customer_id, status, loan_id, created_at)
select customer_id, case when status = 'CLOSED' then 'CLOSED' else 'OVERDUE' end, id, now() from loan;
