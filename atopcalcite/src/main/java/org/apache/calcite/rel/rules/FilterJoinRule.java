/*
 * Copyright (C) 2016 Kyligence Inc. All rights reserved.
 *
 * http://kyligence.io
 *
 * This software is the confidential and proprietary information of
 * Kyligence Inc. ("Confidential Information"). You shall not disclose
 * such Confidential Information and shall use it only in accordance
 * with the terms of the license agreement you entered into with
 * Kyligence Inc.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.apache.calcite.rel.rules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.EquiJoin;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Turn it off.
 * Though try to turn it off in OLAPTableScan, sometimes it still triggerd.
 */

/**
 * Planner rule that pushes filters above and
 * within a join node into the join node and/or its children nodes.
 */
public abstract class FilterJoinRule extends RelOptRule {
    /** Predicate that always returns true. With this predicate, every filter
     * will be pushed into the ON clause. */
    public static final Predicate TRUE_PREDICATE = new Predicate() {
        public boolean apply(Join join, JoinRelType joinType, RexNode exp) {
            return true;
        }
    };

    /** Rule that pushes predicates from a Filter into the Join below them. */
    public static final FilterJoinRule FILTER_ON_JOIN = new FilterIntoJoinRule(true, RelFactories.LOGICAL_BUILDER,
            TRUE_PREDICATE);

    /** Dumber version of {@link #FILTER_ON_JOIN}. Not intended for production
     * use, but keeps some tests working for which {@code FILTER_ON_JOIN} is too
     * smart. */
    public static final FilterJoinRule DUMB_FILTER_ON_JOIN = new FilterIntoJoinRule(false, RelFactories.LOGICAL_BUILDER,
            TRUE_PREDICATE);

    /** Rule that pushes predicates in a Join into the inputs to the join. */
    public static final FilterJoinRule JOIN = new JoinConditionPushRule(RelFactories.LOGICAL_BUILDER, TRUE_PREDICATE);

    /** Whether to try to strengthen join-type. */
    private final boolean smart;

    /** Predicate that returns whether a filter is valid in the ON clause of a
     * join for this particular kind of join. If not, Calcite will push it back to
     * above the join. */
    private final Predicate predicate;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a FilterProjectTransposeRule with an explicit root operand and
     * factories.
     */
    protected FilterJoinRule(RelOptRuleOperand operand, String id, boolean smart, RelBuilderFactory relBuilderFactory,
            Predicate predicate) {
        super(operand, relBuilderFactory, "FilterJoinRule:" + id);
        this.smart = smart;
        this.predicate = Preconditions.checkNotNull(predicate);
    }

    /**
     * Creates a FilterJoinRule with an explicit root operand and
     * factories.
     */
    @Deprecated // to be removed before 2.0
    protected FilterJoinRule(RelOptRuleOperand operand, String id, boolean smart,
            RelFactories.FilterFactory filterFactory, RelFactories.ProjectFactory projectFactory) {
        this(operand, id, smart, RelBuilder.proto(filterFactory, projectFactory), TRUE_PREDICATE);
    }

    /**
     * Creates a FilterProjectTransposeRule with an explicit root operand and
     * factories.
     */
    @Deprecated // to be removed before 2.0
    protected FilterJoinRule(RelOptRuleOperand operand, String id, boolean smart,
            RelFactories.FilterFactory filterFactory, RelFactories.ProjectFactory projectFactory, Predicate predicate) {
        this(operand, id, smart, RelBuilder.proto(filterFactory, projectFactory), predicate);
    }

    //~ Methods ----------------------------------------------------------------

