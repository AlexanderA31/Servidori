--insert default admin (username a, password aa)
INSERT INTO User (id, enabled, roles, username, password)
VALUES (1, TRUE, 'ADMIN,USER', 'a', '{bcrypt}$2a$10$2BpNTbrsarbHjNsUWgzfNubJqBRf.0Vz9924nRSHBqlbPKerkgX.W');
