package org.securegraph.elasticsearch;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.*;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.securegraph.*;
import org.securegraph.query.*;
import org.securegraph.type.GeoCircle;
import org.securegraph.util.ConvertingIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.securegraph.util.IterableUtils.toList;

public abstract class ElasticSearchGraphQueryBase extends GraphQueryBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchGraphQueryBase.class);
    private final TransportClient client;
    private final boolean evaluateHasContainers;
    private String indexName;
    private final double inEdgeBoost;
    private final double outEdgeBoost;

    protected ElasticSearchGraphQueryBase(
            TransportClient client,
            String indexName,
            Graph graph,
            String queryString,
            Map<String, PropertyDefinition> propertyDefinitions,
            double inEdgeBoost,
            double outEdgeBoost,
            boolean evaluateHasContainers,
            Authorizations authorizations) {
        super(graph, queryString, propertyDefinitions, authorizations);
        this.client = client;
        this.indexName = indexName;
        this.inEdgeBoost = inEdgeBoost;
        this.outEdgeBoost = outEdgeBoost;
        this.evaluateHasContainers = evaluateHasContainers;
    }

    @Override
    public Iterable<Vertex> vertices() {
        long startTime = System.nanoTime();
        SearchResponse response = getSearchResponse(ElasticSearchSearchIndexBase.ELEMENT_TYPE_VERTEX);
        final SearchHits hits = response.getHits();
        List<Object> ids = toList(new ConvertingIterable<SearchHit, Object>(hits) {
            @Override
            protected Object convert(SearchHit searchHit) {
                return searchHit.getId();
            }
        });
        long endTime = System.nanoTime();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("elastic search results " + ids.size() + " of " + hits.getTotalHits() + " (time: " + ((endTime - startTime) / 1000 / 1000) + "ms)");
        }

        // since ES doesn't support security we will rely on the graph to provide vertex filtering
        // and rely on the DefaultGraphQueryIterable to provide property filtering
        Parameters filterParameters = getParameters().clone();
        filterParameters.setSkip(0); // ES already did a skip
        Iterable<Vertex> vertices = getGraph().getVertices(ids, filterParameters.getAuthorizations());
        return new DefaultGraphQueryIterable<Vertex>(filterParameters, vertices, false, evaluateHasContainers, hits.getTotalHits());
    }

    @Override
    public Iterable<Edge> edges() {
        long startTime = System.nanoTime();
        SearchResponse response = getSearchResponse(ElasticSearchSearchIndexBase.ELEMENT_TYPE_EDGE);
        final SearchHits hits = response.getHits();
        List<Object> ids = toList(new ConvertingIterable<SearchHit, Object>(hits) {
            @Override
            protected Object convert(SearchHit searchHit) {
                return searchHit.getId();
            }
        });
        long endTime = System.nanoTime();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("elastic search results " + ids.size() + " of " + hits.getTotalHits() + " (time: " + ((endTime - startTime) / 1000 / 1000) + "ms)");
        }

        // since ES doesn't support security we will rely on the graph to provide edge filtering
        // and rely on the DefaultGraphQueryIterable to provide property filtering
        Parameters filterParameters = getParameters().clone();
        filterParameters.setSkip(0); // ES already did a skip
        Iterable<Edge> edges = getGraph().getEdges(ids, filterParameters.getAuthorizations());
        // TODO instead of passing false here to not evaluate the query string it would be better to support the Lucene query
        return new DefaultGraphQueryIterable<Edge>(filterParameters, edges, false, evaluateHasContainers, hits.getTotalHits());
    }

    private SearchResponse getSearchResponse(String elementType) {
        List<FilterBuilder> filters = getFilters(elementType);
        QueryBuilder query = createQuery(getParameters().getQueryString(), elementType, filters);

        ScoreFunctionBuilder scoreFunction = ScoreFunctionBuilders
                .scriptFunction("_score "
                        + " * sqrt(inEdgeMultiplier * (1 + doc['" + ElasticSearchSearchIndexBase.IN_EDGE_COUNT_FIELD_NAME + "'].value))"
                        + " * sqrt(outEdgeMultiplier * (1 + doc['" + ElasticSearchSearchIndexBase.OUT_EDGE_COUNT_FIELD_NAME + "'].value))")
                .param("inEdgeMultiplier", inEdgeBoost)
                .param("outEdgeMultiplier", outEdgeBoost);

        FunctionScoreQueryBuilder functionScoreQuery = QueryBuilders.functionScoreQuery(query, scoreFunction);

        SearchRequestBuilder q = getSearchRequestBuilder(filters, functionScoreQuery);

        LOGGER.debug("query: " + q);
        return q.execute()
                .actionGet();
    }

    protected List<FilterBuilder> getFilters(String elementType) {
        List<FilterBuilder> filters = new ArrayList<FilterBuilder>();
        addElementTypeFilter(filters, elementType);
        for (HasContainer has : getParameters().getHasContainers()) {
            if (has.predicate instanceof Compare) {
                Compare compare = (Compare) has.predicate;
                Object value = has.value;
                String key = has.key;
                if (value instanceof String || value instanceof String[]) {
                    key = key + ElasticSearchSearchIndexBase.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
                }
                switch (compare) {
                    case EQUAL:
                        if (value instanceof DateOnly) {
                            DateOnly dateOnlyValue = ((DateOnly) value);
                            filters.add(FilterBuilders.rangeFilter(key).from(dateOnlyValue.toString()).to(dateOnlyValue.toString()));
                        } else {
                            filters.add(FilterBuilders.termFilter(key, value));
                        }
                        break;
                    case GREATER_THAN_EQUAL:
                        filters.add(FilterBuilders.rangeFilter(key).gte(value));
                        break;
                    case GREATER_THAN:
                        filters.add(FilterBuilders.rangeFilter(key).gt(value));
                        break;
                    case LESS_THAN_EQUAL:
                        filters.add(FilterBuilders.rangeFilter(key).lte(value));
                        break;
                    case LESS_THAN:
                        filters.add(FilterBuilders.rangeFilter(key).lt(value));
                        break;
                    case NOT_EQUAL:
                        addNotFilter(filters, key, value);
                        break;
                    case IN:
                        filters.add(FilterBuilders.inFilter(key, (Object[]) has.value));
                        break;
                    default:
                        throw new SecureGraphException("Unexpected Compare predicate " + has.predicate);
                }
            } else if (has.predicate instanceof TextPredicate) {
                TextPredicate compare = (TextPredicate) has.predicate;
                Object value = has.value;
                if (value instanceof String) {
                    value = ((String) value).toLowerCase(); // using the standard analyzer all strings are lower-cased.
                }
                switch (compare) {
                    case CONTAINS:
                        if (value instanceof String) {
                            filters.add(FilterBuilders.termsFilter(has.key, splitStringIntoTerms((String) value)).execution("and"));
                        } else {
                            filters.add(FilterBuilders.termFilter(has.key, value));
                        }
                        break;
                    default:
                        throw new SecureGraphException("Unexpected text predicate " + has.predicate);
                }
            } else if (has.predicate instanceof GeoCompare) {
                GeoCompare compare = (GeoCompare) has.predicate;
                switch (compare) {
                    case WITHIN:
                        if (has.value instanceof GeoCircle) {
                            GeoCircle geoCircle = (GeoCircle) has.value;
                            double lat = geoCircle.getLatitude();
                            double lon = geoCircle.getLongitude();
                            double distance = geoCircle.getRadius();
                            filters.add(FilterBuilders.geoDistanceFilter(has.key).point(lat, lon).distance(distance, DistanceUnit.KILOMETERS));
                        } else {
                            throw new SecureGraphException("Unexpected has value type " + has.value.getClass().getName());
                        }
                        break;
                    default:
                        throw new SecureGraphException("Unexpected GeoCompare predicate " + has.predicate);
                }
            } else {
                throw new SecureGraphException("Unexpected predicate type " + has.predicate.getClass().getName());
            }
        }
        return filters;
    }

    protected void addElementTypeFilter(List<FilterBuilder> filters, String elementType) {
        filters.add(createElementTypeFilter(elementType));
    }

    protected TermsFilterBuilder createElementTypeFilter(String elementType) {
        return FilterBuilders.inFilter(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME, elementType);
    }

    protected void addNotFilter(List<FilterBuilder> filters, String key, Object value) {
        filters.add(FilterBuilders.notFilter(FilterBuilders.inFilter(key, value)));
    }

    protected SearchRequestBuilder getSearchRequestBuilder(List<FilterBuilder> filters, FunctionScoreQueryBuilder functionScoreQuery) {
        return getClient()
                .prepareSearch(getIndexName())
                .setTypes(ElasticSearchSearchIndexBase.ELEMENT_TYPE)
                .setQuery(functionScoreQuery)
                .setPostFilter(getFilterBuilder(filters))
                .setFrom((int) getParameters().getSkip())
                .setSize((int) getParameters().getLimit());
    }

    protected AndFilterBuilder getFilterBuilder(List<FilterBuilder> filters) {
        return FilterBuilders.andFilter(filters.toArray(new FilterBuilder[filters.size()]));
    }

    private String[] splitStringIntoTerms(String value) {
        String[] values = value.split("[ -]");
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i].trim();
        }
        return values;
    }

    protected QueryBuilder createQuery(String queryString, String elementType, List<FilterBuilder> filters) {
        QueryBuilder query;
        if (queryString == null) {
            query = QueryBuilders.matchAllQuery();
        } else {
            query = QueryBuilders.queryString(queryString);
        }
        return query;
    }

    public TransportClient getClient() {
        return client;
    }

    public String getIndexName() {
        return indexName;
    }
}
