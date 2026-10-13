package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.client.sync.Reconcile;
import com.gtnhplanner.client.sync.Reconcile.Local;

/** Coming into step with the server's plans on joining. */
class ReconcileTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    @Test
    void eachCaseDoesWhatAPlayerWouldExpect() {
        final List<Local> local = List.of(
            new Local(id(1), 0, true), // made here, never sent: sent
            new Local(id(2), 3, false), // in step: nothing
            new Local(id(3), 3, false), // the server moved on: fetched
            new Local(id(4), 3, true), // edited here, the server moved on too: sent (the server keeps theirs as a copy)
            new Local(id(5), 3, false), // deleted on the server while away: dropped
            new Local(id(6), 3, true), // edited here after it was deleted there: kept, sent
            new Local(id(7), 9, false)); // the server has an older one (restored from a backup): sent
        final Map<UUID, Long> server = Map.of(id(2), 3L, id(3), 5L, id(4), 5L, id(7), 2L, id(8), 1L);
        final Reconcile.Plan r = Reconcile.of(local, server);
        assertEquals(List.of(id(1), id(4), id(6), id(7)), r.push());
        assertEquals(List.of(id(3), id(8)), r.fetch());
        assertEquals(List.of(id(5)), r.drop());
    }
}
