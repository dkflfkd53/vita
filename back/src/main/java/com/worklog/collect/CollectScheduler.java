package com.worklog.collect;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.worklog.agent.AgentCollector;
import com.worklog.usage.UsageCollector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 주기 수집. 화면의 "지금 수집" 버튼도 같은 진입점을 쓰고, 겹치면 뒤엣것은 건너뛴다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectScheduler {

    private final TranscriptCollector transcripts;
    private final GitCommitCollector commits;
    private final UsageCollector usage;
    private final AgentCollector agent;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Instant> lastCollectedAt = new AtomicReference<>();

    @Scheduled(initialDelay = 3_000, fixedDelayString = "${worklog.collect-interval}")
    public void scheduled() {
        collectNow();
    }

    public boolean collectNow() {
        if (!lock.tryLock()) {
            return false;
        }
        return runLocked();
    }

    /** 요약 직전처럼 최신 상태가 꼭 필요할 때 — 돌고 있는 수집이 있으면 끝날 때까지 기다렸다가 한 번 더 돈다 */
    public void collectAndWait() {
        lock.lock();
        runLocked();
    }

    private boolean runLocked() {
        try {
            long started = System.currentTimeMillis();
            TranscriptCollector.CollectStats stats = transcripts.collectAll();
            int newCommits = commits.collectAll();
            int responses = usage.collectAll();
            int agentItems = agent.collectAll();
            lastCollectedAt.set(Instant.now());
            if (stats.changedFiles() > 0 || newCommits > 0 || responses > 0 || agentItems > 0) {
                log.info("수집 완료: 세션 파일 {}개·{}줄, 커밋 {}건, 응답 사용량 {}줄, 도구·턴 {}건 ({}ms)", stats.changedFiles(),
                        stats.lines(), newCommits, responses, agentItems, System.currentTimeMillis() - started);
            }
            return true;
        } catch (Exception e) {
            log.error("수집 실패", e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    public Instant lastCollectedAt() {
        return lastCollectedAt.get();
    }
}
