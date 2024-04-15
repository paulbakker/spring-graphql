/*
 * Copyright 2020-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.execution;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.RuntimeWiring;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Inspect schema mappings on startup to ensure the following:
 * <ul>
 * <li>Schema fields have either a {@link DataFetcher} registration or a
 * corresponding Class property.
 * <li>{@code DataFetcher} registrations refer to a schema field that exists.
 * <li>{@code DataFetcher} arguments have matching schema field arguments.
 * </ul>
 *
 * <p>Use methods of {@link GraphQlSource.SchemaResourceBuilder} to enable schema
 * inspection on startup. For all other cases, use {@link #initializer()} as a
 * starting point or the shortcut {@link #inspect(GraphQLSchema, Map)}.
 *
 * @author Brian Clozel
 * @author Rossen Stoyanchev
 * @since 1.2.0
 */
@SuppressWarnings("rawtypes")
public final class SchemaMappingInspector {

	private static final Log logger = LogFactory.getLog(SchemaMappingInspector.class);


	private final GraphQLSchema schema;

	private final Map<String, Map<String, DataFetcher>> dataFetchers;

	private final InterfaceUnionLookup interfaceUnionLookup;

	private final Set<String> inspectedTypes = new HashSet<>();

	private final ReportBuilder reportBuilder = new ReportBuilder();

	@Nullable
	private SchemaReport report;


	private SchemaMappingInspector(
			GraphQLSchema schema, Map<String, Map<String, DataFetcher>> dataFetchers,
			List<ClassResolver> classResolvers, Function<GraphQLObjectType, String> classNameFunction) {

		Assert.notNull(schema, "GraphQLSchema is required");
		Assert.notNull(dataFetchers, "DataFetcher map is required");
		this.schema = schema;
		this.dataFetchers = dataFetchers;
		this.interfaceUnionLookup = new InterfaceUnionLookup(schema, dataFetchers, classResolvers, classNameFunction);
	}


	/**
	 * Perform an inspection and create a {@link SchemaReport}.
	 * The inspection is one once only, during the first call to this method.
	 */
	public SchemaReport getOrCreateReport() {
		if (this.report == null) {
			checkSchemaFields();
			checkDataFetcherRegistrations();
			this.report = this.reportBuilder.build();
		}
		return this.report;
	}

	private void checkSchemaFields() {

		checkFieldsContainer(this.schema.getQueryType(), null);

		if (this.schema.isSupportingMutations()) {
			checkFieldsContainer(this.schema.getMutationType(), null);
		}

		if (this.schema.isSupportingSubscriptions()) {
			checkFieldsContainer(this.schema.getSubscriptionType(), null);
		}
	}

	/**
	 * Check fields of the given {@code GraphQLFieldsContainer} to make sure there
	 * is either a {@code DataFetcher} registration, or a corresponding property
	 * in the given Java type, which may be {@code null} for the top-level types
	 * Query, Mutation, and Subscription.
	 */
	private void checkFieldsContainer(
			GraphQLFieldsContainer fieldContainer, @Nullable ResolvableType resolvableType) {

		String typeName = fieldContainer.getName();
		Map<String, DataFetcher> dataFetcherMap = this.dataFetchers.getOrDefault(typeName, Collections.emptyMap());

		for (GraphQLFieldDefinition field : fieldContainer.getFieldDefinitions()) {
			String fieldName = field.getName();
			DataFetcher<?> dataFetcher = dataFetcherMap.get(fieldName);

			if (dataFetcher != null) {
				if (dataFetcher instanceof SelfDescribingDataFetcher<?> selfDescribing) {
					checkFieldArguments(field, selfDescribing);
					checkField(fieldContainer, field, selfDescribing.getReturnType());
				}
				else {
					checkField(fieldContainer, field, ResolvableType.NONE);
				}
				continue;
			}

			if (resolvableType != null) {
				PropertyDescriptor descriptor = getProperty(resolvableType, fieldName);
				if (descriptor != null) {
					checkField(fieldContainer, field, ResolvableType.forMethodReturnType(descriptor.getReadMethod()));
					continue;
				}
			}

			this.reportBuilder.unmappedField(FieldCoordinates.coordinates(typeName, fieldName));
		}
	}

