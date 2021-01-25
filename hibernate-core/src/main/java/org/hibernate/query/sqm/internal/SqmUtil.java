/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.Bindable;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.MappingModelExpressable;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.metamodel.model.domain.AllowableParameterType;
import org.hibernate.query.IllegalQueryOperationException;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.spi.QueryParameterBinding;
import org.hibernate.query.spi.QueryParameterBindings;
import org.hibernate.query.spi.QueryParameterImplementor;
import org.hibernate.query.sqm.spi.JdbcParameterBySqmParameterAccess;
import org.hibernate.query.sqm.tree.SqmDmlStatement;
import org.hibernate.query.sqm.tree.SqmStatement;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.exec.internal.JdbcParameterBindingImpl;
import org.hibernate.sql.exec.internal.JdbcParameterBindingsImpl;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.type.BasicType;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Helper utilities for dealing with SQM
 *
 * @author Steve Ebersole
 */
@SuppressWarnings("WeakerAccess")
public class SqmUtil {
	private SqmUtil() {
	}

	public static void verifyIsSelectStatement(SqmStatement sqm) {
		if ( !(sqm instanceof SqmSelectStatement) ) {
			throw new IllegalQueryOperationException(
					String.format(
							Locale.ROOT,
							"Expecting a SELECT Query [%s], but found %s",
							SqmSelectStatement.class.getName(),
							sqm.getClass().getName()
					)
			);
		}
	}

	public static void verifyIsNonSelectStatement(SqmStatement sqm) {
		if ( !(sqm instanceof SqmDmlStatement) ) {
			throw new IllegalQueryOperationException(
					String.format(
							Locale.ROOT,
							"Expecting a non-SELECT Query [%s], but found %s",
							SqmDmlStatement.class.getName(),
							sqm.getClass().getName()
					)
			);
		}
	}

	public static Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> generateJdbcParamsXref(
			DomainParameterXref domainParameterXref,
			JdbcParameterBySqmParameterAccess jdbcParameterBySqmParameterAccess) {
		if ( domainParameterXref == null || !domainParameterXref.hasParameters() ) {
			return Collections.emptyMap();
		}

		final int queryParameterCount = domainParameterXref.getQueryParameterCount();
		final Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> result = new IdentityHashMap<>( queryParameterCount );

		for ( Map.Entry<QueryParameterImplementor<?>, List<SqmParameter>> entry : domainParameterXref.getSqmParamByQueryParam().entrySet() ) {
			final QueryParameterImplementor<?> queryParam = entry.getKey();
			final List<SqmParameter> sqmParams = entry.getValue();

			final Map<SqmParameter, List<JdbcParameter>> sqmParamMap = result.computeIfAbsent(
					queryParam,
					qp -> new IdentityHashMap<>( sqmParams.size() )
			);

			for ( SqmParameter sqmParam : sqmParams ) {
				sqmParamMap.put( sqmParam, jdbcParameterBySqmParameterAccess.getJdbcParamsBySqmParam().get( sqmParam ) );

				final List<SqmParameter> expansions = domainParameterXref.getExpansions( sqmParam );
				if ( ! expansions.isEmpty() ) {
					for ( SqmParameter expansion : expansions ) {
						sqmParamMap.put( expansion, jdbcParameterBySqmParameterAccess.getJdbcParamsBySqmParam().get( expansion ) );
						result.put( queryParam, sqmParamMap );
					}
				}
			}
		}

		return result;
	}

//	public static JdbcParameterBindings buildJdbcParameterBindings(
//			SqmStatement sqmStatement,
//			JdbcParameterBySqmParameterAccess sqmInterpretation,
//			ExecutionContext executionContext) {
//		final DomainParameterXref domainParameterXref = DomainParameterXref.from( sqmStatement );
//		final Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> jdbcParamsXref =
//				generateJdbcParamsXref( domainParameterXref, sqmInterpretation );
//		return createJdbcParameterBindings(
//				executionContext.getDomainParameterBindingContext().getQueryParameterBindings(),
//				domainParameterXref,
//				jdbcParamsXref,
//				executionContext.getSession()
//		);
//	}

//	public static JdbcParameterBindings buildJdbcParameterBindings(
//			SqmStatement sqmStatement,
//			Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> jdbcParamsXref,
//			ExecutionContext executionContext) {
//		final DomainParameterXref domainParameterXref = DomainParameterXref.from( sqmStatement );
//		return createJdbcParameterBindings(
//				executionContext.getDomainParameterBindingContext().getQueryParameterBindings(),
//				domainParameterXref,
//				jdbcParamsXref,
//				executionContext.getSession()
//		);
//	}

//	public static JdbcParameterBindings buildJdbcParameterBindings(
//			DomainParameterXref domainParameterXref,
//			Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> jdbcParamsXref,
//			ExecutionContext executionContext) {
//		return createJdbcParameterBindings(
//				executionContext.getDomainParameterBindingContext().getQueryParameterBindings(),
//				domainParameterXref,
//				jdbcParamsXref,
//				executionContext.getSession()
//		);
//	}

