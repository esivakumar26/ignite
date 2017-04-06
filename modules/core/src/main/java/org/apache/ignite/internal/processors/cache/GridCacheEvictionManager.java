/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.eviction.EvictionFilter;
import org.apache.ignite.cache.eviction.EvictionPolicy;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.IgniteFutureCancelledCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.managers.eventstorage.GridLocalEventListener;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCacheEntry;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtInvalidPartitionException;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxEntry;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.timeout.GridTimeoutObject;
import org.apache.ignite.internal.processors.timeout.GridTimeoutObjectAdapter;
import org.apache.ignite.internal.util.F0;
import org.apache.ignite.internal.util.GridBusyLock;
import org.apache.ignite.internal.util.GridConcurrentHashSet;
import org.apache.ignite.internal.util.GridUnsafe;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.lang.GridMetadataAwareAdapter;
import org.apache.ignite.internal.util.lang.IgnitePair;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.C1;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.CI2;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.P1;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.util.worker.GridWorker;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.thread.IgniteThread;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;
import org.jsr166.ConcurrentLinkedDeque8;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.ignite.cache.CacheMode.LOCAL;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.events.EventType.EVT_CACHE_ENTRY_EVICTED;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_JOINED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.processors.cache.GridCacheUtils.isNearEnabled;
import static org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState.MOVING;
import static org.jsr166.ConcurrentLinkedDeque8.Node;

/**
 * TODO GG-11140 (old evictions implementation, now created for near cache, evictions to be reconsidered as part of GG-11140).
 */
public class GridCacheEvictionManager extends GridCacheManagerAdapter implements CacheEvictionManager {
    /** Attribute name used to queue node in entry metadata. */
    private static final int META_KEY = GridMetadataAwareAdapter.EntryKey.CACHE_EVICTION_MANAGER_KEY.key();

    /** Eviction policy. */
    private EvictionPolicy plc;

    /** Eviction filter. */
    private EvictionFilter filter;

    /** Policy enabled. */
    private boolean plcEnabled;

    /** Busy lock. */
    private final GridBusyLock busyLock = new GridBusyLock();

    /** Stopped flag. */
    private boolean stopped;

    /** First eviction flag. */
    private volatile boolean firstEvictWarn;

