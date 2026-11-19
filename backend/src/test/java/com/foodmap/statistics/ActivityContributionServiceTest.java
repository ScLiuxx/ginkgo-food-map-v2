package com.foodmap.statistics;

import static org.mockito.Mockito.*;

import com.foodmap.statistics.service.ActivityContributionService;
import com.foodmap.statistics.service.ActivityStatisticsService;
import com.foodmap.user.dto.UserIdentity;
import com.foodmap.user.service.UserAccountService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class ActivityContributionServiceTest {
    private final ActivityStatisticsService statistics = mock(ActivityStatisticsService.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final ActivityContributionService service =
            new ActivityContributionService(statistics, accounts);
    private final Instant now = Instant.parse("2026-11-19T15:59:59Z");

    @BeforeEach
    void setup() {
        when(statistics.isCollecting()).thenReturn(true);
        when(statistics.now()).thenReturn(now);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void onlyCommitsCountAndKeepOriginalBusinessDate() {
        service.registered();
        verify(statistics, never()).contribution(any(), anyBoolean(), any());
        var hooks = TransactionSynchronizationManager.getSynchronizations();
        hooks.forEach(hook -> hook.afterCommit());
        verify(statistics).contribution(now, true, true);
    }

    @Test
    void rollbackDoesNotCount() {
        service.registered();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(hook -> hook.afterCompletion(1));
        verify(statistics, never()).contribution(any(), anyBoolean(), any());
    }

    @Test
    void checksAuthorInsteadOfCurrentAdministratorAndExcludesAdminAuthors() {
        when(accounts.findById(11L))
                .thenReturn(
                        Optional.of(
                                new UserIdentity(11L, "author", 0, true, List.of("ROLE_USER"))));
        when(accounts.findById(22L))
                .thenReturn(
                        Optional.of(
                                new UserIdentity(
                                        22L,
                                        "admin",
                                        0,
                                        true,
                                        List.of("ROLE_USER", "ROLE_ADMIN"))));
        service.reviewPublished(11);
        service.reviewPublished(22);
        TransactionSynchronizationManager.getSynchronizations().forEach(hook -> hook.afterCommit());
        verify(statistics, times(1)).contribution(now, false, true);
    }

    @Test
    void roleLookupFailureMarksUnknownAndCallbackFailureDoesNotEscape() {
        when(accounts.findById(11L)).thenThrow(new IllegalStateException());
        service.reviewPublished(11);
        doThrow(new IllegalStateException()).when(statistics).contribution(now, false, null);
        TransactionSynchronizationManager.getSynchronizations().forEach(hook -> hook.afterCommit());
        verify(statistics).contribution(now, false, null);
    }

    @Test
    void disabledAndNontransactionalCallsDoNotCreateCallbacks() {
        when(statistics.isCollecting()).thenReturn(false);
        service.registered();
        verifyNoInteractions(accounts);
        org.assertj.core.api.Assertions.assertThat(
                        TransactionSynchronizationManager.getSynchronizations())
                .isEmpty();
    }
}
