-- MySQL Workbench Forward Engineering

SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0;
SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0;
SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- -----------------------------------------------------
-- Schema mydb
-- -----------------------------------------------------
-- -----------------------------------------------------
-- Schema viun
-- -----------------------------------------------------

-- -----------------------------------------------------
-- Schema viun
-- -----------------------------------------------------
CREATE SCHEMA IF NOT EXISTS `viun` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci ;
USE `viun` ;

-- -----------------------------------------------------
-- Table `viun`.`ELEMENTS`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`ELEMENTS` (
  `id_element` BIGINT NOT NULL,
  `key` VARCHAR(255) NULL DEFAULT NULL,
  `content` TEXT NULL DEFAULT NULL,
  `version` BIGINT NOT NULL,
  `sync` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id_element`),
  UNIQUE INDEX `uq_elements_version` (`version` ASC) VISIBLE)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`EDGE_TYPES`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`EDGE_TYPES` (
  `id_edge_type` BIGINT NOT NULL,
  `schema_type` ENUM('JSON_SCHEMA') NULL DEFAULT NULL,
  `is_directed` TINYINT(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id_edge_type`),
  CONSTRAINT `fk_edge_types_element`
    FOREIGN KEY (`id_edge_type`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE CASCADE)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`VERTEX_TYPES`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`VERTEX_TYPES` (
  `id_vertex_type` BIGINT NOT NULL,
  `schema_type` ENUM('JSON_SCHEMA') NULL DEFAULT NULL,
  PRIMARY KEY (`id_vertex_type`),
  CONSTRAINT `fk_vertex_types_element`
    FOREIGN KEY (`id_vertex_type`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE CASCADE)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`VERTEXES`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`VERTEXES` (
  `id_vertex` BIGINT NOT NULL,
  `id_vertex_type` BIGINT NOT NULL,
  PRIMARY KEY (`id_vertex`),
  INDEX `ix_vertexes_vertex_type` (`id_vertex_type` ASC) VISIBLE,
  CONSTRAINT `fk_vertexes_element`
    FOREIGN KEY (`id_vertex`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_vertexes_vertex_type`
    FOREIGN KEY (`id_vertex_type`)
    REFERENCES `viun`.`VERTEX_TYPES` (`id_vertex_type`))
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`EDGES`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`EDGES` (
  `id_edge` BIGINT NOT NULL,
  `id_edge_type` BIGINT NOT NULL,
  `id_vertex_from` BIGINT NOT NULL,
  `id_vertex_to` BIGINT NOT NULL,
  PRIMARY KEY (`id_edge`),
  INDEX `ix_edges_edge_type` (`id_edge_type` ASC) VISIBLE,
  INDEX `ix_edges_vertex_from` (`id_vertex_from` ASC) VISIBLE,
  INDEX `ix_edges_vertex_to` (`id_vertex_to` ASC) VISIBLE,
  CONSTRAINT `fk_edges_edge_type`
    FOREIGN KEY (`id_edge_type`)
    REFERENCES `viun`.`EDGE_TYPES` (`id_edge_type`),
  CONSTRAINT `fk_edges_element`
    FOREIGN KEY (`id_edge`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_edges_vertex_from`
    FOREIGN KEY (`id_vertex_from`)
    REFERENCES `viun`.`VERTEXES` (`id_vertex`),
  CONSTRAINT `fk_edges_vertex_to`
    FOREIGN KEY (`id_vertex_to`)
    REFERENCES `viun`.`VERTEXES` (`id_vertex`))
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`GRAPHS`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`GRAPHS` (
  `id_graph` BIGINT NOT NULL AUTO_INCREMENT,
  `graph_name` VARCHAR(255) NOT NULL,
  `is_tombstone` TINYINT(1) NOT NULL DEFAULT '0',
  `id_graph_metadata_element` BIGINT NULL DEFAULT NULL,
  `last_element_update_version` BIGINT NULL DEFAULT NULL,
  `last_link_sync_version` BIGINT NULL DEFAULT NULL,
  `tombstone_revive_version` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id_graph`),
  INDEX `ix_graphs_metadata_element` (`id_graph_metadata_element` ASC) VISIBLE,
  CONSTRAINT `fk_graphs_metadata_element`
    FOREIGN KEY (`id_graph_metadata_element`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE SET NULL)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`LINKS`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`LINKS` (
  `id_link` BIGINT NOT NULL,
  `id_graph` BIGINT NOT NULL,
  `id_element` BIGINT NOT NULL,
  `is_tombstone` TINYINT(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id_link`),
  INDEX `ix_links_graph` (`id_graph` ASC) VISIBLE,
  INDEX `ix_links_element` (`id_element` ASC) VISIBLE,
  CONSTRAINT `fk_links_graph`
    FOREIGN KEY (`id_graph`)
    REFERENCES `viun`.`GRAPHS` (`id_graph`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_links_referenced_element`
    FOREIGN KEY (`id_element`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`),
  CONSTRAINT `fk_links_self_element`
    FOREIGN KEY (`id_link`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE CASCADE)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`UNIONS`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`UNIONS` (
  `id_union` BIGINT NOT NULL AUTO_INCREMENT,
  `singleton_keeper` ENUM('') NOT NULL,
  `union_name` VARCHAR(255) NOT NULL,
  `is_tombstone` TINYINT(1) NOT NULL DEFAULT '0',
  `id_union_metadata_element` BIGINT NULL DEFAULT NULL,
  `last_element_update_version` BIGINT NULL DEFAULT NULL,
  `tombstone_revive_version` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id_union`, `singleton_keeper`),
  UNIQUE INDEX `uq_unions_name` (`union_name` ASC) VISIBLE,
  UNIQUE INDEX `uq_unions_singleton` (`singleton_keeper` ASC) VISIBLE,
  INDEX `fk_unions_metadata_element` (`id_union_metadata_element` ASC) VISIBLE,
  CONSTRAINT `fk_unions_metadata_element`
    FOREIGN KEY (`id_union_metadata_element`)
    REFERENCES `viun`.`ELEMENTS` (`id_element`)
    ON DELETE SET NULL)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- -----------------------------------------------------
-- Table `viun`.`VERSIONS`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `viun`.`VERSIONS` (
  `version` BIGINT NOT NULL AUTO_INCREMENT,
  `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`version`))
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


SET SQL_MODE=@OLD_SQL_MODE;
SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS;
SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS;
