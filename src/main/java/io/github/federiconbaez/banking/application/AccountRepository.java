package io.github.federiconbaez.banking.application;

import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.IdGenerator;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.domain.Account;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import java.util.List;

/** Patron Repository sobre el Event Store: carga por replay y guarda con expected version. */
public final class AccountRepository {

  private final EventStore store;
  private final IdGenerator ids;

  public AccountRepository(EventStore store, IdGenerator ids) {
    this.store = store;
    this.ids = ids;
  }

  public static String streamId(AccountId id) {
    return "account-" + id.value();
  }

  public record Loaded(Account account, List<StoredEvent> history) {}

  public Loaded load(AccountId id) {
    List<StoredEvent> history = store.load(streamId(id));
    List<AccountEvent> events = history.stream().map(StoredEvent::event).toList();
    return new Loaded(Account.rehydrate(id, events), history);
  }

  public AppendResult save(Account account, CommandId causationId) {
    List<NewEvent> pending =
        account.uncommittedEvents().stream().map(e -> new NewEvent(ids.newId(), e)).toList();
    AppendResult result =
        store.append(streamId(account.id()), account.version(), causationId, pending);
    if (result instanceof AppendResult.Appended) {
      account.markCommitted();
    }
    return result;
  }
}
