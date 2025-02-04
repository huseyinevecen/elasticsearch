/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ilm;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.health.Diagnosis;
import org.elasticsearch.health.HealthIndicatorDetails;
import org.elasticsearch.health.HealthIndicatorImpact;
import org.elasticsearch.health.HealthIndicatorResult;
import org.elasticsearch.health.HealthIndicatorService;
import org.elasticsearch.health.ImpactArea;
import org.elasticsearch.health.SimpleHealthIndicatorDetails;
import org.elasticsearch.health.node.HealthInfo;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.ilm.AllocateAction;
import org.elasticsearch.xpack.core.ilm.DeleteAction;
import org.elasticsearch.xpack.core.ilm.DeleteStep;
import org.elasticsearch.xpack.core.ilm.ForceMergeAction;
import org.elasticsearch.xpack.core.ilm.ForceMergeStep;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.MigrateAction;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.ilm.RolloverAction;
import org.elasticsearch.xpack.core.ilm.RolloverStep;
import org.elasticsearch.xpack.core.ilm.SearchableSnapshotAction;
import org.elasticsearch.xpack.core.ilm.SegmentCountStep;
import org.elasticsearch.xpack.core.ilm.ShrinkAction;
import org.elasticsearch.xpack.core.ilm.WaitForActiveShardsStep;
import org.elasticsearch.xpack.core.ilm.WaitForDataTierStep;
import org.elasticsearch.xpack.core.ilm.WaitForIndexColorStep;
import org.elasticsearch.xpack.core.ilm.WaitForNoFollowersStep;
import org.elasticsearch.xpack.core.ilm.WaitForRolloverReadyStep;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.elasticsearch.health.HealthStatus.GREEN;
import static org.elasticsearch.health.HealthStatus.YELLOW;
import static org.elasticsearch.xpack.core.ilm.LifecycleOperationMetadata.currentILMMode;
import static org.elasticsearch.xpack.ilm.IlmHealthIndicatorService.RuleConfig.Builder.actionRule;
import static org.elasticsearch.xpack.ilm.IlmHealthIndicatorService.StepRule.stepRule;

/**
 * This indicator reports health for index lifecycle management component.
 *
 * Indicator will report YELLOW status when ILM is not running and there are configured policies.
 * Constant indexing could eventually use entire disk space on hot topology in such cases.
 *
 * ILM must be running to fix warning reported by this indicator.
 */
public class IlmHealthIndicatorService implements HealthIndicatorService {

    public static final String NAME = "ilm";
    public static final String HELP_URL = "https://ela.st/fix-ilm";
    public static final Diagnosis ILM_NOT_RUNNING = new Diagnosis(
        new Diagnosis.Definition(
            NAME,
            "ilm_disabled",
            "Index Lifecycle Management is stopped",
            "Start Index Lifecycle Management using [POST /_ilm/start].",
            HELP_URL
        ),
        null
    );

    public static final String AUTOMATION_DISABLED_IMPACT_ID = "automation_disabled";
    public static final String STAGNATING_INDEX_IMPACT_ID = "stagnating_index";
    public static final List<HealthIndicatorImpact> AUTOMATION_DISABLED_IMPACT = List.of(
        new HealthIndicatorImpact(
            NAME,
            AUTOMATION_DISABLED_IMPACT_ID,
            3,
            "Automatic index lifecycle and data retention management is disabled. The performance and stability of the cluster "
                + "could be impacted.",
            List.of(ImpactArea.DEPLOYMENT_MANAGEMENT)
        )
    );

    public static final List<HealthIndicatorImpact> STAGNATING_INDEX_IMPACT = List.of(
        new HealthIndicatorImpact(
            NAME,
            STAGNATING_INDEX_IMPACT_ID,
            3,
            "Automatic index lifecycle and data retention management cannot make progress on one or more indices. The performance and "
                + "stability of the indices and/or the cluster could be impacted.",
            List.of(ImpactArea.DEPLOYMENT_MANAGEMENT)
        )
    );

    private static final TimeValue ONE_DAY = TimeValue.timeValueDays(1);

