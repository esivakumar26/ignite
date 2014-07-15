/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.query;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.gridgain.grid.kernal.processors.cache.query.GridCacheQueryType.*;

/**
 * {@link GridCacheQueries} implementation.
 */
public class GridCacheQueriesImpl<K, V> implements GridCacheQueriesEx<K, V> {
    /** */
    private final GridCacheContext<K, V> ctx;

    /** */
    private GridCacheProjectionImpl<K, V> prj;

    /**
     * @param ctx Context.
     * @param prj Projection.
     */
    public GridCacheQueriesImpl(GridCacheContext<K, V> ctx, @Nullable GridCacheProjectionImpl<K, V> prj) {
        assert ctx != null;

        this.ctx = ctx;
        this.prj = prj;
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<Map.Entry<K, V>> createSqlQuery(Class<?> cls, String clause) {
        A.notNull(cls, "cls");
        A.notNull(clause, "clause");

        return new GridCacheQueryAdapter<>(ctx, SQL, filter(), U.box(cls).getSimpleName(), clause, null, false,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<Map.Entry<K, V>> createSqlQuery(String clsName, String clause) {
        A.notNull("clsName", clsName);
        A.notNull("clause", clause);

        return new GridCacheQueryAdapter<>(ctx, SQL, filter(), clsName, clause, null, false,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<List<?>> createSqlFieldsQuery(String qry) {
        A.notNull(qry, "qry");

        return new GridCacheQueryAdapter<>(ctx, SQL_FIELDS, filter(), null, qry, null, false,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<Map.Entry<K, V>> createFullTextQuery(Class<?> cls, String search) {
        A.notNull(cls, "cls");
        A.notNull(search, "search");

        return new GridCacheQueryAdapter<>(ctx, TEXT, filter(), U.box(cls).getSimpleName(), search, null, false,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<Map.Entry<K, V>> createFullTextQuery(String clsName, String search) {
        A.notNull("clsName", clsName);
        A.notNull("search", search);

        return new GridCacheQueryAdapter<>(ctx, TEXT, filter(), clsName, search, null, false,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridCacheQuery<Map.Entry<K, V>> createScanQuery(@Nullable GridBiPredicate<K, V> filter) {
        return new GridCacheQueryAdapter<>(ctx, SCAN, filter(), null, null, (GridBiPredicate<Object, Object>)filter,
            false, prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /** {@inheritDoc} */
    @Override public GridCacheContinuousQuery<K, V> createContinuousQuery() {
        return ctx.continuousQueries().createQuery(prj == null ? null : prj.predicate());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> rebuildIndexes(Class<?> cls) {
        A.notNull(cls, "cls");

        return ctx.queries().rebuildIndexes(cls);
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> rebuildIndexes(String typeName) {
        A.notNull("typeName", typeName);

        return ctx.queries().rebuildIndexes(typeName);
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> rebuildAllIndexes() {
        return ctx.queries().rebuildAllIndexes();
    }

    /** {@inheritDoc} */
    @Override public GridCacheQueryMetrics metrics() {
        return ctx.queries().metrics();
    }

    /** {@inheritDoc} */
    @Override public void resetMetrics() {
        ctx.queries().resetMetrics();
    }

    /** {@inheritDoc} */
    @Override public Collection<GridCacheSqlMetadata> sqlMetadata() throws GridException {
        return ctx.queries().sqlMetadata();
    }

    /** {@inheritDoc} */
    @Override public GridCacheQuery<List<?>> createSqlFieldsQuery(String qry, boolean incMeta) {
        assert qry != null;

        return new GridCacheQueryAdapter<>(ctx, SQL_FIELDS, filter(), null, qry, null, incMeta,
            prj != null && prj.portableKeys(), prj != null && prj.portableValues());
    }

    /**
     * @return Optional projection filter.
     */
    @SuppressWarnings("unchecked")
    @Nullable private GridPredicate<GridCacheEntry<Object, Object>> filter() {
        return prj == null ? null : ((GridCacheProjectionImpl<Object, Object>)prj).predicate();
    }
}
