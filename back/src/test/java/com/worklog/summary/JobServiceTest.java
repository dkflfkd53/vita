package com.worklog.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.worklog.summary.JobService.Job;

class JobServiceTest {

    private final JobService jobs = new JobService();

    @AfterEach
    void tearDown() {
        jobs.shutdown();
    }

    @Test
    void errorMarksJobFailedAndAllowsResubmit() throws InterruptedException {
        Job first = jobs.submit("report:week:2026-W44", "주간 리포트", progress -> {
            throw new StackOverflowError();
        });
        await(first);

        assertThat(first.view().status()).isEqualTo("FAILED");
        assertThat(first.view().message()).isEqualTo("StackOverflowError");

        Job again = jobs.submit("report:week:2026-W44", "주간 리포트", progress -> "완료");
        assertThat(again).isNotSameAs(first);
        await(again);
        assertThat(again.view().status()).isEqualTo("DONE");
    }

    private static void await(Job job) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (job.finishedAt == null && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
    }
}