    protected void perform(RelOptRuleCall call, Filter filter, Join join) {
        final List<RexNode> joinFilters = RelOptUtil.conjunctions(join.getCondition());
        final List<RexNode> origJoinFilters = ImmutableList.copyOf(joinFilters);

        // If there is only the joinRel,
        // make sure it does not match a cartesian product joinRel
        // (with "true" condition), otherwise this rule will be applied
        // again on the new cartesian product joinRel.
        if (filter == null && joinFilters.isEmpty()) {
            return;
        }

        final List<RexNode> aboveFilters = filter != null ? RelOptUtil.conjunctions(filter.getCondition())
                : Lists.<RexNode> newArrayList();
        final ImmutableList<RexNode> origAboveFilters = ImmutableList.copyOf(aboveFilters);

        // Simplify Outer Joins
        JoinRelType joinType = join.getJoinType();
        if (smart && !origAboveFilters.isEmpty() && join.getJoinType() != JoinRelType.INNER) {
            joinType = RelOptUtil.simplifyJoin(join, origAboveFilters, joinType);
        }

        final List<RexNode> leftFilters = new ArrayList<>();
        final List<RexNode> rightFilters = new ArrayList<>();

        // TODO - add logic to derive additional filters.  E.g., from
        // (t1.a = 1 AND t2.a = 2) OR (t1.b = 3 AND t2.b = 4), you can
        // derive table filters:
        // (t1.a = 1 OR t1.b = 3)
        // (t2.a = 2 OR t2.b = 4)

        // Try to push down above filters. These are typically where clause
        // filters. They can be pushed down if they are not on the NULL
        // generating side.
        boolean filterPushed = false;
        if (RelOptUtil.classifyFilters(join, aboveFilters, joinType, !(join instanceof EquiJoin),
                !joinType.generatesNullsOnLeft(), !joinType.generatesNullsOnRight(), joinFilters, leftFilters,
                rightFilters)) {
            filterPushed = true;
        }

        // Move join filters up if needed
        validateJoinFilters(aboveFilters, joinFilters, join, joinType);

        // If no filter got pushed after validate, reset filterPushed flag
        if (leftFilters.isEmpty() && rightFilters.isEmpty() && joinFilters.size() == origJoinFilters.size()) {
            if (Sets.newHashSet(joinFilters).equals(Sets.newHashSet(origJoinFilters))) {
                filterPushed = false;
            }
        }

        // Try to push down filters in ON clause. A ON clause filter can only be
        // pushed down if it does not affect the non-matching set, i.e. it is
        // not on the side which is preserved.
        if (RelOptUtil.classifyFilters(join, joinFilters, joinType, false, !joinType.generatesNullsOnRight(),
                !joinType.generatesNullsOnLeft(), joinFilters, leftFilters, rightFilters)) {
            filterPushed = true;
        }

        // if nothing actually got pushed and there is nothing leftover,
        // then this rule is a no-op
        if ((!filterPushed && joinType == join.getJoinType())
                || (joinFilters.isEmpty() && leftFilters.isEmpty() && rightFilters.isEmpty())) {
            return;
        }

        // create Filters on top of the children if any filters were
        // pushed to them
        final RexBuilder rexBuilder = join.getCluster().getRexBuilder();
        final RelBuilder relBuilder = call.builder();
        final RelNode leftRel = relBuilder.push(join.getLeft()).filter(leftFilters).build();
        final RelNode rightRel = relBuilder.push(join.getRight()).filter(rightFilters).build();

        // create the new join node referencing the new children and
        // containing its new join filters (if there are any)
        final ImmutableList<RelDataType> fieldTypes = ImmutableList.<RelDataType> builder()
                .addAll(RelOptUtil.getFieldTypeList(leftRel.getRowType()))
                .addAll(RelOptUtil.getFieldTypeList(rightRel.getRowType())).build();
        final RexNode joinFilter = RexUtil.composeConjunction(rexBuilder,
                RexUtil.fixUp(rexBuilder, joinFilters, fieldTypes), false);

        // If nothing actually got pushed and there is nothing leftover,
        // then this rule is a no-op
        if (joinFilter.isAlwaysTrue() && leftFilters.isEmpty() && rightFilters.isEmpty()
                && joinType == join.getJoinType()) {
            return;
        }

        RelNode newJoinRel = join.copy(join.getTraitSet(), joinFilter, leftRel, rightRel, joinType,
                join.isSemiJoinDone());
        call.getPlanner().onCopy(join, newJoinRel);
        if (!leftFilters.isEmpty()) {
            call.getPlanner().onCopy(filter, leftRel);
        }
        if (!rightFilters.isEmpty()) {
            call.getPlanner().onCopy(filter, rightRel);
        }

        relBuilder.push(newJoinRel);

        // Create a project on top of the join if some of the columns have become
        // NOT NULL due to the join-type getting stricter.
        relBuilder.convert(join.getRowType(), false);

        // create a FilterRel on top of the join if needed
        relBuilder.filter(
                RexUtil.fixUp(rexBuilder, aboveFilters, RelOptUtil.getFieldTypeList(relBuilder.peek().getRowType())));

        call.transformTo(relBuilder.build());
    }

