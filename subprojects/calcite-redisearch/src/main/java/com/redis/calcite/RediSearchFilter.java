package com.redis.calcite;

import com.google.common.base.Preconditions;
import com.redis.lettucemod.api.search.Field;
import io.redisearch.querybuilder.QueryBuilder;
import io.redisearch.querybuilder.QueryNode;
import io.redisearch.querybuilder.Value;
import io.redisearch.querybuilder.Values;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.*;
import org.apache.calcite.sql.SqlKind;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of
 * {@link Filter} relational expression in RediSearch.
 */
public class RediSearchFilter extends Filter implements RediSearchRel {

    private final String match;
    private final Map<String, Field.Type> indexFields;

    RediSearchFilter(RelOptCluster cluster, RelTraitSet traitSet, RelNode input, RexNode condition, Map<String, Field.Type> indexFields) {

        super(cluster, traitSet, input, condition);
        this.indexFields = indexFields;

        Translator translator = new Translator(getRowType(), getCluster().getRexBuilder(), indexFields);
        this.match = translator.translateMatch(condition).toString();

        assert getConvention() == CONVENTION;
        assert getConvention() == input.getConvention();
    }

    @Override
    public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        return super.computeSelfCost(planner, mq).multiplyBy(0.1);
    }

    @Override
    public RediSearchFilter copy(RelTraitSet traitSet, RelNode input, RexNode condition) {
        return new RediSearchFilter(getCluster(), traitSet, input, condition, indexFields);
    }

    @Override
    public void implement(RediSearchImplementContext rediSearchImplementContext) {
        // first call the input down the tree.
        rediSearchImplementContext.visitChild(getInput());
        rediSearchImplementContext.addPredicates(Collections.singletonList(match));
    }

    /**
     * Translates {@link RexNode} expressions into RediSearch expression strings.
     */
    static class Translator {
        @SuppressWarnings("unused")
        private final RelDataType rowType;
        private final List<String> fieldNames;
        private final Map<String, Field.Type> indexFields;
        @SuppressWarnings("unused")
        private RexBuilder rexBuilder;

        Translator(RelDataType rowType, RexBuilder rexBuilder, Map<String, Field.Type> indexFields) {
            this.rowType = rowType;
            this.rexBuilder = rexBuilder;
            this.fieldNames = RediSearchRules.rediSearchFieldNames(rowType);
            this.indexFields = indexFields;
        }

        /**
         * Produce the OQL predicate string for the given condition.
         *
         * @param condition Condition to translate
         * @return OQL predicate string
         */
        private QueryNode translateMatch(RexNode condition) {
            // Remove SEARCH calls because current translation logic cannot handle it.
            // However, it would efficient to handle SEARCH explicitly; a RediSearch
            // 'IN SET' would always manifest as a SEARCH.
            final RexNode condition2 = RexUtil.expandSearch(rexBuilder, null, condition);

            // Returns condition decomposed by OR
            List<RexNode> disjunctions = RelOptUtil.disjunctions(condition2);
            if (disjunctions.size() == 1) {
                return translateAnd(disjunctions.get(0));
            } else {
                return translateOr(disjunctions);
            }
        }

        /**
         * Translate a conjunctive predicate to a OQL string.
         *
         * @param condition A conjunctive predicate
         * @return OQL string for the predicate
         */
        private QueryNode translateAnd(RexNode condition) {
            List<QueryNode> predicates = new ArrayList<>();
            for (RexNode node : RelOptUtil.conjunctions(condition)) {
                predicates.add(translateMatch2(node));
            }
            return QueryBuilder.intersect(predicates.toArray(new QueryNode[0]));
        }

        /**
         * Returns the field name for the left node to use for {@code IN SET}
         * query.
         */
        private String getLeftNodeFieldName(RexNode left) {
            switch (left.getKind()) {
                case INPUT_REF:
                    final RexInputRef left1 = (RexInputRef) left;
                    return fieldNames.get(left1.getIndex());
                case CAST:
                    // FIXME This will not work in all cases (for example, we ignore string encoding)
                    return getLeftNodeFieldName(((RexCall) left).operands.get(0));
                case ITEM:
                case OTHER_FUNCTION:
                    return left.accept(new RediSearchRules.RexToRediSearchTranslator(this.fieldNames));
                default:
                    return null;
            }
        }

        /**
         * Returns whether we can use the {@code IN SET} query clause to
         * improve query performance.
         */
        private boolean useInSetQueryClause(List<RexNode> disjunctions) {
            // Only use the in set for more than one disjunctions
            if (disjunctions.size() <= 1) {
                return false;
            }

            return disjunctions.stream().allMatch(node -> {
                // IN SET query can only be used for EQUALS
                if (node.getKind() != SqlKind.EQUALS) {
                    return false;
                }

                RexCall call = (RexCall) node;
                final RexNode left = call.operands.get(0);
                final RexNode right = call.operands.get(1);

                // The right node should always be literal
                if (right.getKind() != SqlKind.LITERAL) {
                    return false;
                }

                String name = getLeftNodeFieldName(left);
                return name != null;
            });
        }

        /**
         * Creates OQL {@code IN SET} predicate string.
         */
        private QueryNode translateInSet(List<RexNode> disjunctions) {
            Preconditions.checkArgument(!disjunctions.isEmpty(), "empty disjunctions");

            RexNode firstNode = disjunctions.get(0);
            RexCall firstCall = (RexCall) firstNode;

            final RexNode left = firstCall.operands.get(0);
            String name = getLeftNodeFieldName(left);
            Field.Type type = type(name);
            switch (type) {
                case NUMERIC:
                    return QueryBuilder.union(name, values(disjunctions, Double.class).map(Values::eq).toArray(Value[]::new));
                case TAG:
                    return QueryBuilder.intersect(name, Values.tags(values(disjunctions, String.class).toArray(String[]::new)));
                case TEXT:
                    return QueryBuilder.union(name, values(disjunctions, String.class).map(Values::value).toArray(Value[]::new));
                default:
                    throw new UnsupportedOperationException("Field type " + type + " not supported");
            }
        }

        private <T> Stream<T> values(List<RexNode> disjunctions, Class<T> clazz) {
            return disjunctions.stream().map(node -> ((RexLiteral) ((RexCall) node).operands.get(1)).getValueAs(clazz));
        }

        private String getLeftNodeFieldNameForNode(RexNode node) {
            final RexCall call = (RexCall) node;
            final RexNode left = call.operands.get(0);
            return getLeftNodeFieldName(left);
        }

        private List<RexNode> getLeftNodeDisjunctions(RexNode node, List<RexNode> disjunctions) {
            List<RexNode> leftNodeDisjunctions = new ArrayList<>();
            String leftNodeFieldName = getLeftNodeFieldNameForNode(node);

            if (leftNodeFieldName != null) {
                leftNodeDisjunctions = disjunctions.stream().filter(rexNode -> {
                    RexCall rexCall = (RexCall) rexNode;
                    RexNode rexCallLeft = rexCall.operands.get(0);
                    return leftNodeFieldName.equals(getLeftNodeFieldName(rexCallLeft));
                }).collect(Collectors.toList());
            }

            return leftNodeDisjunctions;
        }

        private QueryNode translateOr(List<RexNode> disjunctions) {
            List<QueryNode> predicates = new ArrayList<>();

            List<String> leftFieldNameList = new ArrayList<>();
            List<String> inSetLeftFieldNameList = new ArrayList<>();

            for (RexNode node : disjunctions) {
                final String leftNodeFieldName = getLeftNodeFieldNameForNode(node);
                // If any one left node is processed with IN SET predicate
                // all the nodes are already handled
                if (inSetLeftFieldNameList.contains(leftNodeFieldName)) {
                    continue;
                }

                List<RexNode> leftNodeDisjunctions = new ArrayList<>();
                boolean useInSetQueryClause = false;

                // In case the left field node name is already processed and not applicable
                // for IN SET query clause, we can skip the checking
                if (!leftFieldNameList.contains(leftNodeFieldName)) {
                    leftNodeDisjunctions = getLeftNodeDisjunctions(node, disjunctions);
                    useInSetQueryClause = useInSetQueryClause(leftNodeDisjunctions);
                }

                if (useInSetQueryClause) {
                    predicates.add(translateInSet(leftNodeDisjunctions));
                    inSetLeftFieldNameList.add(leftNodeFieldName);
                } else if (RelOptUtil.conjunctions(node).size() > 1) {
                    predicates.add(translateMatch(node));
                } else {
                    predicates.add(translateMatch2(node));
                }
                leftFieldNameList.add(leftNodeFieldName);
            }
            return QueryBuilder.union(predicates.toArray(new QueryNode[0]));
        }

        enum Operator {
            EQ, GT, LT, GE, LE
        }

        /**
         * Translate a binary relation.
         */
        private QueryNode translateMatch2(RexNode node) {
            RexNode child;
            switch (node.getKind()) {
                case EQUALS:
                    return translateBinary(Operator.EQ, Operator.EQ, (RexCall) node);
                case LESS_THAN:
                    return translateBinary(Operator.LT, Operator.GT, (RexCall) node);
                case LESS_THAN_OR_EQUAL:
                    return translateBinary(Operator.LE, Operator.GE, (RexCall) node);
                case GREATER_THAN:
                    return translateBinary(Operator.GT, Operator.LT, (RexCall) node);
                case GREATER_THAN_OR_EQUAL:
                    return translateBinary(Operator.GE, Operator.LE, (RexCall) node);
                case INPUT_REF:
                    return translateBinary2(Operator.EQ, node, rexBuilder.makeLiteral(true));
                case NOT:
                    child = ((RexCall) node).getOperands().get(0);
                    if (child.getKind() == SqlKind.CAST) {
                        child = ((RexCall) child).getOperands().get(0);
                    }
                    if (child.getKind() == SqlKind.INPUT_REF) {
                        return translateBinary2(Operator.EQ, child, rexBuilder.makeLiteral(false));
                    }
                    break;
                case CAST:
                    return translateMatch2(((RexCall) node).getOperands().get(0));
                default:
                    break;
            }
            throw new AssertionError("Cannot translate " + node + ", kind=" + node.getKind());
        }

        /**
         * Translates a call to a binary operator, reversing arguments if
         * necessary.
         */
        private QueryNode translateBinary(Operator op, Operator rop, RexCall call) {
            final RexNode left = call.operands.get(0);
            final RexNode right = call.operands.get(1);
            QueryNode expression = translateBinary2(op, left, right);
            if (expression != null) {
                return expression;
            }
            expression = translateBinary2(rop, right, left);
            if (expression != null) {
                return expression;
            }
            throw new AssertionError("cannot translate op " + op + " call " + call);
        }

        /**
         * Translates a call to a binary operator. Returns null on failure.
         */
        private QueryNode translateBinary2(Operator op, RexNode left, RexNode right) {
            if (right.getKind() != SqlKind.LITERAL) {
                return null;
            }

            final RexLiteral rightLiteral = (RexLiteral) right;
            switch (left.getKind()) {
                case INPUT_REF:
                    final RexInputRef left1 = (RexInputRef) left;
                    String name = fieldNames.get(left1.getIndex());
                    return translateOp2(op, name, rightLiteral);
                case CAST:
                    // FIXME This will not work in all cases (for example, we ignore string encoding)
                    return translateBinary2(op, ((RexCall) left).operands.get(0), right);
                case ITEM:
                    String item = left.accept(new RediSearchRules.RexToRediSearchTranslator(this.fieldNames));
                    return QueryBuilder.intersect(item, value(type(item), op, rightLiteral));
                default:
                    return null;
            }
        }

        /**
         * Combines a field name, operator, and literal to produce a predicate string.
         */
        private QueryNode translateOp2(Operator op, String name, RexLiteral right) {
            return QueryBuilder.intersect(name, value(type(name), op, right));
        }

        private Field.Type type(String name) {
            return indexFields.getOrDefault(name, Field.Type.TEXT);
        }

        public static Value value(Field.Type type, Operator op, RexLiteral literal) {
            switch (type) {
                case NUMERIC:
                    Double value = literal.getValueAs(Double.class);
                    switch (op) {
                        case EQ:
                            return Values.eq(value);
                        case GE:
                            return Values.ge(value);
                        case GT:
                            return Values.gt(value);
                        case LE:
                            return Values.le(value);
                        case LT:
                            return Values.lt(value);
                    }
                    throw new UnsupportedOperationException("Unsupported operator " + op + " for field type " + type);
                case TAG:
                    if (op == Operator.EQ) {
                        return Values.tags(literal.getValueAs(String.class));
                    }
                    throw new UnsupportedOperationException("Unsupported operator " + op + " for field type " + type);
                case TEXT:
                    if (op == Operator.EQ) {
                        return Values.value(literal.getValueAs(String.class));
                    }
                    throw new UnsupportedOperationException("Unsupported operator " + op + " for field type " + type);
                default:
                    throw new UnsupportedOperationException("Unsupported operator " + op + " for field type " + type);
            }
        }
    }
}