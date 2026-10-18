package com.auralink.api.v1.assistant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.auralink.api.v1.error.ApiV1ExceptionHandler;
import com.auralink.assistant.AssistantFailure;
import com.auralink.assistant.AssistantStore;
import com.auralink.entity.User;
import com.auralink.service.CurrentUserService;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssistantArtworkControllerTest {
 private final CurrentUserService users=mock(CurrentUserService.class);
 private final ObjectProvider<AssistantStore> stores=mock(ObjectProvider.class);
 private final ObjectProvider<NamedParameterJdbcTemplate> reads=mock(ObjectProvider.class);
 private final AssistantStore store=mock(AssistantStore.class);
 private MockMvc mvc;
 @BeforeEach void setup(){
  User user=new User();user.setId(71L);when(users.requireCurrentUser()).thenReturn(user);when(stores.getIfAvailable()).thenReturn(store);
  when(reads.getIfAvailable()).thenReturn(new NamedParameterJdbcTemplate(new JdbcTemplate(mock(DataSource.class))));
  mvc=MockMvcBuilders.standaloneSetup(new AssistantArtworkController(users,stores,reads)).setControllerAdvice(new ApiV1ExceptionHandler()).build();
 }
 @Test void disabledAndMissingCatalogAreExplicitWithoutExecutionOrDatabaseAccess()throws Exception{
  when(stores.getIfAvailable()).thenReturn(null);mvc.perform(get("/api/v1/assistant/artworks/page")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ASSISTANT_DISABLED"));verifyNoInteractions(reads);
  when(stores.getIfAvailable()).thenReturn(store);when(reads.getIfAvailable()).thenReturn(null);mvc.perform(get("/api/v1/assistant/artworks/page")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ASSISTANT_ARTWORK_UNAVAILABLE"));verifyNoInteractions(store);
 }
 @Test void authenticationIsRequiredBeforeAnyStorageRead()throws Exception{
  when(users.requireCurrentUser()).thenThrow(new AssistantFailure(401,"UNAUTHORIZED","Authentication required"));mvc.perform(get("/api/v1/assistant/artworks/page")).andExpect(status().isUnauthorized());verifyNoInteractions(stores,reads,store);
 }
 @Test void ambiguousAliasesRepeatedKeysUnknownFiltersAndMalformedPaginationAreRejected()throws Exception{
  mvc.perform(get("/api/v1/assistant/artworks/page").param("query","山水").param("keyword","花鸟")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/page").param("author","甲","乙")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/page").param("userId","other")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/page").param("size","11")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/page").param("page","NaN")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/page").param("sort","storage_uri")).andExpect(status().isBadRequest());verifyNoInteractions(store);
 }
 @Test void detailRequiresCanonicalIdAndNeverAcceptsPathsOrAliases()throws Exception{
  mvc.perform(get("/api/v1/assistant/artworks/not-a-uuid")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/1-1-1-1-1")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/assistant/artworks/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")).andExpect(status().isBadRequest());verifyNoInteractions(store);
 }
}