    static final Map<String, RuleConfig> RULES_BY_ACTION_CONFIG = Map.of(
        RolloverAction.NAME,
        actionRule(RolloverAction.NAME).stepRules(
            stepRule(WaitForActiveShardsStep.NAME, ONE_DAY),
            stepRule(WaitForRolloverReadyStep.NAME, ONE_DAY),
            stepRule(RolloverStep.NAME, ONE_DAY)
        ),
        //
        MigrateAction.NAME,
        actionRule(MigrateAction.NAME).maxTimeOnAction(ONE_DAY).noStepRules(),
        //
        SearchableSnapshotAction.NAME,
        actionRule(SearchableSnapshotAction.NAME).maxTimeOnAction(ONE_DAY)
            .stepRules(
                stepRule(WaitForDataTierStep.NAME, ONE_DAY),
                stepRule(WaitForIndexColorStep.NAME, ONE_DAY),
                // The no-follower step is added here because an `UnfollowAction` is added before the `shrinkAction` in the follower cluster
                stepRule(WaitForNoFollowersStep.NAME, ONE_DAY)
            ),
        //
        DeleteAction.NAME,
        actionRule(DeleteAction.NAME).stepRules(stepRule(DeleteStep.NAME, ONE_DAY)),
        //
        ShrinkAction.NAME,
        actionRule(ShrinkAction.NAME).maxTimeOnAction(ONE_DAY)
            .stepRules(
                // The no-follower step is added here because an `unfollowAction` is added before the `shrinkAction` in the follower
                // cluster.
                stepRule(WaitForNoFollowersStep.NAME, ONE_DAY)
            ),
        //
        AllocateAction.NAME,
        actionRule(AllocateAction.NAME).maxTimeOnAction(ONE_DAY).noStepRules(),
        //
        ForceMergeAction.NAME,
        actionRule(ForceMergeAction.NAME).maxTimeOnAction(ONE_DAY)
            .stepRules(
                stepRule(WaitForIndexColorStep.NAME, ONE_DAY),
                stepRule(ForceMergeStep.NAME, ONE_DAY),
                stepRule(SegmentCountStep.NAME, ONE_DAY)
            )
        //
        // The next rule has to be commented because of this issue https://github.com/elastic/elasticsearch/issues/96705
        // DownsampleAction.NAME,
        // actionRule(DownsampleAction.NAME).maxTimeOnAction(ONE_DAY).stepRules(stepRule(WaitForNoFollowersStep.NAME, ONE_DAY))
    );

    public static final Collection<RuleConfig> ILM_RULE_EVALUATOR = RULES_BY_ACTION_CONFIG.values();

