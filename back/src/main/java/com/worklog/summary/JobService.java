package com.worklog.summary;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 요약·리포트 생성은 claude 호출이라 수십 초씩 걸린다. 한 줄로 세워 차례로 돌리고 화면은 상태만 폴링한다.
 * 같은 key 가 이미 대기·실행 중이면 새로 넣지 않는다.
 */
@Slf4j
@Service
public class JobService {

    private static final int KEEP = 30;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worklog-job");
        t.setDaemon(true);
        return t;
    });
    private final Deque<Job> jobs = new ArrayDeque<>();

    public synchronized Job submit(String key, String label, Work work) {
        for (Job j : jobs) {
            if (j.key.equals(key) && (j.status == Status.QUEUED || j.status == Status.RUNNING)) {
                return j;
            }
        }
        Job job = new Job(UUID.randomUUID().toString(), key, label);
        jobs.addFirst(job);
        while (jobs.size() > KEEP) {
            jobs.removeLast();
        }
        executor.submit(() -> run(job, work));
        return job;
    }

    private void run(Job job, Work work) {
        job.status = Status.RUNNING;
        job.startedAt = Instant.now();
        try {
            job.message = work.run(msg -> job.message = msg);
            job.status = Status.DONE;
        } catch (Exception e) {
            log.warn("작업 실패 [{}]: {}", job.label, e.getMessage());
            job.status = Status.FAILED;
            job.message = e.getMessage();
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    public synchronized List<JobView> list() {
        return jobs.stream().map(Job::view).toList();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @FunctionalInterface
    public interface Work {
        /** @return 끝났을 때 화면에 보일 한 줄 */
        String run(Progress progress) throws Exception;
    }

    @FunctionalInterface
    public interface Progress {
        void update(String message);
    }

    public static final class Job {
        final String id;
        final String key;
        final String label;
        volatile Status status = Status.QUEUED;
        volatile String message;
        volatile Instant startedAt;
        volatile Instant finishedAt;

        Job(String id, String key, String label) {
            this.id = id;
            this.key = key;
            this.label = label;
        }

        public JobView view() {
            return new JobView(id, key, label, status.name(), message,
                    startedAt == null ? null : startedAt.toString(),
                    finishedAt == null ? null : finishedAt.toString());
        }
    }

    public record JobView(String id, String key, String label, String status, String message,
                          String startedAt, String finishedAt) {
    }
}
