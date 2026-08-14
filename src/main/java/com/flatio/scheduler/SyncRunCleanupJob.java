package com.flatio.scheduler;

import com.flatio.service.SyncRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that bounds sync run history by deleting old records per source.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.cleanup.cron}
 * (env: {@code FLATIO_SYNC_CLEANUP_CRON}); default is daily at 01:30.
 * Retention count is configurable via {@code flatio.sync.cleanup.keep-per-source}
 * (env: {@code FLATIO_SYNC_CLEANUP_KEEP_PER_SOURCE}); default is 100.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SyncRunCleanupJob {

  private final SyncRunService syncRunService;

  @Value("${flatio.sync.cleanup.keep-per-source:100}")
  private int keepPerSource;

  /**
   * Deletes sync run records beyond the configured retention count for each source.
   */
  @Scheduled(cron = "${flatio.sync.cleanup.cron:0 30 1 * * *}")
  public void runCleanup() {
    log.info("Sync run cleanup started: keepPerSource={}", keepPerSource);
    try {
      syncRunService.cleanupOldRuns(keepPerSource);
      log.info("Sync run cleanup completed");
    } catch (Exception e) {
      log.error("Sync run cleanup failed: error={}", e.getMessage(), e);
    }
  }
}
