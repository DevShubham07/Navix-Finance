-- Align staff roles with dfd.md §6.3: COLLECTIONS_HEAD -> COLLECTION_HEAD,
-- COLLECTION_OFFICER -> COLLECTION_EXECUTIVE, and add the internal DEVELOPER role.

update staff_user   set role = 'COLLECTION_HEAD'      where role = 'COLLECTIONS_HEAD';
update staff_user   set role = 'COLLECTION_EXECUTIVE' where role = 'COLLECTION_OFFICER';
update staff_invite set role = 'COLLECTION_HEAD'      where role = 'COLLECTIONS_HEAD';
update staff_invite set role = 'COLLECTION_EXECUTIVE' where role = 'COLLECTION_OFFICER';

alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'KYC_APPROVER','CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','ADMIN','DEVELOPER'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'KYC_APPROVER','CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','ADMIN','DEVELOPER'));
