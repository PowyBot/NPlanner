package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;

/** Every plan has an id of its own that a save keeps, and a server revision with whether it is in step with it. */
class PlanIdTest {

    @Test
    void aPlansIdAndRevisionSurviveASave() {
        final Plan plan = Serializer.decodePlan("{}");
        final Graph a = new Graph("a"), b = new Graph("b");
        plan.getGraphs()
            .add(a);
        plan.getGraphs()
            .add(b);
        b.setRevision(7);
        b.markSynced();
        final Plan again = Serializer.decodePlan(Serializer.encodePlan(plan));
        final Graph a2 = again.getGraphs()
            .get(0),
            b2 = again.getGraphs()
                .get(1);
        assertEquals(a.getPlanId(), a2.getPlanId());
        assertEquals(b.getPlanId(), b2.getPlanId());
        assertEquals(0, a2.getRevision());
        assertTrue(a2.unsynced(), "a plan the server never had has to go to it");
        assertEquals(7, b2.getRevision());
        assertFalse(b2.unsynced(), "in step when saved, in step when read");
        assertEquals(1, again.indexOf(b.getPlanId()));
    }

    @Test
    void anEditAfterTheLastSyncIsRememberedThroughASave() {
        final Plan plan = Serializer.decodePlan("{}");
        final Graph a = new Graph("a");
        plan.getGraphs()
            .add(a);
        a.setRevision(3);
        a.markSynced();
        a.setName("renamed");
        assertTrue(a.unsynced(), "a rename is an edit");
        final Graph read = Serializer.decodePlan(Serializer.encodePlan(plan))
            .getGraphs()
            .get(0);
        assertTrue(read.unsynced());
        assertEquals(3, read.getRevision());
    }

    @Test
    void slotsSavedBeforeIdsGetOneAndDuplicatesAreSplit() {
        final Plan plan = Serializer.decodePlan("{}");
        plan.getGraphs()
            .add(new Graph("x"));
        plan.getGraphs()
            .add(new Graph("y"));
        final JsonObject tree = Serializer.planTree(plan);
        final JsonArray slots = tree.getAsJsonArray("graphs");
        // An old save: no ids at all.
        final JsonObject old = Serializer.planTree(plan);
        for (final var e : old.getAsJsonArray("graphs")) e.getAsJsonObject()
            .remove("id");
        final Plan migrated = Serializer.decodePlan(Serializer.GSON.toJson(old));
        final UUID first = migrated.getGraphs()
            .get(0)
            .getPlanId();
        assertNotEquals(
            first,
            migrated.getGraphs()
                .get(1)
                .getPlanId());
        final Plan migratedAgain = Serializer.decodePlan(Serializer.encodePlan(migrated));
        assertEquals(
            first,
            migratedAgain.getGraphs()
                .get(0)
                .getPlanId(),
            "the id given on the first read is kept from then on");
        // Two slots with the same id (a hand-edited save): the second gets its own.
        slots.get(1)
            .getAsJsonObject()
            .add(
                "id",
                slots.get(0)
                    .getAsJsonObject()
                    .get("id"));
        final Plan split = Serializer.decodePlan(Serializer.GSON.toJson(tree));
        assertNotEquals(
            split.getGraphs()
                .get(0)
                .getPlanId(),
            split.getGraphs()
                .get(1)
                .getPlanId());
    }

    @Test
    void aPastedPlanIsANewPlan() {
        final Graph a = new Graph("a");
        final Graph pasted = Serializer.decode(Serializer.encode(a));
        assertNotEquals(a.getPlanId(), pasted.getPlanId());
    }
}
