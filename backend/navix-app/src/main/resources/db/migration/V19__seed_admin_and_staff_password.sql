-- V19: seed a single real ADMIN account with email + password for the staff console.
--
-- Email:    navixfinance@gmail.com
-- Password: demo   (BCrypt, strength 10 — DEMO ONLY; rotate before any real exposure)
--
-- The hash below was generated AND verified with the project's own Spring
-- BCryptPasswordEncoder (matches("demo", hash) == true), so AuthController.staffLogin
-- accepts it. Mirrors the V17 hardcoded-hash pattern.
--
-- The 11 demo personas (V10 + V17, all Admin@12345) are intentionally KEPT so the
-- maker-checker / separation-of-duties demo (the "Act as role" bar) still works.
-- Guarded with WHERE NOT EXISTS so a re-run is a no-op.

insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
select 'navixfinance@gmail.com', 'NAVIX Admin', 'ADMIN', 'ACTIVE',
       '$2a$10$/g/9oH2lJ//EfcEp6u0TAeAqjhk.LCK4SgZKVHT8I3wrO4.4TqAza',
       now(), 'seed'
 where not exists (select 1 from staff_user where email = 'navixfinance@gmail.com');