	private void checkFieldArguments(GraphQLFieldDefinition field, SelfDescribingDataFetcher<?> dataFetcher) {
		List<String> arguments = new ArrayList<>();
		for (String name : dataFetcher.getArguments().keySet()) {
			if (field.getArgument(name) == null) {
				arguments.add(name);
			}
		}
		if (!arguments.isEmpty()) {
			this.reportBuilder.unmappedArgument(dataFetcher, arguments);
		}
	}

	/**
	 * Resolve field wrapper types (connection, list, non-null), nest into generic types,
	 * and recurse with {@link #checkFieldsContainer} if there is enough type information.
	 */
	private void checkField(
			GraphQLFieldsContainer parent, GraphQLFieldDefinition field, ResolvableType resolvableType) {

		TypePair typePair = TypePair.resolveTypePair(parent, field, resolvableType, this.schema);

		if (addAndCheckIfAlreadyInspected(typePair.outputType())) {
			return;
		}

		MultiValueMap<GraphQLType, ResolvableType> typePairs = new LinkedMultiValueMap<>();
		if (typePair.outputType() instanceof GraphQLUnionType unionType) {
			typePairs.putAll(this.interfaceUnionLookup.resolveUnion(unionType));
		}
		else if (typePair.outputType() instanceof GraphQLInterfaceType interfaceType) {
			typePairs.putAll(this.interfaceUnionLookup.resolveInterface(interfaceType));
		}

		if (typePairs.isEmpty()) {
			typePairs.add(typePair.outputType(), typePair.resolvableType());
		}

		for (Map.Entry<GraphQLType, List<ResolvableType>> entry : typePairs.entrySet()) {
			GraphQLType graphQlType = entry.getKey();

			for (ResolvableType currentResolvableType : entry.getValue()) {

				// Can we inspect GraphQL type?
				if (!(graphQlType instanceof GraphQLFieldsContainer fieldContainer)) {
					if (isNotScalarOrEnumType(graphQlType)) {
						FieldCoordinates coordinates = FieldCoordinates.coordinates(parent.getName(), field.getName());
						addSkippedType(graphQlType, coordinates, "Unsupported schema type");
					}
					continue;
				}

				// Can we inspect the Class?
				if (currentResolvableType.resolve(Object.class) == Object.class) {
					FieldCoordinates coordinates = FieldCoordinates.coordinates(parent.getName(), field.getName());
					addSkippedType(graphQlType, coordinates, "No class information");
					continue;
				}

				checkFieldsContainer(fieldContainer, currentResolvableType);
			}
		}
	}

	@Nullable
	private PropertyDescriptor getProperty(ResolvableType resolvableType, String fieldName) {
		try {
			Class<?> clazz = resolvableType.resolve();
			return (clazz != null) ? BeanUtils.getPropertyDescriptor(clazz, fieldName) : null;
		}
		catch (BeansException ex) {
			throw new IllegalStateException(
					"Failed to get property on " + resolvableType + " for field '" + fieldName + "'", ex);
		}
	}

	private boolean addAndCheckIfAlreadyInspected(GraphQLType type) {
		return (type instanceof GraphQLNamedOutputType outputType && !this.inspectedTypes.add(outputType.getName()));
	}

	private static boolean isNotScalarOrEnumType(GraphQLType type) {
		return !(type instanceof GraphQLScalarType || type instanceof GraphQLEnumType);
	}

	private void addSkippedType(GraphQLType type, FieldCoordinates coordinates, String reason) {
		String typeName = typeNameToString(type);
		this.reportBuilder.skippedType(type, coordinates);
		if (logger.isDebugEnabled()) {
			logger.debug("Skipped '" + typeName + "': " + reason);
		}
	}

	private static String typeNameToString(GraphQLType type) {
		return (type instanceof GraphQLNamedType namedType) ? namedType.getName() : type.toString();
	}

	private void checkDataFetcherRegistrations() {
		this.dataFetchers.forEach((typeName, registrations) ->
				registrations.forEach((fieldName, dataFetcher) -> {
					FieldCoordinates coordinates = FieldCoordinates.coordinates(typeName, fieldName);
					if (this.schema.getFieldDefinition(coordinates) == null) {
						this.reportBuilder.unmappedRegistration(coordinates, dataFetcher);
					}
				}));
	}


