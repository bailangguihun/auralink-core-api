package com.auralink.assistant;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Monotonic deadline for a single execution's preparation, never a provider retry policy. */
final class AssistantArtworkDeadline {
 static final Duration BUDGET=Duration.ofSeconds(15);
 private final LongSupplier nanoTime;
 private final long started, budgetNanos;
 AssistantArtworkDeadline(){this(System::nanoTime,BUDGET);}
 AssistantArtworkDeadline(LongSupplier nanoTime,Duration budget){this.nanoTime=Objects.requireNonNull(nanoTime);this.started=nanoTime.getAsLong();this.budgetNanos=budget.toNanos();if(budgetNanos<=0)throw new IllegalArgumentException("Positive preparation budget is required");}
 void check(){if(nanoTime.getAsLong()-started>=budgetNanos)throw new AssistantFailure(504,"ASSISTANT_ARTWORK_TIMEOUT","Platform artwork preparation timed out; no model was dispatched");}
 <T>T lookup(Supplier<T> query){check();try{return query.get();}finally{check();}}
}