	public static JdbcParameterBindings createJdbcParameterBindings(
			QueryParameterBindings domainParamBindings,
			DomainParameterXref domainParameterXref,
			Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> jdbcParamXref,
			MappingMetamodel domainModel,
			Function<NavigablePath, TableGroup> tableGroupLocator,
			SharedSessionContractImplementor session) {
		final JdbcParameterBindings jdbcParameterBindings = new JdbcParameterBindingsImpl(
				domainParameterXref.getSqmParameterCount()
		);

		for ( Map.Entry<QueryParameterImplementor<?>, List<SqmParameter>> entry :
				domainParameterXref.getSqmParamByQueryParam().entrySet() ) {
			final QueryParameterImplementor<?> queryParam = entry.getKey();
			final List<SqmParameter> sqmParameters = entry.getValue();

			final QueryParameterBinding<?> domainParamBinding = domainParamBindings.getBinding( queryParam );
			final AllowableParameterType<?> parameterType = determineParameterType(
					domainParamBinding,
					queryParam,
					session.getFactory()
			);

			final Map<SqmParameter, List<JdbcParameter>> jdbcParamMap = jdbcParamXref.get( queryParam );
			for ( SqmParameter sqmParameter : sqmParameters ) {
				final List<JdbcParameter> jdbcParams = jdbcParamMap.get( sqmParameter );

				if ( ! domainParamBinding.isBound() ) {
					final MappingModelExpressable mappingExpressable = SqmMappingModelHelper.resolveMappingModelExpressable(
							sqmParameter,
							domainModel,
							tableGroupLocator
					);
					mappingExpressable.forEachJdbcType(
							(position, jdbcType) -> {
								jdbcParameterBindings.addBinding(
										jdbcParams.get( position ),
										new JdbcParameterBindingImpl( jdbcType, null )
								);
							}
					);
				}
				else if ( domainParamBinding.isMultiValued() ) {
					final Collection<?> bindValues = domainParamBinding.getBindValues();
					final Iterator<?> valueItr = bindValues.iterator();

					// the original SqmParameter is the one we are processing.. create a binding for it..
					createValueBindings( jdbcParameterBindings, domainParamBinding, parameterType, jdbcParams, valueItr.next(), session );

					// an then one for each of the expansions
					final List<SqmParameter> expansions = domainParameterXref.getExpansions( sqmParameter );
					assert expansions.size() == bindValues.size() - 1;
					int expansionPosition = 0;
					while ( valueItr.hasNext() ) {
						final SqmParameter expansionSqmParam = expansions.get( expansionPosition++ );
						final List<JdbcParameter> expansionJdbcParams = jdbcParamMap.get( expansionSqmParam );
						createValueBindings( jdbcParameterBindings, domainParamBinding, parameterType, expansionJdbcParams, valueItr.next(), session );
					}
				}
				else if ( domainParamBinding.getBindValue() == null ) {
					assert jdbcParams != null;
					for ( int i = 0; i < jdbcParams.size(); i++ ) {
						final JdbcParameter jdbcParameter = jdbcParams.get( i );
						jdbcParameterBindings.addBinding(
								jdbcParameter,
								new JdbcParameterBindingImpl( null, null )
						);
					}
				}
				else {
					final Object bindValue = domainParamBinding.getBindValue();
					createValueBindings( jdbcParameterBindings, domainParamBinding, parameterType, jdbcParams, bindValue, session );
				}
			}
		}

		return jdbcParameterBindings;
	}

	private static void createValueBindings(
			JdbcParameterBindings jdbcParameterBindings,
			final QueryParameterBinding<?> domainParamBinding,
			AllowableParameterType<?> parameterType,
			List<JdbcParameter> jdbcParams,
			Object bindValue,
			SharedSessionContractImplementor session) {
		final MappingMetamodel domainModel = session.getFactory().getDomainModel();
		final MappingModelExpressable mappingExpressable;
		if ( parameterType == null ) {
			if ( domainParamBinding.getType() != null ) {
				final MappingModelExpressable type = domainParamBinding.getType();
				if ( type instanceof EntityIdentifierMapping ) {
					mappingExpressable = type;
					EntityIdentifierMapping identifierMapping = (EntityIdentifierMapping) type;
					if ( !identifierMapping.getJavaTypeDescriptor().getJavaType().isInstance( bindValue ) ) {
						bindValue = identifierMapping.getIdentifier( bindValue, session );
					}
				}
				else if ( type instanceof ToOneAttributeMapping ) {
					ToOneAttributeMapping association = (ToOneAttributeMapping) type;
					if ( association.getReferencedPropertyName() == null ) {
						bindValue = association.getEntityMappingType().getIdentifierMapping().getIdentifier(
								bindValue,
								session
						);
					}
					else {
						bindValue = association.getEntityMappingType()
								.findAttributeMapping( association.getReferencedPropertyName() )
								.getPropertyAccess()
								.getGetter()
								.get( bindValue );
					}
					mappingExpressable = association.getForeignKeyDescriptor();
				}
				else {
					mappingExpressable = type;
				}
			}
			else {
				throw new IllegalStateException( "Parameter has no type by which it can be bound: " + jdbcParameterBindings );
			}
		}
		else {
			mappingExpressable = domainModel.resolveMappingExpressable( parameterType );
		}

		int offset = jdbcParameterBindings.registerParametersForEachJdbcValue(
				bindValue,
				Clause.IRRELEVANT,
				mappingExpressable,
				jdbcParams,
				session
		);
		assert offset == jdbcParams.size();
	}

	public static AllowableParameterType determineParameterType(
			QueryParameterBinding<?> binding,
			QueryParameterImplementor<?> parameter,
			SessionFactoryImplementor sessionFactory) {
		if ( binding.getBindType() != null ) {
			return binding.getBindType();
		}

		if ( parameter.getHibernateType() != null ) {
			return parameter.getHibernateType();
		}

		final TypeConfiguration typeConfiguration = sessionFactory.getTypeConfiguration();

		// assume we have (or can create) a mapping for the parameter's Java type
		BasicType basicType = typeConfiguration.standardBasicTypeForJavaType( parameter.getParameterType() );
		return basicType;
	}
}