package io.github.federiconbaez.banking.adapter.inmemory;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.port.AppendResult;
import io.github.federiconbaez.banking.application.port.EventStore;
import io.github.federiconbaez.banking.application.port.NewEvent;
import io.github.federiconbaez.banking.application.port.StoredEvent;
import io.github.federiconbaez.banking.domain.AccountEvent;
import io.github.federiconbaez.banking.domain.AccountId;
import io.github.federiconbaez.banking.domain.Currency;
import io.github.federiconbaez.banking.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Suite de contrato reutilizable: todo adapter de EventStore debe pasarla. */
abstract class EventStoreContract {

  protected abstract EventStore newStore();

  static final CommandId CMD = new CommandId("c");
  EventStore store;

  @BeforeEach
  void setUp() {
    store = newStore();
  }

  static NewEvent ev(String id, long minor) {
    return new NewEvent(
        id,
        new AccountEvent.MoneyDeposited(
            new AccountId("x"), Money.of(minor, Currency.ARS), "r", T0));
  }

  @Test
  void unknownStreamIsEmpty() {
    assertThat(store.load("nope")).isEmpty();
    assertThat(store.readAll(0, 10)).isEmpty();
  }

  @Test
  void appendToNewStreamAtVersionZero() {
    AppendResult r = store.append("s", 0, CMD, List.of(ev("e1", 1), ev("e2", 2)));
    assertThat(r).isInstanceOf(AppendResult.Appended.class);
    List<StoredEvent> stored = ((AppendResult.Appended) r).events();
    assertThat(stored).extracting(StoredEvent::version).containsExactly(1L, 2L);
    assertThat(stored).extracting(StoredEvent::globalPosition).containsExactly(1L, 2L);
    assertThat(stored).allMatch(e -> e.streamId().equals("s") && e.causationId().equals(CMD));
    assertThat(store.load("s")).isEqualTo(stored);
  }

  @Test
  void wrongExpectedVersionConflictsWithoutWriting() {
    store.append("s", 0, CMD, List.of(ev("e1", 1)));
    assertThat(store.append("s", 0, CMD, List.of(ev("e2", 2))))
        .isEqualTo(new AppendResult.VersionConflict(0, 1));
    assertThat(store.append("s", 5, CMD, List.of(ev("e2", 2))))
        .isEqualTo(new AppendResult.VersionConflict(5, 1));
    assertThat(store.append("new", 1, CMD, List.of(ev("e3", 3))))
        .isEqualTo(new AppendResult.VersionConflict(1, 0));
    assertThat(store.load("s")).hasSize(1);
    assertThat(store.load("new")).isEmpty();
    assertThat(store.readAll(0, 10)).hasSize(1);
  }

  @Test
  void emptyAppendIsRejected() {
    assertThatThrownBy(() -> store.append("s", 0, CMD, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void globalPositionsAreMonotonicAcrossStreams() {
    store.append("a", 0, CMD, List.of(ev("e1", 1)));
    store.append("b", 0, CMD, List.of(ev("e2", 1)));
    store.append("a", 1, CMD, List.of(ev("e3", 1)));
    assertThat(store.readAll(0, 10))
        .extracting(StoredEvent::eventId)
        .containsExactly("e1", "e2", "e3");
    assertThat(store.readAll(0, 10))
        .extracting(StoredEvent::globalPosition)
        .containsExactly(1L, 2L, 3L);
    assertThat(store.load("a")).extracting(StoredEvent::version).containsExactly(1L, 2L);
  }

  @Test
  void readAllPaginates() {
    for (int i = 0; i < 5; i++) {
      store.append("s", i, CMD, List.of(ev("e" + i, 1)));
    }
    assertThat(store.readAll(0, 2)).extracting(StoredEvent::globalPosition).containsExactly(1L, 2L);
    assertThat(store.readAll(2, 2)).extracting(StoredEvent::globalPosition).containsExactly(3L, 4L);
    assertThat(store.readAll(4, 2)).extracting(StoredEvent::globalPosition).containsExactly(5L);
    assertThat(store.readAll(5, 2)).isEmpty();
    assertThat(store.readAll(99, 2)).isEmpty();
    assertThat(store.readAll(-3, 1)).extracting(StoredEvent::globalPosition).containsExactly(1L);
    assertThatThrownBy(() -> store.readAll(0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnedListsAreImmutableSnapshots() {
    store.append("s", 0, CMD, List.of(ev("e1", 1)));
    List<StoredEvent> loaded = store.load("s");
    assertThatThrownBy(loaded::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> store.readAll(0, 5).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    store.append("s", 1, CMD, List.of(ev("e2", 1)));
    assertThat(loaded).hasSize(1);
  }

  @Test
  void concurrentAppendsAtSameVersionHaveExactlyOneWinner() throws Exception {
    int writers = 16;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<AppendResult>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        String id = "w" + i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return store.append("hot", 0, CMD, List.of(ev(id, 1)));
                }));
      }
      start.countDown();
      int winners = 0;
      for (Future<AppendResult> f : results) {
        if (f.get() instanceof AppendResult.Appended) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
      assertThat(store.load("hot")).hasSize(1);
    } finally {
      pool.shutdown();
    }
  }
}
