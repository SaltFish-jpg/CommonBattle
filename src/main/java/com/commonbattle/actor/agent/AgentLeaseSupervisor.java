package com.commonbattle.actor.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程级 Agent owner 租约监督器。
 * 它批量续租本进程持有的 Agent；明确冲突时立即 fencing，网络异常时则保留到本地租约到期再 fencing。
 */
public final class AgentLeaseSupervisor implements AutoCloseable {
    private final LeasedAgentDirectory directory;
    private final String ownerSession;
    private final Duration leaseTtl;
    private final Duration renewInterval;
    private final Clock clock;
    private final AgentLeaseLossHandler lossHandler;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Map<AgentIdentity, AgentDirectoryLease> leases = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong acquiredLeases = new AtomicLong();
    private final AtomicLong renewedLeases = new AtomicLong();
    private final AtomicLong renewalFailures = new AtomicLong();
    private final AtomicLong lostLeases = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public AgentLeaseSupervisor(
            LeasedAgentDirectory directory,
            String ownerSession,
            Duration leaseTtl,
            Duration renewInterval,
            Clock clock,
            AgentLeaseLossHandler lossHandler
    ) {
        this(
                directory,
                ownerSession,
                leaseTtl,
                renewInterval,
                clock,
                lossHandler,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "agent-directory-lease-renewer");
                    thread.setDaemon(true);
                    return thread;
                }),
                true
        );
    }

    public AgentLeaseSupervisor(
            LeasedAgentDirectory directory,
            String ownerSession,
            Duration leaseTtl,
            Duration renewInterval,
            Clock clock,
            AgentLeaseLossHandler lossHandler,
            ScheduledExecutorService scheduler
    ) {
        this(directory, ownerSession, leaseTtl, renewInterval, clock, lossHandler, scheduler, false);
    }

    private AgentLeaseSupervisor(
            LeasedAgentDirectory directory,
            String ownerSession,
            Duration leaseTtl,
            Duration renewInterval,
            Clock clock,
            AgentLeaseLossHandler lossHandler,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.ownerSession = requireOwnerSession(ownerSession);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.renewInterval = positive(renewInterval, "renewInterval");
        if (renewInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException("renewInterval must be shorter than leaseTtl");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lossHandler = Objects.requireNonNull(lossHandler, "lossHandler");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public static String newOwnerSession(String serviceWireName) {
        Objects.requireNonNull(serviceWireName, "serviceWireName");
        if (serviceWireName.isBlank()) {
            throw new IllegalArgumentException("serviceWireName must not be blank");
        }
        return serviceWireName + ":" + UUID.randomUUID();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::renewSafely,
                renewInterval.toMillis(),
                renewInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public Optional<AgentDirectoryLease> acquire(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        AgentDirectoryLease current = leases.get(identity);
        if (current != null) {
            return current.location().equals(location) ? Optional.of(current) : Optional.empty();
        }
        Optional<AgentDirectoryLease> acquired = directory.acquire(identity, location, ownerSession, leaseTtl);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        AgentDirectoryLease lease = acquired.orElseThrow();
        AgentDirectoryLease raced = leases.putIfAbsent(identity, lease);
        if (raced != null) {
            if (!raced.equals(lease)) {
                directory.release(lease);
            }
            return raced.location().equals(location) ? Optional.of(raced) : Optional.empty();
        }
        acquiredLeases.incrementAndGet();
        return Optional.of(lease);
    }

    public boolean release(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        AgentDirectoryLease lease = leases.remove(identity);
        return lease != null && directory.release(lease);
    }

    public Optional<AgentDirectoryLease> handoff(AgentIdentity identity, AgentLocation target) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        AgentDirectoryLease current = leases.get(identity);
        if (current == null) {
            return Optional.empty();
        }
        String handoffSession = "handoff:" + UUID.randomUUID();
        Optional<AgentDirectoryLease> transferred = directory.transfer(
                current, target, handoffSession, leaseTtl
        );
        if (transferred.isPresent()) {
            leases.remove(identity, current);
        }
        return transferred;
    }

    public Optional<AgentDirectoryLease> handoffCurrent(
            AgentIdentity identity,
            AgentLocation expectedCurrent,
            AgentLocation target
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(target, "target");
        Optional<AgentDirectoryLease> current = directory.locateLease(identity);
        if (current.isEmpty() || !current.orElseThrow().location().equals(expectedCurrent)) {
            return Optional.empty();
        }
        return directory.transfer(
                current.orElseThrow(), target, "handoff:" + UUID.randomUUID(), leaseTtl
        );
    }

    public Optional<AgentDirectoryLease> adopt(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        Optional<AgentDirectoryLease> current = directory.locateLease(identity);
        if (current.isEmpty()
                || !current.orElseThrow().location().equals(location)
                || !current.orElseThrow().ownerSession().startsWith("handoff:")) {
            return Optional.empty();
        }
        Optional<AgentDirectoryLease> adopted = directory.transfer(
                current.orElseThrow(), location, ownerSession, leaseTtl
        );
        adopted.ifPresent(lease -> {
            leases.put(identity, lease);
            acquiredLeases.incrementAndGet();
        });
        return adopted;
    }

    public Optional<AgentDirectoryLease> reclaim(
            AgentIdentity identity,
            AgentLocation expectedCurrent,
            AgentLocation source
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(source, "source");
        Optional<AgentDirectoryLease> current = directory.locateLease(identity);
        if (current.isEmpty() || !current.orElseThrow().location().equals(expectedCurrent)) {
            return Optional.empty();
        }
        Optional<AgentDirectoryLease> reclaimed = directory.transfer(
                current.orElseThrow(), source, ownerSession, leaseTtl
        );
        reclaimed.ifPresent(lease -> {
            leases.put(identity, lease);
            acquiredLeases.incrementAndGet();
        });
        return reclaimed;
    }

    public int renewOnce() {
        int renewed = 0;
        for (AgentDirectoryLease lease : List.copyOf(leases.values())) {
            try {
                Optional<AgentDirectoryLease> next = directory.renew(lease, leaseTtl);
                if (next.isPresent()) {
                    if (leases.replace(lease.identity(), lease, next.orElseThrow())) {
                        renewed++;
                        renewedLeases.incrementAndGet();
                    }
                } else {
                    lose(lease);
                }
            } catch (RuntimeException failure) {
                renewalFailures.incrementAndGet();
                if (!lease.validAt(clock.instant())) {
                    lose(lease);
                }
            }
        }
        return renewed;
    }

    public Optional<AgentDirectoryLease> lease(AgentIdentity identity) {
        return Optional.ofNullable(leases.get(Objects.requireNonNull(identity, "identity")));
    }

    public void forget(AgentIdentity identity) {
        leases.remove(Objects.requireNonNull(identity, "identity"));
    }

    public AgentLeaseSupervisorStats stats() {
        return new AgentLeaseSupervisorStats(
                leases.size(),
                acquiredLeases.get(),
                renewedLeases.get(),
                renewalFailures.get(),
                lostLeases.get()
        );
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        started.set(false);
        for (AgentIdentity identity : List.copyOf(leases.keySet())) {
            try {
                release(identity);
            } catch (RuntimeException ignored) {
            }
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void renewSafely() {
        try {
            renewOnce();
        } catch (RuntimeException ignored) {
            renewalFailures.incrementAndGet();
        }
    }

    private void lose(AgentDirectoryLease lease) {
        if (!leases.remove(lease.identity(), lease)) {
            return;
        }
        lostLeases.incrementAndGet();
        lossHandler.leaseLost(lease);
    }

    private static String requireOwnerSession(String value) {
        Objects.requireNonNull(value, "ownerSession");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ownerSession must not be blank");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
