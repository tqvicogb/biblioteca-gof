-- Execute conectado à base dedicada da biblioteca.
CREATE TABLE IF NOT EXISTS unidade_lock (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO unidade_lock VALUES (1);
CREATE TABLE IF NOT EXISTS biblioteca_dados (
  familia VARCHAR(30) NOT NULL,
  id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  conteudo BLOB NOT NULL,
  PRIMARY KEY (familia, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;