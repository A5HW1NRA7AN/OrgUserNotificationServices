package com.catalogue.verg.core.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.SourceFilter;
import com.catalogue.verg.core.elasticsearch.config.EsConfig;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.core.util.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The source filter is the only thing between a search and the credential hashes. */
@ExtendWith(MockitoExtension.class)
class EsUtilServiceImplTest {

    @Mock private ElasticsearchClient elasticsearchClient;
    @Mock private EsConfig esConfig;

    @Test
    void nullRequestedFieldsStillExcludesCredentials() throws Exception {
        SourceFilter filter = sourceFilterFor(null);

        assertThat(filter.excludes()).contains(Constants.PASSWORD, Constants.PIN);
        assertThat(filter.includes()).isEmpty();
    }

    @Test
    void emptyRequestedFieldsStillExcludesCredentials() throws Exception {
        SourceFilter filter = sourceFilterFor(List.of());

        assertThat(filter.excludes()).contains(Constants.PASSWORD, Constants.PIN);
        assertThat(filter.includes()).isEmpty();
    }

    @Test
    void requestingACredentialByNameCannotOptBackIn() throws Exception {
        SourceFilter filter = sourceFilterFor(List.of(Constants.EMAIL, Constants.PASSWORD));

        assertThat(filter.includes()).containsExactly(Constants.EMAIL, Constants.PASSWORD);
        // Elasticsearch applies excludes after includes, so the named password is still dropped.
        assertThat(filter.excludes()).contains(Constants.PASSWORD, Constants.PIN);
    }

    /** Runs a search and captures the request; the client throws so no response needs faking. */
    private SourceFilter sourceFilterFor(List<String> requestedFields) throws Exception {
        EsUtilServiceImpl service = new EsUtilServiceImpl(elasticsearchClient, esConfig);
        SearchCriteria criteria = new SearchCriteria();
        criteria.setRequestedFields(requestedFields);
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class)))
                .thenThrow(new IllegalStateException("captured"));

        assertThatThrownBy(() -> service.searchDocuments("user", criteria)).hasMessage("captured");

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearchClient).search(request.capture(), eq(Map.class));
        return request.getValue().source().filter();
    }
}
