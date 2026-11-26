-- Execute como administrador no cliente mysql, no diretório raiz do projeto.
-- Antes, substitua SENHA_LOCAL_ESCOLHIDA por uma senha própria.
CREATE DATABASE IF NOT EXISTS biblioteca_gof CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE biblioteca_gof;
SOURCE schema.sql;
CREATE USER IF NOT EXISTS 'biblioteca_app'@'127.0.0.1' IDENTIFIED BY 'SENHA_LOCAL_ESCOLHIDA';
GRANT SELECT, INSERT, UPDATE, DELETE ON biblioteca_gof.* TO 'biblioteca_app'@'127.0.0.1';