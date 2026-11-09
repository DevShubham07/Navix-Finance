-- V17, V19, V42 and V55 seeded staff accounts with hashes of passwords published in this repository.
-- Those migrations are immutable, so this one blanks every account still carrying either hash. An
-- account whose password was changed no longer matches and is untouched. A null hash cannot sign in
-- (AuthController.staffLogin); a real inbox recovers through forgot-password.
update staff_user
   set password_hash = null
 where password_hash in (
       '$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W',
       '$2a$10$/g/9oH2lJ//EfcEp6u0TAeAqjhk.LCK4SgZKVHT8I3wrO4.4TqAza');