	/**
	 * Check the schema against {@code DataFetcher} registrations, and produce a report.
	 * @param schema the schema to inspect
	 * @param runtimeWiring for {@code DataFetcher} registrations
	 * @return the created report
	 */
	public static SchemaReport inspect(GraphQLSchema schema, RuntimeWiring runtimeWiring) {
		return inspect(schema, runtimeWiring.getDataFetchers());
	}

	/**
	 * Variant of {@link #inspect(GraphQLSchema, RuntimeWiring)} with a map of
	 * {@code DataFetcher} registrations.
	 * @param schema the schema to inspect
	 * @param fetchers the map of {@code DataFetcher} registrations
	 * @since 1.2.5
	 */
	public static SchemaReport inspect(GraphQLSchema schema, Map<String, Map<String, DataFetcher>> fetchers) {
		return initializer().inspect(schema, fetchers);
	}

	/**
	 * Return an initializer to configure the {@link SchemaMappingInspector}
	 * and perform the inspection.
	 * @since 1.3.0
	 */
	public static Initializer initializer() {
		return new DefaultInitializer();
	}


	/**
	 * Helps to configure {@link SchemaMappingInspector}.
	 * @since 1.3.0
	 */
	public interface Initializer {

		/**
		 * Provide a function to derive the simple class name that corresponds to a
		 * GraphQL union member type, or a GraphQL interface implementation type.
		 * This is then used to find a Java class in the same package as that of
		 * the return type of the controller method for the interface or union.
		 * <p>The default, {@link GraphQLObjectType#getName()} is used
		 * @param function the function to use
		 * @return the same initializer instance
		 */
		Initializer classNameFunction(Function<GraphQLObjectType, String> function);

		/**
		 * Add a custom {@link ClassResolver} to use to find the Java class for a
		 * GraphQL union member type, or a GraphQL interface implementation type.
		 * @param resolver the resolver to add
		 * @return the same initializer instance
		 */
		Initializer classResolver(ClassResolver resolver);

		/**
		 * Perform the inspection and return a report.
		 * @param schema the schema to inspect
		 * @param fetchers the registered data fetchers
		 * @return the produced report
		 */
		SchemaReport inspect(GraphQLSchema schema, Map<String, Map<String, DataFetcher>> fetchers);

	}


	/**
	 * Strategy to resolve the Java class(es) for a {@code GraphQLObjectType}, effectively
	 * the reverse of {@link graphql.schema.TypeResolver}, for schema inspection purposes.
	 */
	public interface ClassResolver {

		/**
		 * Return Java class(es) for the given GraphQL object type.
		 * @param objectType the {@code GraphQLObjectType} to resolve
		 * @param interfaceOrUnionType either an interface the object implements,
		 * or a union the object is a member of
		 */
		List<Class<?>> resolveClass(GraphQLObjectType objectType, GraphQLNamedOutputType interfaceOrUnionType);


		/**
		 * Create a resolver by re-using the explicit, reverse mappings of
		 * {@link ClassNameTypeResolver}.
		 * @param resolver the type resolver using class names
		 */
		static ClassResolver fromClassNameTypeResolver(ClassNameTypeResolver resolver) {
			MappingClassResolver mappingResolver = new MappingClassResolver();
			resolver.getMappings().forEach((key, value) -> mappingResolver.addMapping(value, key));
			return mappingResolver;
		}

	}


	/**
	 * Default implementation of {@link Initializer}.
	 */
	private static class DefaultInitializer implements Initializer {

		private Function<GraphQLObjectType, String> classNameFunction = GraphQLObjectType::getName;

		private final List<ClassResolver> classResolvers = new ArrayList<>();

		DefaultInitializer() {
			this.classResolvers.add((objectType, interfaceOrUnionType) -> Collections.emptyList());
		}

		@Override
		public Initializer classNameFunction(Function<GraphQLObjectType, String> function) {
			this.classNameFunction = function;
			return this;
		}

		@Override
		public Initializer classResolver(ClassResolver resolver) {
			this.classResolvers.add(resolver);
			return this;
		}

