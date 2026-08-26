package com.auralink.api.v1.painting;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.auralink.api.v1.error.ApiV1ExceptionHandler;
import com.auralink.service.painting.PaintingFavoriteService;
import com.auralink.service.painting.PaintingQueryService;

@ExtendWith(MockitoExtension.class)
class PaintingControllerTest {

    private static final String PAINTING_ID = "00000000-0000-0000-0000-000000000001";

    @Mock private PaintingQueryService paintingQueryService;
    @Mock private PaintingFavoriteService favoriteService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaintingController paintingController = new PaintingController(
                paintingQueryService, favoriteService);
        MyPaintingFavoriteController favoritesController = new MyPaintingFavoriteController(
                paintingQueryService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(paintingController, favoritesController)
                .setControllerAdvice(new ApiV1ExceptionHandler())
                .build();
    }

    @Test
    void listUsesStableCompetitionDefaults() throws Exception {
        PaintingPageResponse response = emptyPage(24);
        when(paintingQueryService.listPaintings(
                null, null, null, null, null, null, null, null, null, null, null,
                0, 24, "source", "asc"))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/paintings"))
                .andExpect(status().isOk());

        verify(paintingQueryService).listPaintings(
                null, null, null, null, null, null, null, null, null, null, null,
                0, 24, "source", "asc");
    }

    @Test
    void favoriteMutationsAreIdempotentNoContentContracts() throws Exception {
        mockMvc.perform(put("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/paintings/{paintingId}/favorite", PAINTING_ID))
                .andExpect(status().isNoContent());

        verify(favoriteService).favorite(PAINTING_ID);
        verify(favoriteService).unfavorite(PAINTING_ID);
    }

    @Test
    void currentUserFavoritePageUsesSameBoundedDefaultSize() throws Exception {
        when(paintingQueryService.listCurrentUserFavorites(0, 24)).thenReturn(emptyPage(24));

        mockMvc.perform(get("/api/v1/me/favorites/paintings"))
                .andExpect(status().isOk());

        verify(paintingQueryService).listCurrentUserFavorites(0, 24);
    }

    private PaintingPageResponse emptyPage(int size) {
        return new PaintingPageResponse(List.of(), 0, size, 0, 0, true, true, false);
    }
}
