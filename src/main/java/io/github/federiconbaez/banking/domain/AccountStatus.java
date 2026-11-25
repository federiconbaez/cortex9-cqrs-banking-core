package io.github.federiconbaez.banking.domain;

/** Ciclo de vida: NOT_OPENED -> ACTIVE <-> FROZEN -> CLOSED (terminal). */
public enum AccountStatus {
  NOT_OPENED,
  ACTIVE,
  FROZEN,
  CLOSED
}
