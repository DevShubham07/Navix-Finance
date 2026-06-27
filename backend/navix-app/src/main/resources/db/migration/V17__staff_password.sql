-- P6: real staff auth. BCrypt password hash on staff_user (nullable — set at
-- invite-accept). Seed EVERY demo staff row with a known password so the
-- role-pick login keeps working through real JWT auth.
-- Default password: Admin@12345  (CHANGE IN PRODUCTION).
alter table staff_user add column password_hash varchar(255);

update staff_user
   set password_hash = '$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W'
 where password_hash is null;
