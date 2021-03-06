package de.zalando.bigbash.parser;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import de.zalando.bigbash.commands.TableStripper;
import de.zalando.bigbash.entities.*;
import de.zalando.bigbash.exceptions.BigBashException;
import de.zalando.bigbash.grammar.BashSqlBaseListener;
import de.zalando.bigbash.grammar.BashSqlListener;
import de.zalando.bigbash.grammar.BashSqlParser;
import de.zalando.bigbash.pipes.BashCommand;
import de.zalando.bigbash.pipes.BashPipe;
import org.aeonbits.owner.ConfigCache;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by bvonloesch on 6/12/14.
 */
public class BashSqlSelectTranslater {

    private final boolean optimizationJoins;
    private final boolean optRemoveUnusedColumns;
    private Map<String, BashSqlTable> tables;
    private final boolean useSortAggregation;

    public BashSqlSelectTranslater(final Map<String, BashSqlTable> tables, boolean useSortAggregation) {
        ProgramConfig programConfig = ConfigCache.getOrCreate(ProgramConfig.class);
        this.tables = tables;
        optimizationJoins = programConfig.isOptimizingJoins();
        optRemoveUnusedColumns = programConfig.isRemovingUnusedColumns();
        this.useSortAggregation = useSortAggregation;
    }

    public String getSelectExpression(final SelectStmtData selectData) {

        //Check
        if (selectData.isDistinctQuery() && selectData.getGroupByExpr() != null
                && selectData.getGroupByExpr().size() > 0) {
            throw new BigBashException("Cannot use DISTINCT and GROUP BY together in a query.",
                    EditPosition.fromContext(selectData.getSelectStmt()));
        }

        //Remove unsused tables
        tables = removeUnusedTables(selectData);

        // Optimization: Remove unused columns
        if (optRemoveUnusedColumns) {
            prepareTables(selectData);
        }

        // Optimization: Filter before join if possible
        if (optimizationJoins) {
            if (selectData.getFromStatementContext().join_clause() != null) {
                Set<String> joinedTables = Sets.newHashSet();
                String tableName = selectData.getFromStatementContext().table_or_subquery().table_name().getText();
                if (selectData.getFromStatementContext().table_or_subquery().table_alias() != null) {
                    tableName = selectData.getFromStatementContext().table_or_subquery().table_alias().getText();
                }
                joinedTables.add(tableName.toLowerCase());

                int nrOfJoins = selectData.getFromStatementContext().join_clause().table_or_subquery().size();
                for (int i = 0; i < nrOfJoins; i++) {
                    String newTable = selectData.getFromStatementContext().join_clause().table_or_subquery(i).getText();
                    if (selectData.getFromStatementContext().join_clause().table_or_subquery(i).table_alias() != null) {
                        newTable = selectData.getFromStatementContext().join_clause().table_or_subquery(i).table_alias().getText();
                    }
                    BashSqlParser.Join_operatorContext joinOperator = selectData.getFromStatementContext().join_clause()
                            .join_operator(i);
                    JoinType jointype = FromAndJoinTranslater.getJoinType(joinOperator);
                    if (jointype == JoinType.INNER) {
                        joinedTables.add(newTable.toLowerCase());
                    } else if (jointype == JoinType.RIGHT || jointype == JoinType.OUTER) {
                        joinedTables.clear();
                    }
                }

                SimpleWhereClauses simpleWhereClauses = new SimpleWhereClauses(tables);
                for (String joinedTableName : joinedTables) {
                    List<BashSqlParser.ExprContext> expressions = simpleWhereClauses.getSingleTableExpressions(
                            selectData.getWhereExpr(), joinedTableName);
                    if (expressions.size() > 0) {
                        BashSqlTable table = tables.get(joinedTableName);
                        WhereTranslater translater = new WhereTranslater(table);
                        BashPipe p = new BashPipe(table.getInput(),
                                new BashCommand(translater.translateWhereExpression(expressions)));
                        table.setInput(p);
                    }
                }
            }
        }

        // JOIN
        FromAndJoinTranslater fromAndJoinTranslater = new FromAndJoinTranslater(tables);
        BashSqlTable joinedTable = fromAndJoinTranslater.createJoinExpression(selectData.getFromStatementContext());

        // WHERE
        if (selectData.getWhereExpr() != null) {
            WhereTranslater whereTranslater = new WhereTranslater(joinedTable);
            BashPipe p = new BashPipe(joinedTable.getInput(),
                    new BashCommand(whereTranslater.translateWhereExpression(selectData.getWhereExpr())));
            joinedTable.setInput(p);
        }

        AddExpressionsTranslater addExpressionsTranslater = new AddExpressionsTranslater(joinedTable);
        joinedTable = addExpressionsTranslater.addExpressionsTranslator(selectData.getGroupByExpr());

        // GROUP BY + basic functions in return columns

        String groupByOutput;
        if (useSortAggregation) {
            groupByOutput = new SortedGroupBy2AwkParser().parseGroupByStmt(selectData, joinedTable);
        } else {
            groupByOutput = new HashedGroupBy2AwkParser().parseGroupByStmt(selectData, joinedTable);
        }

        if (groupByOutput != null) {
            BashPipe p = new BashPipe(joinedTable.getInput(), new BashCommand(groupByOutput));
            joinedTable.setInput(p);
        }

        // HAVING
        if (selectData.getHavingExpr() != null) {
            WhereTranslater havingTranslater = new WhereTranslater(joinedTable);
            BashPipe p = new BashPipe(joinedTable.getInput(),
                    new BashCommand(havingTranslater.translateWhereExpression(selectData.getHavingExpr())));
            joinedTable.setInput(p);
        }

        if (selectData.isDistinctQuery()) {
            //Distinct keyword is present
            throw new BigBashException("DISTINCT keyword not supported at the moment",
                    EditPosition.fromContext(selectData.getSelectStmt()));

            //First assemble the returned columns
//            ReturnColumnsTranslater returnColumnsTranslater = new ReturnColumnsTranslater(joinedTable);
//            BashPipe p = new BashPipe(joinedTable.getInput(),
//                    new BashCommand(returnColumnsTranslater.translateReturnColumns(selectData.getReturnColumnsExpr())));
//            joinedTable.setInput(p);
//
//            DistinctTranslater distinctTranslater = new DistinctTranslater();
//            BashPipe p2 = new BashPipe(joinedTable.getInput(), new BashCommand(distinctTranslater.createDistinctOutput()));
//            joinedTable.setInput(p2);
        }

        // ORDER BY
        if (selectData.getOrderByContext() != null) {
            addExpressionsTranslater = new AddExpressionsTranslater(joinedTable);

            List<BashSqlParser.ExprContext> l = Lists.newArrayList();
            for (BashSqlParser.Ordering_termContext orderingterm : selectData.getOrderByContext().ordering_term()) {
                l.add(orderingterm.expr());
            }
            joinedTable = addExpressionsTranslater.addExpressionsTranslator(l);

            OrderByTranslater orderByTranslater = new OrderByTranslater(joinedTable);
            BashPipe p = new BashPipe(joinedTable.getInput(),
                    new BashCommand(orderByTranslater.createOrderByOutput(selectData.getOrderByContext())));
            joinedTable.setInput(p);
        }

        // LIMIT
        if (selectData.getLimit() != null) {
            LimitTranslater translater = new LimitTranslater(joinedTable);
            BashPipe p = new BashPipe(joinedTable.getInput(),
                    new BashCommand(translater.createLimitOutput(selectData.getLimit(), selectData.getOffset())));
            joinedTable.setInput(p);
        }

        // Return selected columns
        ReturnColumnsTranslater returnColumnsTranslater = new ReturnColumnsTranslater(joinedTable);
        BashPipe p = new BashPipe(joinedTable.getInput(),
                new BashCommand(returnColumnsTranslater.translateReturnColumns(selectData.getReturnColumnsExpr())));
        joinedTable.setInput(p);

        return joinedTable.getInput().render();
    }

