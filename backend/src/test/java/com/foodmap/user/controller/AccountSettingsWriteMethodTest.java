package com.foodmap.user.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foodmap.security.dto.AuthenticatedActor;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.user.service.FavoritePrivacyService;
import com.foodmap.user.service.FollowUserService;
import com.foodmap.user.service.UserProfileService;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountSettingsWriteMethodTest {
    @ParameterizedTest
    @MethodSource("writeMethods")
    void savesSettingsThroughTheCurrentActor(HttpMethod method) throws Exception {
        CurrentActorService actors = mock(CurrentActorService.class);
        when(actors.requireActor()).thenReturn(new AuthenticatedActor(11L, false));
        UserProfileService profiles = mock(UserProfileService.class);
        FavoritePrivacyService favorites = mock(FavoritePrivacyService.class);
        FollowUserService follows = mock(FollowUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new UserProfileController(profiles, actors),
                new FavoritePrivacyController(favorites, actors),
                new FollowPrivacyController(follows, actors)).build();

        mvc.perform(request(method, "/api/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"student\"}"))
                .andExpect(status().isOk());
        mvc.perform(request(method, "/api/me/favorites/visibility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favoritesPublic\":true}"))
                .andExpect(status().isOk());
        mvc.perform(request(method, "/api/me/follows/privacy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followingPublic\":true,\"followersPublic\":false}"))
                .andExpect(status().isOk());

        verify(profiles).update(11L, "student");
        verify(favorites).set(11L, true);
        verify(follows).set(11L, true, false);
    }

    static Stream<HttpMethod> writeMethods() {
        return Stream.of(HttpMethod.PATCH, HttpMethod.PUT);
    }
}
