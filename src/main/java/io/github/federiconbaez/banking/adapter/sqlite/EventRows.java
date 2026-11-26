package io.github.federiconbaez.banking.adapter.sqlite;

import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Traducción {@link AccountEvent} ↔ columnas. Una columna por campo, sin serializador genérico:
 * agregar un evento nuevo rompe la compilación del {@code switch} hasta que se mapee. Los instantes
 * van como ISO-8601 (sin pérdida de nanos).
 */
final class EventRows {
  static final String BODY_COLUMNS =
      "type, account_id, occurred_at, owner, currency, overdraft_limit_minor, amount_minor,"
          + " reference, reason";
  static final String BODY_PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?";

  private EventRows() {}

  static void bindBody(PreparedStatement ps, int from, AccountEvent event) throws SQLException {
    String type;
    @Nullable String owner = null;
    @Nullable Currency currency = null;
    @Nullable Long overdraft = null;
    @Nullable Long amount = null;
    @Nullable String reference = null;
    @Nullable String reason = null;
    switch (event) {
      case AccountEvent.AccountOpened e -> {
        type = "AccountOpened";
        owner = e.owner();
        currency = e.currency();
        overdraft = e.overdraftLimitMinor();
      }
      case AccountEvent.MoneyDeposited e -> {
        type = "MoneyDeposited";
        currency = e.amount().currency();
        amount = e.amount().minorUnits();
        reference = e.reference();
      }
      case AccountEvent.MoneyWithdrawn e -> {
        type = "MoneyWithdrawn";
        currency = e.amount().currency();
        amount = e.amount().minorUnits();
        reference = e.reference();
      }
      case AccountEvent.AccountFrozen e -> {
        type = "AccountFrozen";
        reason = e.reason();
      }
      case AccountEvent.AccountUnfrozen e -> type = "AccountUnfrozen";
      case AccountEvent.AccountClosed e -> type = "AccountClosed";
    }
    int i = from;
    ps.setString(i++, type);
    ps.setString(i++, event.accountId().value());
    ps.setString(i++, event.occurredAt().toString());
    setString(ps, i++, owner);
    setString(ps, i++, currency == null ? null : currency.name());
    setLong(ps, i++, overdraft);
    setLong(ps, i++, amount);
    setString(ps, i++, reference);
    setString(ps, i, reason);
  }

  static AccountEvent read(ResultSet rs) throws SQLException {
    String type = required(rs, "type");
    AccountId id = new AccountId(required(rs, "account_id"));
    Instant at = Instant.parse(required(rs, "occurred_at"));
    return switch (type) {
      case "AccountOpened" ->
          new AccountEvent.AccountOpened(
              id,
              required(rs, "owner"),
              currency(rs),
              requiredLong(rs, "overdraft_limit_minor"),
              at);
      case "MoneyDeposited" ->
          new AccountEvent.MoneyDeposited(id, money(rs), required(rs, "reference"), at);
      case "MoneyWithdrawn" ->
          new AccountEvent.MoneyWithdrawn(id, money(rs), required(rs, "reference"), at);
      case "AccountFrozen" -> new AccountEvent.AccountFrozen(id, required(rs, "reason"), at);
      case "AccountUnfrozen" -> new AccountEvent.AccountUnfrozen(id, at);
      case "AccountClosed" -> new AccountEvent.AccountClosed(id, at);
      default -> throw new StorageException("unknown event type " + type);
    };
  }

  private static Money money(ResultSet rs) throws SQLException {
    return Money.of(requiredLong(rs, "amount_minor"), currency(rs));
  }

  private static Currency currency(ResultSet rs) throws SQLException {
    String name = required(rs, "currency");
    try {
      return Currency.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new StorageException("unknown currency " + name, e);
    }
  }

  private static String required(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    if (value == null) {
      throw new StorageException("corrupt row: " + column + " is null");
    }
    return value;
  }

  private static long requiredLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    if (rs.wasNull()) {
      throw new StorageException("corrupt row: " + column + " is null");
    }
    return value;
  }

  private static void setString(PreparedStatement ps, int i, @Nullable String v)
      throws SQLException {
    if (v == null) {
      ps.setNull(i, Types.VARCHAR);
    } else {
      ps.setString(i, v);
    }
  }

  private static void setLong(PreparedStatement ps, int i, @Nullable Long v) throws SQLException {
    if (v == null) {
      ps.setNull(i, Types.INTEGER);
    } else {
      ps.setLong(i, v);
    }
  }
}