    private Map<String, BashSqlTable> removeUnusedTables(SelectStmtData selectData) {
        final Map<String, BashSqlTable> usedTables = Maps.newHashMap();

        BashSqlListener functionStatementCollector = new BashSqlBaseListener() {
            @Override
            public void enterTable_or_subquery(@NotNull BashSqlParser.Table_or_subqueryContext ctx) {
                String tableName = ctx.table_name().getText();
                if (!tables.containsKey(tableName.toLowerCase())) {
                    throw new BigBashException("Table '" + tableName + "' not defined!",
                            EditPosition.fromContext(ctx));
                } else if (tables.get(tableName.toLowerCase()).getInput() == null) {
                    throw new BigBashException("Table '" + tableName + "' has no valid mapping!",
                            EditPosition.fromContext(ctx));
                }
                BashSqlTable table = tables.get(tableName.toLowerCase());
                if (ctx.table_alias() != null) {
                    usedTables.put(ctx.table_alias().getText().toLowerCase(),
                            table.createAlias(ctx.table_alias().getText().toLowerCase()));
                } else {
                    usedTables.put(tableName.toLowerCase(), table);
                }
            }
        };

        ParseTreeWalker walker = new ParseTreeWalker();

        // Collect all used columns
        walker.walk(functionStatementCollector, selectData.getSelectStmt());
        return usedTables;
    }

    private void prepareTables(final SelectStmtData selectData) {
        final Multimap<BashSqlTable, BashSqlTable.ColumnInformation> columns = HashMultimap.create();
        BashSqlListener functionStatementCollector = new BashSqlBaseListener() {
            @Override
            public void enterColumn_name_def(@NotNull final BashSqlParser.Column_name_defContext ctx) {
                for (BashSqlTable table : tables.values()) {
                    Optional<BashSqlTable.ColumnInformation> info = table.getColumnInformation(ctx);
                    if (info.isPresent()) {
                        columns.put(table, info.get());
                    }
                }
            }

            @Override
            public void enterResult_column_tableStar(@NotNull BashSqlParser.Result_column_tableStarContext ctx) {
                BashSqlTable table = tables.get(ctx.table_name().getText().toLowerCase());
                if (table != null) {
                    columns.putAll(table, table.getColumns().values());
                }
            }
        };

        ParseTreeWalker walker = new ParseTreeWalker();

        // Collect all used columns
        walker.walk(functionStatementCollector, selectData.getSelectStmt());

        for (BashSqlParser.Result_columnContext expr : selectData.getReturnColumnsExpr()) {
            if (expr instanceof BashSqlParser.Result_columnContext) {
                if (expr instanceof BashSqlParser.Result_column_starContext) {

                    // Do not return when '*' is selected
                    return;
                } else if (expr instanceof BashSqlParser.Result_column_tableStarContext) {

                    // Remove table if tablename.* is selected
                    columns.removeAll(((BashSqlParser.Result_column_tableStarContext) expr).table_name().getText()
                            .toLowerCase());
                }
            }
        }

        TableStripper stripper = new TableStripper();
        for (BashSqlTable table : columns.keySet()) {
            Preconditions.checkNotNull(tables.get(table.getTableName()));

            BashSqlTable strippedDownTable = stripper.stripTable(table, columns.get(table));
            tables.put(strippedDownTable.getTableName(), strippedDownTable);
        }
    }
}
