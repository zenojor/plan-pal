package com.weekendplanner.engine;

import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.provider.AvailabilityProvider;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.provider.SandboxAvailabilityProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PlanningToolOrchestrator {

    private final PoiProvider poiProvider;
    private final AvailabilityProvider availabilityProvider;
    private final CandidateScorer scorer;
    private final Executor executor;

    @Value("${agent.fast.max-checks-per-category:3}")
    private int maxChecksPerPhase = 3;

    @Value("${agent.queue-threshold-minutes:30}")
    private int queueThresholdMinutes = 30;

    @Autowired
    public PlanningToolOrchestrator(PoiProvider poiProvider,
                                    AvailabilityProvider availabilityProvider,
                                    CandidateScorer scorer,
                                    @Value("${agent.fast.tool-concurrency:6}") int concurrency) {
        this.poiProvider = poiProvider;
        this.availabilityProvider = availabilityProvider;
        this.scorer = scorer;
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency), daemonThreadFactory());
    }

    public PlanningToolOrchestrator(PoiProvider poiProvider) {
        this(poiProvider, new SandboxAvailabilityProvider(poiProvider), new CandidateScorer(), 6);
    }

    private ThreadFactory daemonThreadFactory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "planning-tool-orchestrator-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public CandidatePool collectCandidates(String planId, PlanIntent intent, List<SearchTask> tasks) {
        long started = System.currentTimeMillis();
        List<String> degradation = new ArrayList<>();
        List<CompletableFuture<TaskResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> runSearchTask(task), executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<CandidatePool.TaskStat> stats = new ArrayList<>();
        Map<String, Map<String, MutableCandidate>> byPhase = new LinkedHashMap<>();
        for (CompletableFuture<TaskResult> future : futures) {
            TaskResult result = future.join();
            stats.add(result.stat());
            if (!result.stat().success()) {
                degradation.add("search task " + result.stat().taskId() + " failed: " + result.stat().note());
                continue;
            }
            for (PoiDto poi : result.results()) {
                if (!scorer.isAllowed(poi, intent)) continue;
                byPhase.computeIfAbsent(result.task().phase(), ignored -> new LinkedHashMap<>());
                Map<String, MutableCandidate> phaseMap = byPhase.get(result.task().phase());
                MutableCandidate current = phaseMap.computeIfAbsent(poi.poiId(),
                        ignored -> new MutableCandidate(poi, result.task().phase()));
                current.taskIds.add(result.task().id());
                current.matchedTags.addAll(scorer.matchedTags(poi, result.task()));
                current.bestPriority = Math.min(current.bestPriority, result.task().priority());
            }
        }

        addExplicitPoiCandidates(intent, tasks, byPhase);

        Map<String, List<CandidateProfile>> phaseCandidates = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, MutableCandidate>> entry : byPhase.entrySet()) {
            boolean hasExplicit = entry.getValue().values().stream().anyMatch(candidate -> candidate.explicit);
            List<CandidateProfile> profiles = entry.getValue().values().stream()
                    .filter(candidate -> !hasExplicit || candidate.explicit)
                    .map(candidate -> candidate.toProfile(scorer.score(candidate.poi, candidate.phase, intent)))
                    .sorted(Comparator.comparingDouble(CandidateProfile::score).reversed())
                    .toList();
            phaseCandidates.put(entry.getKey(), profiles);
        }

        if (tasks.isEmpty()) {
            degradation.add("no search tasks were compiled");
        }
        long elapsed = System.currentTimeMillis() - started;
        stats.add(new CandidatePool.TaskStat("POOL", "ALL", "ALL",
                phaseCandidates.values().stream().mapToInt(List::size).sum(), elapsed, true, "candidate pool merged"));
        return new CandidatePool(planId, phaseCandidates, stats, degradation);
    }

    public AvailabilitySelection selectAvailable(String phase, List<CandidateProfile> candidates,
                                                 PlanIntent intent, String targetTime, Set<String> usedPoiIds) {
        List<CandidateProfile> top = candidates.stream()
                .filter(candidate -> usedPoiIds == null || !usedPoiIds.contains(candidate.poi().poiId()))
                .limit(Math.max(1, maxChecksPerPhase))
                .toList();
        if (top.isEmpty()) {
            return AvailabilitySelection.none("no candidates for " + phase, List.of());
        }

        List<CompletableFuture<CandidateProfile>> futures = top.stream()
                .map(candidate -> CompletableFuture.supplyAsync(() -> check(candidate, intent, targetTime), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<CandidateProfile> checked = futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingDouble(CandidateProfile::score).reversed())
                .toList();
        Optional<CandidateProfile> accepted = checked.stream()
                .filter(candidate -> candidate.rejectionReason() == null || candidate.rejectionReason().isBlank())
                .findFirst();
        if (accepted.isPresent()) {
            CandidateProfile candidate = accepted.get();
            return new AvailabilitySelection(candidate.poi(), candidate.availability(), false, null, checked);
        }
        String note = "availability rejected " + checked.stream()
                .map(candidate -> candidate.poi().name() + "(" + candidate.rejectionReason() + ")")
                .toList();
        return AvailabilitySelection.none(note, checked);
    }

    private TaskResult runSearchTask(SearchTask task) {
        long started = System.currentTimeMillis();
        try {
            List<PoiDto> results = poiProvider.searchByCategory(task.category(), task.tags(), task.radiusKm()).stream()
                    .limit(Math.max(1, task.limit()))
                    .toList();
            return new TaskResult(task, results, new CandidatePool.TaskStat(
                    task.id(), task.phase(), task.category(), results.size(),
                    System.currentTimeMillis() - started, true, task.reason()));
        } catch (Exception e) {
            return new TaskResult(task, List.of(), new CandidatePool.TaskStat(
                    task.id(), task.phase(), task.category(), 0,
                    System.currentTimeMillis() - started, false, e.getMessage()));
        }
    }

    private CandidateProfile check(CandidateProfile candidate, PlanIntent intent, String targetTime) {
        try {
            CheckResponse availability = availabilityProvider.checkAvailability(
                    candidate.poi().poiId(), targetTime, intent.headcount() > 0 ? intent.headcount() : 1);
            String rejection = isAcceptable(availability) ? null
                    : availability.status() + "/" + availability.queueTimeMinutes() + "min";
            return candidate.withAvailability(availability, rejection);
        } catch (Exception e) {
            CheckResponse fallback = new CheckResponse(candidate.poi().poiId(), "UNKNOWN",
                    queueThresholdMinutes + 1, false);
            return candidate.withAvailability(fallback, "check failed: " + e.getMessage());
        }
    }

    private boolean isAcceptable(CheckResponse availability) {
        String status = availability.status() == null ? "UNKNOWN" : availability.status();
        if ("SOLD_OUT".equalsIgnoreCase(status) || "UNKNOWN".equalsIgnoreCase(status)) return false;
        return availability.queueTimeMinutes() <= queueThresholdMinutes;
    }

    private void addExplicitPoiCandidates(PlanIntent intent, List<SearchTask> tasks,
                                          Map<String, Map<String, MutableCandidate>> byPhase) {
        Set<String> taskPhases = new HashSet<>();
        for (SearchTask task : tasks) taskPhases.add(task.phase());
        for (String poiId : extractPoiIds(intent.originalPrompt())) {
            poiProvider.findById(poiId).ifPresent(poi -> {
                String phase = phaseForPoi(poi);
                if (!taskPhases.contains(phase)) return;
                byPhase.computeIfAbsent(phase, ignored -> new LinkedHashMap<>());
                MutableCandidate candidate = byPhase.get(phase).computeIfAbsent(poi.poiId(),
                        ignored -> new MutableCandidate(poi, phase));
                candidate.taskIds.add("EXPLICIT:" + poi.poiId());
                candidate.explicit = true;
            });
        }
    }

    private List<String> extractPoiIds(String prompt) {
        if (prompt == null) return List.of();
        List<String> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("(P\\d{3}|H\\d{3}|S\\d{3}|B0[0-9A-Z]{8,})").matcher(prompt);
        while (matcher.find()) ids.add(matcher.group());
        return ids;
    }

    private String phaseForPoi(PoiDto poi) {
        if (!"RESTAURANT".equalsIgnoreCase(poi.category())) return "ACTIVITY";
        Set<String> tags = new HashSet<>();
        if (poi.tags() != null) {
            for (String tag : poi.tags()) tags.add(tag == null ? "" : tag.toLowerCase(Locale.ROOT));
        }
        if (tags.contains("bar") || tags.contains("drinks") || tags.contains("club")
                || tags.contains("nightlife") || tags.contains("cocktail") || tags.contains("quiet_bar")) {
            return "DRINKS";
        }
        return "DINING";
    }

    private record TaskResult(SearchTask task, List<PoiDto> results, CandidatePool.TaskStat stat) {}

    private static class MutableCandidate {
        private final PoiDto poi;
        private final String phase;
        private final Set<String> matchedTags = new LinkedHashSet<>();
        private final Set<String> taskIds = new LinkedHashSet<>();
        private int bestPriority = 100;
        private boolean explicit;

        private MutableCandidate(PoiDto poi, String phase) {
            this.poi = poi;
            this.phase = phase;
        }

        private CandidateProfile toProfile(double score) {
            double boostedScore = score + Math.max(0, 100 - bestPriority) * 6.0 + matchedTags.size() * 30.0;
            return new CandidateProfile(poi, phase, explicit ? boostedScore + 10_000 : boostedScore, new ArrayList<>(matchedTags),
                    null, new ArrayList<>(taskIds), null);
        }
    }
}