    /** {@inheritDoc} */
    @Override public void start0() throws IgniteCheckedException {
        CacheConfiguration cfg = cctx.config();

        plc = cctx.isNear() ? cfg.getNearConfiguration().getNearEvictionPolicy() : cfg.getEvictionPolicy();

        plcEnabled = plc != null;

        filter = cfg.getEvictionFilter();

        if (log.isDebugEnabled())
            log.debug("Eviction manager started on node: " + cctx.nodeId());
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStop0(boolean cancel) {
        super.onKernalStop0(cancel);

        busyLock.block();

        try {
            if (log.isDebugEnabled())
                log.debug("Eviction manager stopped on node: " + cctx.nodeId());
        }
        finally {
            stopped = true;

            busyLock.unblock();
        }
    }

    /**
     * @return {@code True} if entered busy.
     */
    private boolean enterBusy() {
        if (!busyLock.enterBusy())
            return false;

        if (stopped) {
            busyLock.leaveBusy();

            return false;
        }

        return true;
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void sendEvictionResponse(UUID nodeId, GridCacheEvictionResponse res) {
        try {
            cctx.io().send(nodeId, res, cctx.ioPolicy());

            if (log.isDebugEnabled())
                log.debug("Sent eviction response [node=" + nodeId + ", localNode=" + cctx.nodeId() +
                    ", res" + res + ']');
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (log.isDebugEnabled())
                log.debug("Failed to send eviction response since initiating node left grid " +
                    "[node=" + nodeId + ", localNode=" + cctx.nodeId() + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send eviction response to node [node=" + nodeId +
                ", localNode=" + cctx.nodeId() + ", res" + res + ']', e);
        }
    }

    /**
     * @param key Key.
     * @param ver Version.
     * @param p Partition ID.
     */
    private void saveEvictionInfo(KeyCacheObject key, GridCacheVersion ver, int p) {
        assert cctx.rebalanceEnabled();

        if (!cctx.isNear()) {
            try {
                GridDhtLocalPartition part = cctx.dht().topology().localPartition(p,
                    AffinityTopologyVersion.NONE, false);

                assert part != null;

                part.onEntryEvicted(key, ver);
            }
            catch (GridDhtInvalidPartitionException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Partition does not belong to local node [part=" + p +
                        ", nodeId" + cctx.localNode().id() + ']');
            }
        }
        else
            assert false : "Failed to save eviction info: " + cctx.namexx();
    }

    /**
     * @param p Partition ID.
     * @return {@code True} if partition has been actually locked,
     *      {@code false} if preloading is finished or disabled and no lock is needed.
     */
    private boolean lockPartition(int p) {
        if (!cctx.rebalanceEnabled())
            return false;

        if (!cctx.isNear()) {
            try {
                GridDhtLocalPartition part = cctx.dht().topology().localPartition(p, AffinityTopologyVersion.NONE,
                    false);

                if (part != null && part.reserve()) {
                    part.lock();

                    if (part.state() != MOVING) {
                        part.unlock();

                        part.release();

                        return false;
                    }

                    return true;
                }
            }
            catch (GridDhtInvalidPartitionException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Partition does not belong to local node [part=" + p +
                        ", nodeId" + cctx.localNode().id() + ']');
            }
        }

        // No lock is needed.
        return false;
    }

    /**
     * @param p Partition ID.
     */
    private void unlockPartition(int p) {
        if (!cctx.rebalanceEnabled())
            return;

        if (!cctx.isNear()) {
            try {
                GridDhtLocalPartition part = cctx.dht().topology().localPartition(p, AffinityTopologyVersion.NONE,
                    false);

                if (part != null) {
                    part.unlock();

                    part.release();
                }
            }
            catch (GridDhtInvalidPartitionException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Partition does not belong to local node [part=" + p +
                        ", nodeId" + cctx.localNode().id() + ']');
            }
        }
    }

    /**
     * Locks topology (for DHT cache only) and returns its version.
     *
     * @return Topology version after lock.
     */
    private AffinityTopologyVersion lockTopology() {
        if (!cctx.isNear()) {
            cctx.dht().topology().readLock();

            return cctx.dht().topology().topologyVersion();
        }

        return AffinityTopologyVersion.ZERO;
    }

    /**
     * Unlocks topology.
     */
    private void unlockTopology() {
        if (!cctx.isNear())
            cctx.dht().topology().readUnlock();
    }

    /**
     * @param cache Cache from which to evict entry.
     * @param entry Entry to evict.
     * @param obsoleteVer Obsolete version.
     * @param filter Filter.
     * @param explicit If eviction is initiated by user.
     * @return {@code true} if entry has been evicted.
     * @throws IgniteCheckedException If failed to evict entry.
     */
    private boolean evict0(
        GridCacheAdapter cache,
        GridCacheEntryEx entry,
        GridCacheVersion obsoleteVer,
        @Nullable CacheEntryPredicate[] filter,
        boolean explicit
    ) throws IgniteCheckedException {
        assert cache != null;
        assert entry != null;
        assert obsoleteVer != null;

        boolean recordable = cctx.events().isRecordable(EVT_CACHE_ENTRY_EVICTED);

        CacheObject oldVal = recordable ? entry.rawGet() : null;

        boolean hasVal = recordable && entry.hasValue();

        boolean evicted = entry.evictInternal(obsoleteVer, filter);

        if (evicted) {
            // Remove manually evicted entry from policy.
            if (explicit && plcEnabled)
                notifyPolicy(entry);

            cache.removeEntry(entry);

            if (cache.configuration().isStatisticsEnabled())
                cache.metrics0().onEvict();

            if (recordable)
                cctx.events().addEvent(entry.partition(), entry.key(), cctx.nodeId(), (IgniteUuid)null, null,
                    EVT_CACHE_ENTRY_EVICTED, null, false, oldVal, hasVal, null, null, null, false);

            if (log.isDebugEnabled())
                log.debug("Entry was evicted [entry=" + entry + ", localNode=" + cctx.nodeId() + ']');
        }
        else {
            if (log.isDebugEnabled())
                log.debug("Entry was not evicted [entry=" + entry + ", localNode=" + cctx.nodeId() + ']');
        }

        return evicted;
    }

    /** {@inheritDoc} */
    @Override public void touch(IgniteTxEntry txEntry, boolean loc) {
        if (!plcEnabled)
            return;

        if (!loc) {
            if (cctx.isNear())
                return;
        }

        GridCacheEntryEx e = txEntry.cached();

        if (e.detached() || e.isInternal())
            return;

        try {
            if (e.markObsoleteIfEmpty(null) || e.obsolete())
                e.context().cache().removeEntry(e);
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to evict entry from cache: " + e, ex);
        }

        notifyPolicy(e);
    }

    /** {@inheritDoc} */
    @Override public void touch(GridCacheEntryEx e, AffinityTopologyVersion topVer) {
        if (e.detached() || e.isInternal())
            return;

        try {
            if (e.markObsoleteIfEmpty(null) || e.obsolete())
                e.context().cache().removeEntry(e);
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to evict entry from cache: " + e, ex);
        }

        if (!plcEnabled)
            return;

        if (!enterBusy())
            return;

        try {
            if (log.isDebugEnabled())
                log.debug("Touching entry [entry=" + e + ", localNode=" + cctx.nodeId() + ']');

            notifyPolicy(e);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Warns on first eviction.
     */
    private void warnFirstEvict() {
        // Do not move warning output to synchronized block (it causes warning in IDE).
        synchronized (this) {
            if (firstEvictWarn)
                return;

            firstEvictWarn = true;
        }

        U.warn(log, "Evictions started (cache may have reached its capacity)." +
                " You may wish to increase 'maxSize' on eviction policy being used for cache: " + cctx.name(),
            "Evictions started (cache may have reached its capacity): " + cctx.name());
    }

    /** {@inheritDoc} */
    @Override public boolean evict(@Nullable GridCacheEntryEx entry, @Nullable GridCacheVersion obsoleteVer,
        boolean explicit, @Nullable CacheEntryPredicate[] filter) throws IgniteCheckedException {
        if (entry == null)
            return true;

        // Do not evict internal entries.
        if (entry.key() instanceof GridCacheInternal)
            return false;

        if (!cctx.isNear() && !explicit && !firstEvictWarn)
            warnFirstEvict();

        if (obsoleteVer == null)
            obsoleteVer = cctx.versions().next();

        // Do not touch entry if not evicted:
        // 1. If it is call from policy, policy tracks it on its own.
        // 2. If it is explicit call, entry is touched on tx commit.
        return evict0(cctx.cache(), entry, obsoleteVer, filter, explicit);
    }

    /** {@inheritDoc} */
    @Override public void batchEvict(Collection<?> keys, @Nullable GridCacheVersion obsoleteVer)
        throws IgniteCheckedException {
        List<GridCacheEntryEx> locked = new ArrayList<>(keys.size());

        Set<GridCacheEntryEx> notRmv = null;

        Collection<GridCacheBatchSwapEntry> swapped = new ArrayList<>(keys.size());

        boolean recordable = cctx.events().isRecordable(EVT_CACHE_ENTRY_EVICTED);

        GridCacheAdapter cache = cctx.cache();

        Map<Object, GridCacheEntryEx> cached = U.newLinkedHashMap(keys.size());

        // Get all participating entries to avoid deadlock.
        for (Object k : keys) {
            KeyCacheObject cacheKey = cctx.toCacheKeyObject(k);

            GridCacheEntryEx e = cache.peekEx(cacheKey);

            if (e != null)
                cached.put(k, e);
        }

        try {
            for (GridCacheEntryEx entry : cached.values()) {
                // Do not evict internal entries.
                if (entry.key().internal())
                    continue;

                // Lock entry.
                GridUnsafe.monitorEnter(entry);

                locked.add(entry);

                if (entry.obsolete()) {
                    if (notRmv == null)
                        notRmv = new HashSet<>();

                    notRmv.add(entry);

                    continue;
                }

                if (obsoleteVer == null)
                    obsoleteVer = cctx.versions().next();

                GridCacheBatchSwapEntry swapEntry = entry.evictInBatchInternal(obsoleteVer);

                if (swapEntry != null) {
                    assert entry.obsolete() : entry;

                    swapped.add(swapEntry);

                    if (log.isDebugEnabled())
                        log.debug("Entry was evicted [entry=" + entry + ", localNode=" + cctx.nodeId() + ']');
                }
                else if (!entry.obsolete()) {
                    if (notRmv == null)
                        notRmv = new HashSet<>();

                    notRmv.add(entry);
                }
            }

            // Batch write to swap.
            if (!swapped.isEmpty())
                cctx.offheap().writeAll(swapped);
        }
        finally {
            // Unlock entries in reverse order.
            for (ListIterator<GridCacheEntryEx> it = locked.listIterator(locked.size()); it.hasPrevious(); ) {
                GridCacheEntryEx e = it.previous();

                GridUnsafe.monitorExit(e);
            }

            // Remove entries and fire events outside the locks.
            for (GridCacheEntryEx entry : locked) {
                if (entry.obsolete() && (notRmv == null || !notRmv.contains(entry))) {
                    entry.onMarkedObsolete();

                    cache.removeEntry(entry);

                    if (plcEnabled)
                        notifyPolicy(entry);

                    if (recordable)
                        cctx.events().addEvent(entry.partition(), entry.key(), cctx.nodeId(), (IgniteUuid)null, null,
                            EVT_CACHE_ENTRY_EVICTED, null, false, entry.rawGet(), entry.hasValue(), null, null, null,
                            false);
                }
            }
        }
    }

    /**
     * @param info Eviction info.
     * @return Version aware filter.
     */
    private CacheEntryPredicate[] versionFilter(final EvictionInfo info) {
        // If version has changed since we started the whole process
        // then we should not evict entry.
        return new CacheEntryPredicate[] {
            new CacheEntryPredicateAdapter() {
                @Override public boolean apply(GridCacheEntryEx e) {
                    try {
                        GridCacheVersion ver = e.version();

                        return info.version().equals(ver) && F.isAll(info.filter());
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        return false;
                    }
                }
            }};
    }

    /**
     * @param e Entry to notify eviction policy.
     */
    @SuppressWarnings({"IfMayBeConditional", "RedundantIfStatement"})
    private void notifyPolicy(GridCacheEntryEx e) {
        assert plcEnabled;
        assert plc != null;
        assert !e.isInternal() : "Invalid entry for policy notification: " + e;

        if (log.isDebugEnabled())
            log.debug("Notifying eviction policy with entry: " + e);

        if (filter == null || filter.evictAllowed(e.wrapLazyValue(cctx.keepBinary())))
            plc.onEntryAccessed(e.obsoleteOrDeleted(), e.wrapEviction());
    }

    /**
     * Prints out eviction stats.
     */
    public void printStats() {
        X.println("Eviction stats [igniteInstanceName=" + cctx.igniteInstanceName() +
            ", cache=" + cctx.cache().name() + ']');
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>> ");
        X.println(">>> Eviction manager memory stats [igniteInstanceName=" + cctx.igniteInstanceName() +
            ", cache=" + cctx.name() + ']');
    }

    /**
     * Wrapper around an entry to be put into queue.
     */
    private class EvictionInfo {
        /** Cache entry. */
        private GridCacheEntryEx entry;

        /** Start version. */
        private GridCacheVersion ver;

        /** Filter to pass before entry will be evicted. */
        private CacheEntryPredicate[] filter;

        /**
         * @param entry Entry.
         * @param ver Version.
         * @param filter Filter.
         */
        EvictionInfo(GridCacheEntryEx entry, GridCacheVersion ver,
            CacheEntryPredicate[] filter) {
            assert entry != null;
            assert ver != null;

            this.entry = entry;
            this.ver = ver;
            this.filter = filter;
        }

        /**
         * @return Entry.
         */
        GridCacheEntryEx entry() {
            return entry;
        }

        /**
         * @return Version.
         */
        GridCacheVersion version() {
            return ver;
        }

        /**
         * @return Filter.
         */
        CacheEntryPredicate[] filter() {
            return filter;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(EvictionInfo.class, this);
        }
    }
}