		@Override
		public SchemaReport inspect(GraphQLSchema schema, Map<String, Map<String, DataFetcher>> fetchers) {
			return new SchemaMappingInspector(
					schema, fetchers, this.classResolvers, this.classNameFunction).getOrCreateReport();
		}
	}


	/**
	 * ClassResolver with explicit mappings.
	 */
	private static final class MappingClassResolver implements ClassResolver {

		private final MultiValueMap<String, Class<?>> map = new LinkedMultiValueMap<>();

		void addMapping(String typeName, Class<?> clazz) {
			this.map.add(typeName, clazz);
		}

		@Override
		public List<Class<?>> resolveClass(GraphQLObjectType objectType, GraphQLNamedOutputType interfaceOrUnionType) {
			return this.map.getOrDefault(objectType.getName(), Collections.emptyList());
		}
	}


	/**
	 * ClassResolver that uses a function to derive the simple class name from
	 * the GraphQL object type, and then prepends a prefixes such as a package
	 * name and/or an outer class name.
	 */
	private static class ReflectionClassResolver implements ClassResolver {

		private final Function<GraphQLObjectType, String> classNameFunction;

		private final MultiValueMap<String, String> classPrefixes = new LinkedMultiValueMap<>();

		ReflectionClassResolver(Function<GraphQLObjectType, String> classNameFunction) {
			this.classNameFunction = classNameFunction;
		}

		void addClassPrefix(String interfaceOrUnionTypeName, String classPrefix) {
			this.classPrefixes.add(interfaceOrUnionTypeName, classPrefix);
		}

		@Override
		public List<Class<?>> resolveClass(GraphQLObjectType objectType, GraphQLNamedOutputType interfaceOrUnion) {
			String className = this.classNameFunction.apply(objectType);
			for (String prefix : this.classPrefixes.getOrDefault(interfaceOrUnion.getName(), Collections.emptyList())) {
				try {
					Class<?> clazz = Class.forName(prefix + className);
					return Collections.singletonList(clazz);
				}
				catch (ClassNotFoundException ex) {
					// Ignore
				}
			}
			return Collections.emptyList();
		}
	}


	/**
	 * Provides methods to look up GraphQL Object and Java type pairs associated
	 * with GraphQL interface and union types.
	 */
	private static class InterfaceUnionLookup {

		private static final Predicate<String> PACKAGE_PREDICATE = (name) -> !name.startsWith("java.");

		private static final LinkedMultiValueMap<GraphQLType, ResolvableType> EMPTY_MULTI_VALUE_MAP = new LinkedMultiValueMap<>(0);

		/** Interface or union type name to implementing or member GraphQL-Java types pairs. */
		private final Map<String, MultiValueMap<GraphQLType, ResolvableType>> mappings = new LinkedHashMap<>();

		InterfaceUnionLookup(
				GraphQLSchema schema, Map<String, Map<String, DataFetcher>> dataFetchers,
				List<ClassResolver> classResolvers, Function<GraphQLObjectType, String> classNameFunction) {

			addReflectionClassResolver(schema, dataFetchers, classNameFunction, classResolvers);

			for (GraphQLNamedType type : schema.getAllTypesAsList()) {
				if (type instanceof GraphQLUnionType union) {
					for (GraphQLNamedOutputType member : union.getTypes()) {
						addTypeMapping(union, (GraphQLObjectType) member, classResolvers);
					}
				}
				else if (type instanceof GraphQLObjectType objectType) {
					for (GraphQLNamedOutputType interfaceType : objectType.getInterfaces()) {
						addTypeMapping(interfaceType, objectType, classResolvers);
					}
				}
			}
		}

