/**
 * Copyright (C) 2007 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA
 */

package org.hibernate.shards.criteria;

import org.hibernate.CacheMode;
import org.hibernate.Criteria;
import org.hibernate.FetchMode;
import org.hibernate.FlushMode;
import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.criterion.AvgProjection;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projection;
import org.hibernate.criterion.ProjectionList;
import org.hibernate.criterion.Projections;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.shards.Shard;
import org.hibernate.shards.ShardOperation;
import org.hibernate.shards.strategy.access.ShardAccessStrategy;
import org.hibernate.shards.strategy.exit.ConcatenateListsExitStrategy;
import org.hibernate.shards.strategy.exit.FirstNonNullResultExitStrategy;
import org.hibernate.sql.JoinType;
import org.hibernate.transform.ResultTransformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Concrete implementation of the {@link ShardedCriteria} interface.
 *
 * @author maxr@google.com (Max Ross)
 */
public class ShardedCriteriaImpl implements ShardedCriteria {

    private static final Iterable<CriteriaEvent> NO_CRITERIA_EVENTS =
            Collections.unmodifiableList(new ArrayList<CriteriaEvent>());

    // unique id for this ShardedCriteria
    private final CriteriaId criteriaId;

    // the shards we know about
    private final List<Shard> shards;

    // a factory that knows how to create actual Criteria objects
    private final CriteriaFactory criteriaFactory;

    // the shard access strategy we use when we execute the Criteria
    // across multiple shards
    private final ShardAccessStrategy shardAccessStrategy;

    // the criteria collector we use to process the results of executing
    // the Criteria across multiple shards
    private final ExitOperationsCriteriaCollector criteriaCollector;

    private Boolean readOnly;

    /**
     * Construct a ShardedCriteriaImpl
     *
     * @param criteriaId          unique id for this ShardedCriteria
     * @param shards              the shards that this ShardedCriteria is aware of
     * @param criteriaFactory     factory that knows how to create concrete {@link Criteria} objects
     * @param shardAccessStrategy the access strategy we use when we execute this
     *                            ShardedCriteria across multiple shards.
     */
    public ShardedCriteriaImpl(final CriteriaId criteriaId,
                               final List<Shard> shards,
                               final CriteriaFactory criteriaFactory,
                               final ShardAccessStrategy shardAccessStrategy) {

        this.criteriaId = criteriaId;
        this.shards = shards;
        this.criteriaFactory = criteriaFactory;
        this.shardAccessStrategy = shardAccessStrategy;
        this.criteriaCollector = new ExitOperationsCriteriaCollector();
        criteriaCollector.setSessionFactory(shards.get(0).getSessionFactoryImplementor());
    }

    @Override
    public CriteriaId getCriteriaId() {
        return criteriaId;
    }

    @Override
    public CriteriaFactory getCriteriaFactory() {
        return criteriaFactory;
    }

    @Override
    public String getAlias() {
        return getOrEstablishSomeCriteria().getAlias();
    }

    @Override
    public Criteria setProjection(final Projection projection) {
        criteriaCollector.addProjection(projection);
        if (projection instanceof AvgProjection) {
            setAvgProjection(projection);
        }
        // TODO - handle ProjectionList
        return this;
    }