    /**
     * Validates that target execution framework can satisfy join filters.
     *
     * <p>If the join filter cannot be satisfied (for example, if it is
     * {@code l.c1 > r.c2} and the join only supports equi-join), removes the
     * filter from {@code joinFilters} and adds it to {@code aboveFilters}.
     *
     * <p>The default implementation does nothing; i.e. the join can handle all
     * conditions.
     *
     * @param aboveFilters Filter above Join
     * @param joinFilters Filters in join condition
     * @param join Join
     * @param joinType JoinRelType could be different from type in Join due to
     * outer join simplification.
     */
    protected void validateJoinFilters(List<RexNode> aboveFilters, List<RexNode> joinFilters, Join join,
            JoinRelType joinType) {
        final Iterator<RexNode> filterIter = joinFilters.iterator();
        while (filterIter.hasNext()) {
            RexNode exp = filterIter.next();
            if (!predicate.apply(join, joinType, exp)) {
                aboveFilters.add(exp);
                filterIter.remove();
            }
        }
    }

    /** Rule that pushes parts of the join condition to its inputs. */
    public static class JoinConditionPushRule extends FilterJoinRule {
        public JoinConditionPushRule(RelBuilderFactory relBuilderFactory, Predicate predicate) {
            super(RelOptRule.operand(Join.class, RelOptRule.any()), "FilterJoinRule:no-filter", true, relBuilderFactory,
                    predicate);
        }

        @Deprecated // to be removed before 2.0
        public JoinConditionPushRule(RelFactories.FilterFactory filterFactory,
                RelFactories.ProjectFactory projectFactory, Predicate predicate) {
            this(RelBuilder.proto(filterFactory, projectFactory), predicate);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Join join = call.rel(0);
            // HACK POINT
//            perform(call, null, join);
        }
    }

    /** Rule that tries to push filter expressions into a join
     * condition and into the inputs of the join. */
    public static class FilterIntoJoinRule extends FilterJoinRule {
        public FilterIntoJoinRule(boolean smart, RelBuilderFactory relBuilderFactory, Predicate predicate) {
            super(operand(Filter.class, operand(Join.class, RelOptRule.any())), "FilterJoinRule:filter", smart,
                    relBuilderFactory, predicate);
        }

        @Deprecated // to be removed before 2.0
        public FilterIntoJoinRule(boolean smart, RelFactories.FilterFactory filterFactory,
                RelFactories.ProjectFactory projectFactory, Predicate predicate) {
            this(smart, RelBuilder.proto(filterFactory, projectFactory), predicate);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Filter filter = call.rel(0);
            Join join = call.rel(1);
            // HACK POINT
//            perform(call, filter, join);
        }
    }

    /** Predicate that returns whether a filter is valid in the ON clause of a
     * join for this particular kind of join. If not, Calcite will push it back to
     * above the join. */
    public interface Predicate {
        boolean apply(Join join, JoinRelType joinType, RexNode exp);
    }
}

// End FilterJoinRule.java