    static final Map<String, Diagnosis.Definition> STAGNATING_ACTION_DEFINITIONS = RULES_BY_ACTION_CONFIG.entrySet()
        .stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> new Diagnosis.Definition(
                    NAME,
                    "stagnating_action:" + entry.getKey(),
                    "Some indices have been stagnated on the action [" + entry.getKey() + "] longer than the expected time.",
                    "Check the current status of the Index Lifecycle Management for every affected index using the "
                        + "[GET /<affected_index_name>/_ilm/explain] API. Please replace the <affected_index_name> in the API "
                        + "with the actual index name.",
                    "https://ela.st/ilm-explain"
                )
            )
        );

    private final ClusterService clusterService;
    private final StagnatingIndicesFinder stagnatingIndicesFinder;

    public IlmHealthIndicatorService(ClusterService clusterService, StagnatingIndicesFinder stagnatingIndicesFinder) {
        this.clusterService = clusterService;
        this.stagnatingIndicesFinder = stagnatingIndicesFinder;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public HealthIndicatorResult calculate(boolean verbose, int maxAffectedResourcesCount, HealthInfo healthInfo) {
        final var currentState = clusterService.state();
        var ilmMetadata = currentState.metadata().custom(IndexLifecycleMetadata.TYPE, IndexLifecycleMetadata.EMPTY);
        final var currentMode = currentILMMode(currentState);
        if (ilmMetadata.getPolicyMetadatas().isEmpty()) {
            return createIndicator(
                GREEN,
                "No Index Lifecycle Management policies configured",
                createDetails(verbose, ilmMetadata, currentMode),
                Collections.emptyList(),
                Collections.emptyList()
            );
        } else if (currentMode != OperationMode.RUNNING) {
            return createIndicator(
                YELLOW,
                "Index Lifecycle Management is not running",
                createDetails(verbose, ilmMetadata, currentMode),
                AUTOMATION_DISABLED_IMPACT,
                List.of(ILM_NOT_RUNNING)
            );
        } else {
            var stagnatingIndices = stagnatingIndicesFinder.find();

            if (stagnatingIndices.isEmpty()) {
                return createIndicator(
                    GREEN,
                    "Index Lifecycle Management is running",
                    createDetails(verbose, ilmMetadata, currentMode),
                    Collections.emptyList(),
                    Collections.emptyList()
                );
            } else {
                return createIndicator(
                    YELLOW,
                    (stagnatingIndices.size() > 1 ? stagnatingIndices.size() + " indices have" : "An index has")
                        + " stayed on the same action longer than expected.",
                    createDetails(verbose, ilmMetadata, currentMode, stagnatingIndices),
                    STAGNATING_INDEX_IMPACT,
                    createDiagnoses(stagnatingIndices, maxAffectedResourcesCount)
                );
            }
        }
    }

    private static HealthIndicatorDetails createDetails(boolean verbose, IndexLifecycleMetadata ilmMetadata, OperationMode currentMode) {
        return createDetails(verbose, ilmMetadata, currentMode, List.of());
    }

    private static List<Diagnosis> createDiagnoses(List<IndexMetadata> stagnatingIndices, int maxAffectedResourcesCount) {
        return stagnatingIndices.stream()
            .collect(groupingBy(md -> md.getLifecycleExecutionState().action()))
            .entrySet()
            .stream()
            .map(action -> {
                var affectedIndices = action.getValue()
                    .stream()
                    .map(IndexMetadata::getIndex)
                    .map(Index::getName)
                    .limit(Math.min(maxAffectedResourcesCount, action.getValue().size()))
                    .collect(Collectors.toCollection(TreeSet::new));
                var affectedPolicies = action.getValue()
                    .stream()
                    .map(IndexMetadata::getLifecyclePolicyName)
                    .limit(Math.min(maxAffectedResourcesCount, action.getValue().size()))
                    .collect(Collectors.toCollection(TreeSet::new));
                return new Diagnosis(
                    STAGNATING_ACTION_DEFINITIONS.get(action.getKey()),
                    List.of(
                        new Diagnosis.Resource(Diagnosis.Resource.Type.ILM_POLICY, affectedPolicies),
                        new Diagnosis.Resource(Diagnosis.Resource.Type.INDEX, affectedIndices)
                    )
                );
            })
            .toList();
    }

    private static HealthIndicatorDetails createDetails(
        boolean verbose,
        IndexLifecycleMetadata metadata,
        OperationMode mode,
        List<IndexMetadata> stagnatingIndices
    ) {
        if (verbose == false) {
            return HealthIndicatorDetails.EMPTY;
        }

        var details = new HashMap<String, Object>();

        details.put("ilm_status", mode);
        details.put("policies", metadata.getPolicies().size());
        details.put("stagnating_indices", stagnatingIndices.size());

        var stagnatingIndicesPerAction = stagnatingIndices.stream()
            .collect(groupingBy(md -> md.getLifecycleExecutionState().action(), counting()));

        if (stagnatingIndicesPerAction.isEmpty() == false) {
            RULES_BY_ACTION_CONFIG.forEach((action, value) -> stagnatingIndicesPerAction.putIfAbsent(action, 0L));
            details.put("stagnating_indices_per_action", stagnatingIndicesPerAction);
        }

        return new SimpleHealthIndicatorDetails(details);
    }

    /**
     * Class in charge of find all the indices that are _potentially_ stagnated at some ILM action. To find the indices, it uses a list of
     * rules evaluators (Check {@link IlmHealthIndicatorService#RULES_BY_ACTION_CONFIG to the current rules}
     */
    static class StagnatingIndicesFinder {
        private final ClusterService clusterService;
        private final Collection<RuleConfig> rules;
        private final LongSupplier nowSupplier;

        StagnatingIndicesFinder(ClusterService clusterService, Collection<RuleConfig> rules, LongSupplier nowSupplier) {
            this.clusterService = clusterService;
            this.rules = rules;
            this.nowSupplier = nowSupplier;
        }

        /**
         * @return A list containing the ILM managed indices that are stagnated in any ILM action/step.
         */
        public List<IndexMetadata> find() {
            var metadata = clusterService.state().metadata();
            var now = nowSupplier.getAsLong();
            return metadata.indices()
                .values()
                .stream()
                .filter(metadata::isIndexManagedByILM)
                .filter(md -> isStagnated(rules, now, md))
                .toList();
        }
    }

    static boolean isStagnated(Collection<RuleConfig> rules, Long now, IndexMetadata indexMetadata) {
        return rules.stream().anyMatch(r -> r.test(now, indexMetadata));
    }

    @FunctionalInterface
    public interface RuleConfig {

        boolean test(Long now, IndexMetadata indexMetadata);

        static TimeValue getElapsedTime(Long now, Long currentTime) {
            return currentTime == null ? TimeValue.ZERO : TimeValue.timeValueMillis(now - currentTime);
        }

        default RuleConfig and(RuleConfig other) {
            return (now, indexMetadata) -> test(now, indexMetadata) && other.test(now, indexMetadata);
        }

        default RuleConfig or(RuleConfig other) {
            return (now, indexMetadata) -> test(now, indexMetadata) || other.test(now, indexMetadata);
        }

        /**
         * Builder class that provides a simple DSL to create composed `RuleConfig`s. It'll enforce that any rule to start with an action
         * and then optionally define the max-time-on an action or a set of steps. In general, the output rule will be in the form:
         *
         *      action-rule AND (step-rule-1 OR step-rule-2 OR step-rule-3 ...)?
         *
         * Where the list of `step-rule-xx` could be empty.
         *
         * To have a clearer idea of the final shape of the rules, check the methods {@link ActionRule#test} and {@link StepRule#test}
         */
        class Builder {
            private String action;
            private TimeValue maxTimeOn = null;

            static Builder actionRule(String action) {
                var builder = new Builder();
                builder.action = action;
                return builder;
            }

            Builder maxTimeOnAction(TimeValue maxTimeOn) {
                this.maxTimeOn = maxTimeOn;
                return this;
            }

            RuleConfig stepRules(StepRule... stepRules) {
                assert stepRules.length > 0;
                if (stepRules.length == 1) {
                    return new ActionRule(action, maxTimeOn).and(stepRules[0]);
                } else {
                    RuleConfig stepRule = stepRules[0];
                    for (var i = 1; i < stepRules.length; i++) {
                        stepRule = stepRule.or(stepRules[i]);
                    }
                    return new ActionRule(action, maxTimeOn).and(stepRule);
                }
            }

            RuleConfig noStepRules() {
                return new ActionRule(action, maxTimeOn);
            }
        }
    }

    /**
     * Record defining a rule that will check the current action that an ILM-managed index is into.
     *
     * @param action    The action against which the rule will be checked.
     * @param maxTimeOn Maximum time that an index should spend on this action.
     */
    record ActionRule(String action, TimeValue maxTimeOn) implements RuleConfig {

        @Override
        public boolean test(Long now, IndexMetadata indexMetadata) {
            var currentAction = indexMetadata.getLifecycleExecutionState().action();
            if (maxTimeOn == null) {
                return action.equals(currentAction);
            } else {
                return action.equals(currentAction)
                    && maxTimeOn.compareTo(RuleConfig.getElapsedTime(now, indexMetadata.getLifecycleExecutionState().actionTime())) < 0;
            }
        }
    }

    /**
     * Record defining a rule that will check the current step that an ILM-managed index is into.
     *
     * @param step       The step against which the rule will be checked.
     * @param maxTimeOn  Maximum time that an index should spend on this step.
     * @param maxRetries Maximum number of times that a step should be retried.
     */
    public record StepRule(String step, TimeValue maxTimeOn, long maxRetries) implements RuleConfig {
        static StepRule stepRule(String name, TimeValue maxTimeOn) {
            return new StepRule(name, maxTimeOn, 100);
        }

        @Override
        public boolean test(Long now, IndexMetadata indexMetadata) {
            return step.equals(indexMetadata.getLifecycleExecutionState().step())
                && (maxTimeOn.compareTo(RuleConfig.getElapsedTime(now, indexMetadata.getLifecycleExecutionState().stepTime())) < 0
                    || indexMetadata.getLifecycleExecutionState().failedStepRetryCount() > maxRetries);
        }
    }
}