    @Override
    public Criteria add(final Criterion criterion) {
        CriteriaEvent event = new AddCriterionEvent(criterion);
        for (Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).add(criterion);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria addOrder(final Order order) {
        criteriaCollector.addOrder(order);
        return this;
    }

    @Override
    public Criteria setFetchMode(final String associationPath, final FetchMode mode) throws HibernateException {
        final CriteriaEvent event = new SetFetchModeEvent(associationPath, mode);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setFetchMode(associationPath, mode);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setLockMode(final LockMode lockMode) {
        final CriteriaEvent event = new SetLockModeEvent(lockMode);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setLockMode(lockMode);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setLockMode(final String alias, final LockMode lockMode) {
        CriteriaEvent event = new SetLockModeEvent(lockMode, alias);
        for (Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setLockMode(lockMode);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria createAlias(final String associationPath, final String alias) throws HibernateException {
        final CriteriaEvent event = new CreateAliasEvent(associationPath, alias);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).createAlias(associationPath, alias);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria createAlias(final String associationPath, final String alias, final JoinType joinType)
            throws HibernateException {

        final CriteriaEvent event = new CreateAliasEvent(associationPath, alias, joinType);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).createAlias(associationPath, alias, joinType);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }

        return this;
    }

    @Override
    @Deprecated
    public Criteria createAlias(final String associationPath, final String alias, final int joinType)
            throws HibernateException {

        return createAlias(associationPath, alias, JoinType.parse(joinType));
    }

    @Override
    public Criteria createAlias(final String associationPath, final String alias, final JoinType joinType,
                                final Criterion withClause) throws HibernateException {

        final CriteriaEvent event = new CreateAliasEvent(associationPath, alias, joinType, withClause);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).createAlias(associationPath, alias, joinType);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }

        return this;
    }

    @Override
    public Criteria createAlias(String associationPath, String alias, int joinType, Criterion withClause) throws HibernateException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Criteria createCriteria(final String associationPath) throws HibernateException {
        final SubcriteriaFactory factory = new SubcriteriaFactoryImpl(associationPath);
        return createSubcriteria(factory);
    }

    @Override
    public Criteria createCriteria(final String associationPath, final JoinType joinType) throws HibernateException {
        final SubcriteriaFactory factory = new SubcriteriaFactoryImpl(associationPath, joinType);
        return createSubcriteria(factory);
    }

    @Override
    @Deprecated
    public Criteria createCriteria(final String associationPath, final int joinType) throws HibernateException {
        return createCriteria(associationPath, JoinType.parse(joinType));
    }

    @Override
    public Criteria createCriteria(final String associationPath, final String alias) throws HibernateException {
        final SubcriteriaFactory factory = new SubcriteriaFactoryImpl(associationPath, alias);
        return createSubcriteria(factory);
    }

    @Override
    public Criteria createCriteria(final String associationPath, final String alias, final JoinType joinType)
            throws HibernateException {

        final SubcriteriaFactory factory = new SubcriteriaFactoryImpl(associationPath, alias, joinType);
        return createSubcriteria(factory);
    }

    @Override
    public Criteria createCriteria(final String associationPath, final String alias, final int joinType)
            throws HibernateException {

        return createCriteria(associationPath, alias, JoinType.parse(joinType));
    }

    @Override
    public Criteria createCriteria(final String associationPath, final String alias, final JoinType joinType,
                                   final Criterion withClause) throws HibernateException {

        final SubcriteriaFactory factory = new SubcriteriaFactoryImpl(associationPath, alias, joinType, withClause);
        return createSubcriteria(factory);
    }

    @Override
    @Deprecated
    public Criteria createCriteria(String associationPath, String alias, int joinType, Criterion withClause) throws HibernateException {
        return createCriteria(associationPath, alias, JoinType.parse(joinType), withClause);
    }

    @Override
    public Criteria setResultTransformer(final ResultTransformer resultTransformer) {
        final CriteriaEvent event = new SetResultTransformerEvent(resultTransformer);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setResultTransformer(resultTransformer);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setMaxResults(final int maxResults) {
        criteriaCollector.setMaxResults(maxResults);
        return this;
    }

    @Override
    public Criteria setFirstResult(final int firstResult) {
        criteriaCollector.setFirstResult(firstResult);
        return this;
    }

    @Override
    public boolean isReadOnlyInitialized() {
        return readOnly != null;
    }

    @Override
    public boolean isReadOnly() {
        if ( ! isReadOnlyInitialized() && (shards == null || shards.isEmpty()) ) {
            throw new IllegalStateException(
                    "cannot determine readOnly/modifiable setting when it is not initialized and is not initialized and getSession() == null"
            );
        }

        boolean defaultReadOnly = true;
        for (final Shard shard : shards) {
            final SessionImplementor session = (SessionImplementor)shard.getSessionFactoryImplementor().getCurrentSession();
            if (session != null) {
                defaultReadOnly &= session.getPersistenceContext().isDefaultReadOnly();
            }
        }

        return ( isReadOnlyInitialized() ? readOnly : defaultReadOnly );
    }

    @Override
    public Criteria setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    @Override
    public Criteria setFetchSize(final int fetchSize) {
        final CriteriaEvent event = new SetFetchSizeEvent(fetchSize);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setFetchSize(fetchSize);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setTimeout(final int timeout) {
        final CriteriaEvent event = new SetTimeoutEvent(timeout);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setTimeout(timeout);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setCacheable(final boolean cacheable) {
        final CriteriaEvent event = new SetCacheableEvent(cacheable);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setCacheable(cacheable);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setCacheRegion(final String cacheRegion) {
        final CriteriaEvent event = new SetCacheRegionEvent(cacheRegion);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setCacheRegion(cacheRegion);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setComment(final String comment) {
        final CriteriaEvent event = new SetCommentEvent(comment);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setComment(comment);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setFlushMode(final FlushMode flushMode) {
        final CriteriaEvent event = new SetFlushModeEvent(flushMode);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setFlushMode(flushMode);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    @Override
    public Criteria setCacheMode(final CacheMode cacheMode) {
        final CriteriaEvent event = new SetCacheModeEvent(cacheMode);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setCacheMode(cacheMode);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return this;
    }

    /**
     * Unsupported.  This is a scope decision, not a technical decision.
     */
    @Override
    public ScrollableResults scroll() throws HibernateException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported.  This is a scope decision, not a technical decision.
     */
    @Override
    public ScrollableResults scroll(final ScrollMode scrollMode) throws HibernateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List list() throws HibernateException {

        // build a shard operation and apply it across all shards
        final ShardOperation<List<Object>> shardOp = new ShardOperation<List<Object>>() {
            public List<Object> execute(final Shard shard) {
                shard.establishCriteria(ShardedCriteriaImpl.this);
                return shard.list(criteriaId);
            }

            public String getOperationName() {
                return "list()";
            }
        };

        /**
         * We don't support shard selection for criteria queries.  If you want
         * custom shards, create a ShardedSession with only the shards you want.
         * We're going to concatenate all our results and then use our
         * criteria collector to do post processing.
         */
        return shardAccessStrategy.apply(
                shards,
                shardOp,
                new ConcatenateListsExitStrategy(),
                criteriaCollector);
    }

    @Override
    public Object uniqueResult() throws HibernateException {

        // build a shard operation and apply it across all shards
        final ShardOperation<Object> shardOp = new ShardOperation<Object>() {
            public Object execute(Shard shard) {
                shard.establishCriteria(ShardedCriteriaImpl.this);
                return shard.uniqueResult(criteriaId);
            }

            public String getOperationName() {
                return "uniqueResult()";
            }
        };

        /**
         * We don't support shard selection for criteria queries.  If you want
         * custom shards, create a ShardedSession with only the shards you want.
         * We're going to return the first non-null result we get from a shard.
         */
        return shardAccessStrategy.apply(
                shards,
                shardOp,
                new FirstNonNullResultExitStrategy<Object>(),
                criteriaCollector);
    }

    /**
     * @return any Criteria, or null if we don't have one
     */
    private /*@Nullable*/ Criteria getSomeCriteria() {
        for (final Shard shard : shards) {
            final Criteria crit = shard.getCriteriaById(criteriaId);
            if (crit != null) {
                return crit;
            }
        }
        return null;
    }

    /**
     * @return any Criteria.  If no Criteria has been established we establish
     *         one and return it.
     */
    private Criteria getOrEstablishSomeCriteria() {
        Criteria crit = getSomeCriteria();
        if (crit == null) {
            final Shard shard = shards.get(0);
            crit = shard.establishCriteria(this);
        }
        return crit;
    }

    private void setAvgProjection(final Projection projection) {

        // We need to modify the query to pull back not just the average but also
        // the count.  We'll do this by creating a ProjectionList with both the
        // average and the row count.
        final ProjectionList projectionList = Projections.projectionList();
        projectionList.add(projection);
        projectionList.add(Projections.rowCount());

        final CriteriaEvent event = new SetProjectionEvent(projectionList);
        for (final Shard shard : shards) {
            if (shard.getCriteriaById(criteriaId) != null) {
                shard.getCriteriaById(criteriaId).setProjection(projectionList);
            } else {
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
    }

    /**
     * Creating sharded subcriteria is tricky.  We need to give the client a
     * reference to a ShardedSubcriteriaImpl (which to the client just looks like
     * a Criteria object).  Then, for each shard where the Criteria has already been
     * established we need to create the actual subcriteria, and for each shard
     * where the Criteria has not yet been established we need to register an
     * event that will create the Subcriteria when the Criteria is established.
     */
    private ShardedSubcriteriaImpl createSubcriteria(final SubcriteriaFactory factory) {

        final ShardedSubcriteriaImpl subcrit = new ShardedSubcriteriaImpl(shards, this);

        for (final Shard shard : shards) {
            final Criteria crit = shard.getCriteriaById(criteriaId);
            if (crit != null) {
                factory.createSubcriteria(crit, NO_CRITERIA_EVENTS);
            } else {
                CreateSubcriteriaEvent event = new CreateSubcriteriaEvent(factory, subcrit.getSubcriteriaRegistrar(shard));
                shard.addCriteriaEvent(criteriaId, event);
            }
        }
        return subcrit;
    }
}
