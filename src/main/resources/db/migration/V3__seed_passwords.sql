-- ─────────────────────────────────────────────────────────────
-- V3: real password hashes for the seeded demo users
--
-- V2 inserted a CHANGE_ME placeholder because there was no auth yet.
-- Both demo accounts now use the password: password123
--
-- BCrypt, cost 10. The hash embeds its own salt, which is why two rows
-- with the same password have different hashes — that is the point: a
-- stolen table cannot be attacked with one precomputed rainbow table.
-- ─────────────────────────────────────────────────────────────

UPDATE users
SET password_hash = '$2b$10$q.87SH3OqGRfAH/4cdEJle6yeELkUGwb3mdJEzlxb51LC9KnCFEXu'
WHERE email = 'demo@tickethub.dev';

UPDATE users
SET password_hash = '$2b$10$Z24RwrrMrU4pWDGJJkE/aeDtsEG/acD08TIvtWWf4JU//KwinST/6'
WHERE email = 'second@tickethub.dev';
