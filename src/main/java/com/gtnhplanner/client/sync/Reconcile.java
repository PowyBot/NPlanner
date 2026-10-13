package com.gtnhplanner.client.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a client does to come into step with the server's plans when it joins (pure, so tested): which of its plans
 * it sends, which it asks for, and which it drops because the server deleted them while it was away.
 */
public final class Reconcile {

    /** A plan on the client: its id, the server revision it is based on (0: never sent), whether it has edits since. */
    public record Local(UUID id, long revision, boolean unsynced) {}

    public record Plan(List<UUID> push, List<UUID> fetch, List<UUID> drop) {}

    private Reconcile() {}

    /** {@code server}: the server's plans, id to revision. */
    public static Plan of(final List<Local> local, final Map<UUID, Long> server) {
        final List<UUID> push = new ArrayList<>(), fetch = new ArrayList<>(), drop = new ArrayList<>();
        final Set<UUID> mine = new HashSet<>();
        for (final Local l : local) {
            mine.add(l.id());
            final Long theirs = server.get(l.id());
            if (theirs == null) {
                // Not on the server: new here, or edited here after it was deleted there (kept); else deleted there.
                if (l.revision() == 0 || l.unsynced()) push.add(l.id());
                else drop.add(l.id());
            } else if (l.unsynced() || l.revision() > theirs) {
                // Edits here win; if the server moved on meanwhile, it keeps what they overwrite as a copy.
                push.add(l.id());
            } else if (l.revision() < theirs) fetch.add(l.id());
        }
        for (final UUID id : server.keySet()) if (!mine.contains(id)) fetch.add(id);
        return new Plan(push, fetch, drop);
    }
}