		private static void addReflectionClassResolver(
				GraphQLSchema schema, Map<String, Map<String, DataFetcher>> dataFetchers,
				Function<GraphQLObjectType, String> classNameFunction, List<ClassResolver> classResolvers) {

			ReflectionClassResolver resolver = new ReflectionClassResolver(classNameFunction);
			classResolvers.add(resolver);

			for (Map.Entry<String, Map<String, DataFetcher>> typeEntry : dataFetchers.entrySet()) {
				String typeName = typeEntry.getKey();
				GraphQLType parentType = schema.getType(typeName);
				if (parentType == null) {
					continue;  // Unmapped registration
				}
				for (Map.Entry<String, DataFetcher> fieldEntry : typeEntry.getValue().entrySet()) {
					FieldCoordinates coordinates = FieldCoordinates.coordinates(typeName, fieldEntry.getKey());
					GraphQLFieldDefinition field = schema.getFieldDefinition(coordinates);
					if (field == null) {
						continue;  // Unmapped registration
					}
					TypePair pair = TypePair.resolveTypePair(parentType, field, fieldEntry.getValue(), schema);
					GraphQLType outputType = pair.outputType();
					if (outputType instanceof GraphQLUnionType || outputType instanceof GraphQLInterfaceType) {
						String outputTypeName = ((GraphQLNamedOutputType) outputType).getName();
						Class<?> clazz = pair.resolvableType().resolve(Object.class);
						if (PACKAGE_PREDICATE.test(clazz.getPackageName())) {
							int index = clazz.getName().indexOf(clazz.getSimpleName());
							resolver.addClassPrefix(outputTypeName, clazz.getName().substring(0, index));
						}
					}
				}
			}
		}

		private void addTypeMapping(
				GraphQLNamedOutputType interfaceOrUnionType, GraphQLObjectType objectType,
				List<ClassResolver> classResolvers) {

			List<ResolvableType> resolvableTypes = new ArrayList<>();

			for (ClassResolver resolver : classResolvers) {
				List<Class<?>> classes = resolver.resolveClass(objectType, interfaceOrUnionType);
				if (!classes.isEmpty()) {
					for (Class<?> clazz : classes) {
						ResolvableType resolvableType = ResolvableType.forClass(clazz);
						resolvableTypes.add(resolvableType);
					}
					break;
				}
			}

			if (resolvableTypes.isEmpty()) {
				resolvableTypes.add(ResolvableType.NONE);
			}

			for (ResolvableType resolvableType : resolvableTypes) {
				String name = interfaceOrUnionType.getName();
				this.mappings.computeIfAbsent(name, (n) -> new LinkedMultiValueMap<>()).add(objectType, resolvableType);
			}
		}

		/**
		 * Resolve the implementation GraphQL and Java type pairs for the interface.
		 * @param interfaceType the interface type to resolve type pairs for
		 * @return {@code MultiValueMap} with one or more pairs, possibly one
		 * pair with {@link ResolvableType#NONE}.
		 */
		MultiValueMap<GraphQLType, ResolvableType> resolveInterface(GraphQLInterfaceType interfaceType) {
			return this.mappings.getOrDefault(interfaceType.getName(), EMPTY_MULTI_VALUE_MAP);
		}

		/**
		 * Resolve the member GraphQL and Java type pairs for the union.
		 * @param unionType the union type to resolve type pairs for
		 * @return {@code MultiValueMap} with one or more pairs, possibly one
		 * pair with {@link ResolvableType#NONE}.
		 */
		MultiValueMap<GraphQLType, ResolvableType> resolveUnion(GraphQLUnionType unionType) {
			return this.mappings.getOrDefault(unionType.getName(), EMPTY_MULTI_VALUE_MAP);
		}

	}


	/**
	 * Container for a GraphQL and Java type pair along with logic to resolve the
	 * pair of types for a GraphQL field and the {@code DataFetcher} registered for it.
	 */
	private record TypePair(GraphQLType outputType, ResolvableType resolvableType) {

		private static final ReactiveAdapterRegistry adapterRegistry = ReactiveAdapterRegistry.getSharedInstance();

		/**
		 * Convenience variant of
		 * {@link #resolveTypePair(GraphQLType, GraphQLFieldDefinition, ResolvableType, GraphQLSchema)}
		 * with a {@link DataFetcher} to extract the return type from.
		 * @param parent the parent type of the field
		 * @param field the field
		 * @param fetcher the data fetcher associated with this field
		 * @param schema the GraphQL schema
		 */
		public static TypePair resolveTypePair(
				GraphQLType parent, GraphQLFieldDefinition field, DataFetcher<?> fetcher, GraphQLSchema schema) {

			return resolveTypePair(parent, field,
					(fetcher instanceof SelfDescribingDataFetcher<?> sd) ? sd.getReturnType() : ResolvableType.NONE,
					schema);
		}

