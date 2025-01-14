/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.event.internal;

import org.hibernate.HibernateException;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.ResolveNaturalIdEvent;
import org.hibernate.event.spi.ResolveNaturalIdEventListener;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.stat.spi.StatisticsImplementor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Defines the default load event listeners used by hibernate for loading entities
 * in response to generated load events.
 *
 * @author Eric Dalquist
 * @author Steve Ebersole
 */
public class DefaultResolveNaturalIdEventListener
		extends AbstractLockUpgradeEventListener
		implements ResolveNaturalIdEventListener {

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( DefaultResolveNaturalIdEventListener.class );

	@Override
	public void onResolveNaturalId(ResolveNaturalIdEvent event) throws HibernateException {
		event.setEntityId( resolveNaturalId( event ) );
	}

	/**
	 * Coordinates the efforts to load a given entity. First, an attempt is
	 * made to load the entity from the session-level cache. If not found there,
	 * an attempt is made to locate it in second-level cache. Lastly, an
	 * attempt is made to load it directly from the datasource.
	 *
	 * @param event The load event
	 *
	 * @return The loaded entity, or null.
	 */
	protected Object resolveNaturalId(final ResolveNaturalIdEvent event) {
		final EntityPersister persister = event.getEntityPersister();

		if ( LOG.isTraceEnabled() ) {
			LOG.tracev(
					"Attempting to resolve: {0}#{1}",
					MessageHelper.infoString( persister ),
					event.getNaturalIdValues()
			);
		}

		Object entityId = resolveFromCache( event );
		if ( entityId != null ) {
			if ( LOG.isTraceEnabled() ) {
				LOG.tracev(
						"Resolved object in cache: {0}#{1}",
						MessageHelper.infoString( persister ),
						event.getNaturalIdValues()
				);
			}
			return entityId;
		}

		if ( LOG.isTraceEnabled() ) {
			LOG.tracev(
					"Object not resolved in any cache: {0}#{1}",
					MessageHelper.infoString( persister ),
					event.getNaturalIdValues()
			);
		}

		return loadFromDatasource( event );
	}

	/**
	 * Attempts to resolve the entity id corresponding to the event's natural id values from the session
	 *
	 * @param event The load event
	 *
	 * @return The entity from the cache, or null.
	 */
	protected Object resolveFromCache(final ResolveNaturalIdEvent event) {
		return event.getSession().getPersistenceContextInternal()
				.getNaturalIdResolutions()
				.findCachedIdByNaturalId( event.getOrderedNaturalIdValues(), event.getEntityPersister() );
	}

	/**
	 * Performs the process of loading an entity from the configured
	 * underlying datasource.
	 *
	 * @param event The load event
	 *
	 * @return The object loaded from the datasource, or null if not found.
	 */
	protected Object loadFromDatasource(final ResolveNaturalIdEvent event) {
		final EventSource session = event.getSession();
		final StatisticsImplementor statistics = session.getFactory().getStatistics();
		final boolean statisticsEnabled = statistics.isStatisticsEnabled();
		long startTime = 0;
		if ( statisticsEnabled ) {
			startTime = System.nanoTime();
		}

		final Object pk = event.getEntityPersister().loadEntityIdByNaturalId(
				event.getOrderedNaturalIdValues(),
				event.getLockOptions(),
				session
		);

		if ( statisticsEnabled ) {
			final long endTime = System.nanoTime();
			final long milliseconds = MILLISECONDS.convert( endTime - startTime, NANOSECONDS );
			statistics.naturalIdQueryExecuted( event.getEntityPersister().getRootEntityName(), milliseconds );
		}

		//PK can be null if the entity doesn't exist
		if ( pk != null ) {
			session.getPersistenceContextInternal()
					.getNaturalIdResolutions()
					.cacheResolutionFromLoad( pk, event.getOrderedNaturalIdValues(), event.getEntityPersister() );
		}
		return pk;
	}
}
