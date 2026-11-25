package io.github.federiconbaez.banking.adapter.inmemory;

import static io.github.federiconbaez.banking.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.federiconbaez.banking.application.Command;
import io.github.federiconbaez.banking.application.CommandId;
import io.github.federiconbaez.banking.application.CommandResult;
import io.github.federiconbaez.banking.application.port.IdempotencyRecord;
import io.github.federiconbaez.banking.domain.AccountId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SupportAdaptersTest {

  @Test
  void idempotencyStoreIsFirstWriterWins() {
    InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    CommandId id = new CommandId("k");
    Command cmd = new Command.CloseAccount(id, new AccountId("a"));
    IdempotencyRecord first = new IdempotencyRecord(cmd, new CommandResult.ConcurrencyConflict(1));
    store.save(id, first);
    store.save(id, new IdempotencyRecord(cmd, new CommandResult.ConcurrencyConflict(2)));
    assertThat(store.find(id)).contains(first);
    assertThat(store.find(new CommandId("other"))).isEmpty();
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void clocksAndIds() {
    FixedClock clock = new FixedClock(T0);
    clock.advance(Duration.ofSeconds(5));
    assertThat(clock.now()).isEqualTo(T0.plusSeconds(5));
    assertThat(new SystemClock().now()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    SequentialIdGenerator seq = new SequentialIdGenerator("e");
    assertThat(seq.newId()).isEqualTo("e-1");
    assertThat(seq.newId()).isEqualTo("e-2");
    UuidIdGenerator uuid = new UuidIdGenerator();
    assertThat(uuid.newId()).hasSize(36).isNotEqualTo(uuid.newId());
  }
}