		/**
		 * Given a GraphQL field and its associated Java type, determine
		 * the type pair to use for schema inspection, removing list, non-null, and
		 * connection type wrappers, and nesting within generic types in order to get
		 * to the types to use for schema inspection.
		 * @param parent the parent type of the field
		 * @param field the field
		 * @param resolvableType the Java type associated with the field
		 * @param schema the GraphQL schema
		 * @return the GraphQL type and corresponding Java type, or {@link ResolvableType#NONE} if unresolved.
		 */
		public static TypePair resolveTypePair(
				GraphQLType parent, GraphQLFieldDefinition field, ResolvableType resolvableType, GraphQLSchema schema) {

			// Remove GraphQL type wrappers, and nest within Java generic types
			GraphQLType outputType = unwrapIfNonNull(field.getType());
			if (isPaginatedType(outputType)) {
				outputType = getPaginatedType((GraphQLObjectType) outputType, schema);
				resolvableType = nestForConnection(resolvableType);
			}
			else if (outputType instanceof GraphQLList listType) {
				outputType = unwrapIfNonNull(listType.getWrappedType());
				resolvableType = nestForList(resolvableType, parent == schema.getSubscriptionType());
			}
			else {
				resolvableType = nestIfWrappedType(resolvableType);
			}
			return new TypePair(outputType, resolvableType);
		}

		private static GraphQLType unwrapIfNonNull(GraphQLType type) {
			return (type instanceof GraphQLNonNull graphQLNonNull) ? graphQLNonNull.getWrappedType() : type;
		}

		private static boolean isPaginatedType(GraphQLType type) {
			return (type instanceof GraphQLObjectType objectType &&
					objectType.getName().endsWith("Connection") &&
					objectType.getField("edges") != null && objectType.getField("pageInfo") != null);
		}

		private static GraphQLType getPaginatedType(GraphQLObjectType type, GraphQLSchema schema) {
			String name = type.getName().substring(0, type.getName().length() - 10);
			GraphQLType nodeType = schema.getType(name);
			Assert.state(nodeType != null, "No node type for '" + type.getName() + "'");
			return nodeType;
		}

		private static ResolvableType nestForConnection(ResolvableType type) {
			if (type == ResolvableType.NONE) {
				return type;
			}
			type = nestIfWrappedType(type);
			if (logger.isDebugEnabled() && type.getGenerics().length != 1) {
				logger.debug("Expected Connection type to have a generic parameter: " + type);
			}
			return type.getNested(2);
		}

		private static ResolvableType nestIfWrappedType(ResolvableType type) {
			Class<?> clazz = type.resolve(Object.class);
			if (Optional.class.isAssignableFrom(clazz)) {
				if (logger.isDebugEnabled() && type.getGeneric(0).resolve() == null) {
					logger.debug("Expected Optional type to have a generic parameter: " + type);
				}
				return type.getNested(2);
			}
			ReactiveAdapter adapter = adapterRegistry.getAdapter(clazz);
			if (adapter != null) {
				if (logger.isDebugEnabled() && adapter.isNoValue()) {
					logger.debug("Expected reactive/async return type that can produce value(s): " + type);
				}
				return type.getNested(2);
			}
			return type;
		}

		private static ResolvableType nestForList(ResolvableType type, boolean subscription) {
			if (type == ResolvableType.NONE) {
				return type;
			}
			ReactiveAdapter adapter = adapterRegistry.getAdapter(type.resolve(Object.class));
			if (adapter != null) {
				if (logger.isDebugEnabled() && adapter.isNoValue()) {
					logger.debug("Expected List compatible type: " + type);
				}
				type = type.getNested(2);
				if (adapter.isMultiValue() && !subscription) {
					return type;
				}
			}
			if (logger.isDebugEnabled() && !type.isArray() && type.getGenerics().length != 1) {
				logger.debug("Expected List compatible type: " + type);
			}
			return type.getNested(2);
		}

	};


	/**
	 * Helps to build a {@link SchemaReport}.
	 */
	private final class ReportBuilder {

