-- Compte administrateur des profils dev et test (mot de passe : password123). Jamais appliqué en production.
INSERT INTO app.users (id, email, password_hash, display_name, role)
VALUES ('u_admin', 'admin@swipread.dev', '$2y$10$VboLvCJTyO8DGxl1Bh5fUeRxqmevU3T..uXy9SeztFsJ/70JAJjbO', 'Admin', 'ADMIN')
ON CONFLICT (email) DO UPDATE SET role = 'ADMIN';
