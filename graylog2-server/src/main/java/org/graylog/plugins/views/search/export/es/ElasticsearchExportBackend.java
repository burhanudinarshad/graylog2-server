/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.views.search.export.es;

import com.google.inject.name.Named;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.graylog.plugins.views.search.elasticsearch.ElasticsearchQueryString;
import org.graylog.plugins.views.search.elasticsearch.IndexLookup;
import org.graylog.plugins.views.search.export.ExportBackend;
import org.graylog.plugins.views.search.export.ExportMessagesCommand;
import org.graylog.plugins.views.search.export.SimpleMessage;
import org.graylog.plugins.views.search.export.SimpleMessageChunk;
import org.graylog2.indexer.IndexHelper;
import org.graylog2.indexer.IndexMapping;
import org.graylog2.plugin.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.graylog2.plugin.Tools.ES_DATE_FORMAT_FORMATTER;

@SuppressWarnings("rawtypes")
public class ElasticsearchExportBackend implements ExportBackend {
    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchExportBackend.class);

    private final IndexLookup indexLookup;
    private final RequestStrategy requestStrategy;
    private final boolean allowLeadingWildcard;

    @Inject
    public ElasticsearchExportBackend(IndexLookup indexLookup, RequestStrategy requestStrategy, @Named("allow_leading_wildcard_searches") boolean allowLeadingWildcard) {
        this.indexLookup = indexLookup;
        this.requestStrategy = requestStrategy;
        this.allowLeadingWildcard = allowLeadingWildcard;
    }

    @Override
    public void run(ExportMessagesCommand request, Consumer<SimpleMessageChunk> chunkCollector) {
        boolean isFirstChunk = true;
        int totalCount = 0;

        while (true) {
            List<SearchResult.Hit<Map, Void>> hits = search(request);

            if (hits.isEmpty()) {
                return;
            }

            boolean success = publishChunk(chunkCollector, hits, request.fieldsInOrder(), isFirstChunk);
            if (!success) {
                return;
            }

            totalCount += hits.size();
            if (request.limit().isPresent() && totalCount >= request.limit().getAsInt()) {
                LOG.info("Limit of {} reached. Stopping message retrieval.", request.limit().getAsInt());
                return;
            }

            isFirstChunk = false;
        }
    }

    private List<SearchResult.Hit<Map, Void>> search(ExportMessagesCommand request) {
        Search.Builder search = prepareSearchRequest(request);

        return requestStrategy.nextChunk(search, request);
    }

    private Search.Builder prepareSearchRequest(ExportMessagesCommand request) {
        SearchSourceBuilder ssb = searchSourceBuilderFrom(request);

        Set<String> indices = indicesFor(request);
        return new Search.Builder(ssb.toString())
                .addType(IndexMapping.TYPE_MESSAGE)
                .allowNoIndices(false)
                .ignoreUnavailable(false)
                .addIndex(indices);
    }

    private SearchSourceBuilder searchSourceBuilderFrom(ExportMessagesCommand request) {
        QueryBuilder query = queryFrom(request);

        SearchSourceBuilder ssb = new SearchSourceBuilder()
                .query(query)
                .size(request.chunkSize());

        return requestStrategy.configure(ssb);
    }

    private QueryBuilder queryFrom(ExportMessagesCommand request) {
        return boolQuery()
                .filter(queryStringFilter(request))
                .filter(timestampFilter(request))
                .filter(streamsFilter(request));
    }

    private QueryBuilder queryStringFilter(ExportMessagesCommand request) {
        ElasticsearchQueryString backendQuery = request.queryString();
        return backendQuery.isEmpty() ?
                matchAllQuery() :
                queryStringQuery(backendQuery.queryString()).allowLeadingWildcard(allowLeadingWildcard);
    }

    private QueryBuilder timestampFilter(ExportMessagesCommand request) {
        return requireNonNull(IndexHelper.getTimestampRangeFilter(request.timeRange()));
    }

    private TermsQueryBuilder streamsFilter(ExportMessagesCommand request) {
        return termsQuery(Message.FIELD_STREAMS, request.streams());
    }

    private Set<String> indicesFor(ExportMessagesCommand request) {
        return indexLookup.indexNamesForStreamsInTimeRange(request.streams(), request.timeRange());
    }

    private boolean publishChunk(Consumer<SimpleMessageChunk> chunkCollector, List<SearchResult.Hit<Map, Void>> hits, LinkedHashSet<String> desiredFieldsInOrder, boolean isFirstChunk) {
        SimpleMessageChunk hitsWithOnlyRelevantFields = buildHitsWithRelevantFields(hits, desiredFieldsInOrder);

        if (isFirstChunk) {
            hitsWithOnlyRelevantFields = hitsWithOnlyRelevantFields.toBuilder().isFirstChunk(true).build();
        }

        try {
            chunkCollector.accept(hitsWithOnlyRelevantFields);
            return true;
        } catch (Exception e) {
            LOG.warn("Chunk publishing threw exception. Stopping search after queries", e);
            return false;
        }
    }

    private SimpleMessageChunk buildHitsWithRelevantFields(List<SearchResult.Hit<Map, Void>> hits, LinkedHashSet<String> desiredFieldsInOrder) {
        LinkedHashSet<SimpleMessage> set = hits.stream()
                .map(h -> buildHitWithAllFields(h.source, h.index))
                .collect(toCollection(LinkedHashSet::new));
        return SimpleMessageChunk.from(desiredFieldsInOrder, set);
    }

    private SimpleMessage buildHitWithAllFields(Map source, String index) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

        for (Object key : source.keySet()) {
            String name = (String) key;
            Object value = valueFrom(source, name);
            fields.put(name, value);
        }

        // _id is needed, because the old decorators implementation relies on it
        fields.put("_id", UUID.randomUUID().toString());

        return SimpleMessage.from(index, fields);
    }

    private Object valueFrom(Map source, String name) {
        if (name.equals(Message.FIELD_TIMESTAMP)) {
            return fixTimestampFormat(source.get(Message.FIELD_TIMESTAMP));
        }
        return source.get(name);
    }

    private Object fixTimestampFormat(Object rawTimestamp) {
        try {
            return ES_DATE_FORMAT_FORMATTER.parseDateTime(String.valueOf(rawTimestamp)).toString();
        } catch (IllegalArgumentException e) {
            LOG.warn("Could not parse timestamp {}", rawTimestamp, e);
            return rawTimestamp;
        }
    }
}