		private final List<FieldCoordinates> unmappedFields = new ArrayList<>();

		private final Map<FieldCoordinates, DataFetcher<?>> unmappedRegistrations = new LinkedHashMap<>();

		private final MultiValueMap<DataFetcher<?>, String> unmappedArguments = new LinkedMultiValueMap<>();

		private final List<SchemaReport.SkippedType> skippedTypes = new ArrayList<>();

		void unmappedField(FieldCoordinates coordinates) {
			this.unmappedFields.add(coordinates);
		}

		void unmappedRegistration(FieldCoordinates coordinates, DataFetcher<?> dataFetcher) {
			this.unmappedRegistrations.put(coordinates, dataFetcher);
		}

		void unmappedArgument(DataFetcher<?> dataFetcher, List<String> arguments) {
			this.unmappedArguments.put(dataFetcher, arguments);
		}

		void skippedType(GraphQLType type, FieldCoordinates coordinates) {
			this.skippedTypes.add(new DefaultSkippedType(type, coordinates));
		}

		SchemaReport build() {
			return new DefaultSchemaReport(
					this.unmappedFields, this.unmappedRegistrations, this.unmappedArguments, this.skippedTypes);
		}

	}


	/**
	 * Default implementation of {@link SchemaReport}.
	 */
	private class DefaultSchemaReport implements SchemaReport {

		private final List<FieldCoordinates> unmappedFields;

		private final Map<FieldCoordinates, DataFetcher<?>> unmappedRegistrations;

		private final MultiValueMap<DataFetcher<?>, String> unmappedArguments;

		private final List<SchemaReport.SkippedType> skippedTypes;

		DefaultSchemaReport(
				List<FieldCoordinates> unmappedFields, Map<FieldCoordinates, DataFetcher<?>> unmappedRegistrations,
				MultiValueMap<DataFetcher<?>, String> unmappedArguments, List<SkippedType> skippedTypes) {

			this.unmappedFields = Collections.unmodifiableList(unmappedFields);
			this.unmappedRegistrations = Collections.unmodifiableMap(unmappedRegistrations);
			this.unmappedArguments = CollectionUtils.unmodifiableMultiValueMap(unmappedArguments);
			this.skippedTypes = Collections.unmodifiableList(skippedTypes);
		}

		@Override
		public List<FieldCoordinates> unmappedFields() {
			return this.unmappedFields;
		}

		@Override
		public Map<FieldCoordinates, DataFetcher<?>> unmappedRegistrations() {
			return this.unmappedRegistrations;
		}

		@Override
		public MultiValueMap<DataFetcher<?>, String> unmappedArguments() {
			return this.unmappedArguments;
		}

		@Override
		public List<SkippedType> skippedTypes() {
			return this.skippedTypes;
		}

		@Override
		public GraphQLSchema schema() {
			return SchemaMappingInspector.this.schema;
		}

		@Override
		@Nullable
		public DataFetcher<?> dataFetcher(FieldCoordinates coordinates) {
			return SchemaMappingInspector.this.dataFetchers
					.getOrDefault(coordinates.getTypeName(), Collections.emptyMap())
					.get(coordinates.getFieldName());
		}

		@Override
		public String toString() {
			return "GraphQL schema inspection:\n" +
					"\tUnmapped fields: " + formatUnmappedFields() + "\n" +
					"\tUnmapped registrations: " + this.unmappedRegistrations + "\n" +
					"\tUnmapped arguments: " + this.unmappedArguments + "\n" +
					"\tSkipped types: " + this.skippedTypes;
		}

		private String formatUnmappedFields() {
			MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
			this.unmappedFields.forEach((coordinates) -> {
				List<String> fields = map.computeIfAbsent(coordinates.getTypeName(), (s) -> new ArrayList<>());
				fields.add(coordinates.getFieldName());
			});
			return map.toString();
		}

	}


	/**
	 * Default implementation of a {@link SchemaReport.SkippedType}.
	 */
	private record DefaultSkippedType(
			GraphQLType type, FieldCoordinates fieldCoordinates) implements SchemaReport.SkippedType {

		@Override
		public String toString() {
			return typeNameToString(this.type);
		}

	}

}
