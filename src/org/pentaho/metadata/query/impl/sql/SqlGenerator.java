/*
 * Copyright 2009 Pentaho Corporation.  All rights reserved.
 * This software was developed by Pentaho Corporation and is provided under the terms
 * of the Mozilla Public License, Version 1.1, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to http://www.mozilla.org/MPL/MPL-1.1.txt. The Original Code is the Pentaho
 * BI Platform.  The Initial Developer is Pentaho Corporation.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */
package org.pentaho.metadata.query.impl.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.metadata.model.LogicalColumn;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.model.LogicalRelationship;
import org.pentaho.metadata.model.LogicalTable;
import org.pentaho.metadata.model.SqlPhysicalColumn;
import org.pentaho.metadata.model.SqlPhysicalTable;
import org.pentaho.metadata.model.concept.types.RelationshipType;
import org.pentaho.metadata.model.concept.types.TargetColumnType;
import org.pentaho.metadata.model.concept.types.TargetTableType;
import org.pentaho.metadata.query.model.CombinationType;
import org.pentaho.metadata.query.model.Constraint;
import org.pentaho.metadata.query.model.Order;
import org.pentaho.metadata.query.model.Query;
import org.pentaho.metadata.query.model.Selection;
import org.pentaho.metadata.query.model.Order.Type;
import org.pentaho.metadata.repository.IMetadataDomainRepository;
import org.pentaho.pms.core.exception.PentahoMetadataException;
import org.pentaho.pms.messages.Messages;
import org.pentaho.pms.mql.dialect.JoinType;
import org.pentaho.pms.mql.dialect.SQLDialectFactory;
import org.pentaho.pms.mql.dialect.SQLDialectInterface;
import org.pentaho.pms.mql.dialect.SQLQueryModel;
import org.pentaho.pms.mql.dialect.SQLQueryModel.OrderType;

/**
 * This class contains the SQL generation algorithm.
 * The primary entrance method into this class is 
 * generateSql()
 * 
 * @author Will Gorman (wgorman@pentaho.org)
 *
 */
public class SqlGenerator {
  
  private static final Log logger = LogFactory.getLog(SqlGenerator.class);
  
  /**
   * This private class is used to sort the business tables in terms of the number of neighbours they have. We use
   * this information to find the table best suited to provide the missing link between selected tables while doing
   * SQL generation.
   */
  protected class BusinessTableNeighbours implements Comparable<BusinessTableNeighbours> {
    public LogicalTable businessTable;

    public int nrNeighbours;

    public int compareTo(BusinessTableNeighbours obj) {
      if (nrNeighbours == obj.nrNeighbours) {
        return businessTable.getId().compareTo(obj.businessTable.getId());
      } else {
        return new Integer(nrNeighbours).compareTo(new Integer(obj.nrNeighbours));
      }
    }
  }
  
  /**
   * This method traverses the set of selections and renders those selections
   * to the SQL string buffer.  This method determines the SQL column aliases.
   * It also calls getBusinessColumnSQL() which renders each individual 
   * business column in three different ways.  Either as an MQL Formula, an
   * aggregate function, or as a standard SQL column.
   *
   * @param sql sql string buffer
   * @param model business model
   * @param databaseMeta database metadata
   * @param selections sql selections
   * @param disableDistinct if true, disable distinct rendering
   * @param group if true, disable distinct rendering
   * @param locale locale string
   * @param columnsMap map of column aliases to populate
   */
  protected void generateSelect(SQLQueryModel query, LogicalModel model, DatabaseMeta databaseMeta, List<Selection> selections, boolean disableDistinct, boolean group, String locale, Map<LogicalTable, String> tableAliases, Map<String, String> columnsMap) {
    query.setDistinct(!disableDistinct && !group);
    for (int i = 0; i < selections.size(); i++) {
      // in some database implementations, the "as" name has a finite length;
      // for instance, oracle cannot handle a name longer than 30 characters. 
      // So, we map a short name here to the longer id, and replace the id
      // later in the resultset metadata. 
      String alias = null;
      if(columnsMap != null){
        columnsMap.put("COL" + Integer.toString(i), selections.get(i).getLogicalColumn().getId()); //$NON-NLS-1$
        alias = databaseMeta.quoteField("COL" + Integer.toString(i)); //$NON-NLS-1$
      }else{
        alias = databaseMeta.quoteField(selections.get(i).getLogicalColumn().getId());
      }
      SqlAndTables sqlAndTables = getBusinessColumnSQL(model, selections.get(i), tableAliases, databaseMeta, locale);
      query.addSelection(sqlAndTables.getSql(), alias);
    }
  }
  
  /**
   * This method first traverses the set of included business tables 
   * and renders those tables to the SQL string buffer. Second, it traverses
   * the list of joins and renders those in the WHERE clause. Finally, it 
   * traverses the constraints and adds them to the where or having clauses.
   * 
   * @param query sql query model
   * @param usedBusinessTables used business tables in query
   * @param model the current business model
   * @param path the join path
   * @param conditions the where conditions 
   * @param databaseMeta database metadata
   * @param locale locale string
   */
  protected void generateFromAndWhere(
      SQLQueryModel query, List<LogicalTable> usedBusinessTables, LogicalModel model, 
      Path path, List<Constraint> conditions, Map<LogicalTable, String> tableAliases,
      Map<Constraint, SqlOpenFormula> constraintFormulaMap,
      DatabaseMeta databaseMeta, String locale) throws PentahoMetadataException {

    // FROM TABLES
    for (int i = 0; i < usedBusinessTables.size(); i++) {
      LogicalTable businessTable = usedBusinessTables.get(i);
      String schemaName = null;
      if (businessTable.getProperty(SqlPhysicalTable.TARGET_SCHEMA) != null) {
        schemaName = databaseMeta.quoteField((String)businessTable.getProperty(SqlPhysicalTable.TARGET_SCHEMA));
      }
      
      // this code allows subselects to drive the physical model.
      // TODO: make this key off a metadata flag vs. the 
      // beginning of the table name.
      
      String tableName = (String)businessTable.getProperty(SqlPhysicalTable.TARGET_TABLE);
      TargetTableType type = (TargetTableType)businessTable.getProperty(SqlPhysicalTable.TARGET_TABLE_TYPE);
      if (type == TargetTableType.INLINE_SQL) {
        tableName = "(" + tableName + ")"; 
      } else {
        tableName = databaseMeta.quoteField(tableName);
      }
      
      query.addTable(databaseMeta.getSchemaTableCombination(schemaName, tableName),
          databaseMeta.quoteField(tableAliases.get(businessTable)));
    }
    
    // JOIN CONDITIONS
    if (path != null) {
      for (int i = 0; i < path.size(); i++) {
        LogicalRelationship relation = path.getRelationship(i);
        String joinFormula = getJoin(model, relation, tableAliases, databaseMeta, locale);
        String joinOrderKey = relation.getJoinOrderKey();
        JoinType joinType;
        switch(RelationshipType.getJoinType(relation.getRelationshipType())) {
          case LEFT_OUTER : joinType = JoinType.LEFT_OUTER_JOIN; break;
          case RIGHT_OUTER : joinType = JoinType.RIGHT_OUTER_JOIN; break;
          case FULL_OUTER : joinType = JoinType.FULL_OUTER_JOIN; break;
          default: joinType = JoinType.INNER_JOIN; break;
        }
        
        String leftTableName = databaseMeta.getQuotedSchemaTableCombination(
                  (String)relation.getFromTable().getProperty(SqlPhysicalTable.TARGET_SCHEMA), 
                  (String)relation.getFromTable().getProperty(SqlPhysicalTable.TARGET_TABLE));
        String leftTableAlias = relation.getFromTable().getId();
        String rightTableName = databaseMeta.getQuotedSchemaTableCombination(
                  (String)relation.getToTable().getProperty(SqlPhysicalTable.TARGET_SCHEMA), 
                  (String)relation.getToTable().getProperty(SqlPhysicalTable.TARGET_TABLE));
        String rightTableAlias = relation.getToTable().getId();
        
        query.addJoin(leftTableName, leftTableAlias, rightTableName, rightTableAlias, joinType, joinFormula, joinOrderKey);
        // query.addWhereFormula(joinFormula, "AND"); //$NON-NLS-1$
      }
    }
    
    // WHERE CONDITIONS
    if (conditions != null) {
      boolean first = true;
      for (Constraint condition : conditions) {
        SqlOpenFormula formula = constraintFormulaMap.get(condition);
        
        // configure formula to use table aliases
        formula.setTableAliases(tableAliases);
        
        // The ones with aggregates in it are for the HAVING clause
        if (!formula.hasAggregate()) {
          
          String sqlFormula = formula.generateSQL(locale);
          String[] usedTables = formula.getLogicalTableIDs();
          query.addWhereFormula(sqlFormula, first ? "AND" : condition.getCombinationType().name(), usedTables); //$NON-NLS-1$
          first = false;
        } else {
          query.addHavingFormula(formula.generateSQL(locale), condition.getCombinationType().name());
        }
      }
    }
  }
  
  /**
   * this method adds the group by statements to the query model
   * 
   * @param query sql query model
   * @param model business model
   * @param selections list of selections
   * @param databaseMeta database info
   * @param locale locale string
   */
  protected void generateGroupBy(SQLQueryModel query, LogicalModel model, 
      List<Selection> selections, Map<LogicalTable, String> tableAliases, 
      DatabaseMeta databaseMeta, String locale) {
    // can be moved to selection loop
    for (Selection selection : selections) {
      // Check if the column has any nested aggregation in there like a calculated column : SUM(a)/SUM(b) with no aggregation set.
      //
      if (!hasFactsInIt(model, selection, databaseMeta, locale)) {
    	SqlAndTables sqlAndTables = getBusinessColumnSQL(model, selection, tableAliases, databaseMeta, locale);
        query.addGroupBy(sqlAndTables.getSql(), null);
      }
    }
  }
  
  /**
   * this method adds the order by statements to the query model
   * 
   * @param query sql query model
   * @param model business model
   * @param orderBy list of order bys
   * @param databaseMeta database info
   * @param locale locale string
   */
  protected void generateOrderBy(SQLQueryModel query, LogicalModel model, List<Order> orderBy,
      DatabaseMeta databaseMeta, String locale, Map<LogicalTable, String> tableAliases, 
      Map<String,String> columnsMap) {
    if (orderBy != null) {
      for (Order orderItem : orderBy) {
        LogicalColumn businessColumn = orderItem.getSelection().getLogicalColumn();
        String alias=null;
        if (columnsMap!=null) {
	        // The column map is a unique mapping of Column alias to the column ID
	        // Here we have the column ID and we need the alias.
	        // We need to do the order by on the alias, not the column name itself.
        	// For most databases, it can be both, but the alias is more standard.
        	//
        	// Using the column name and not the alias caused an issue on Apache Derby.
	        //
	        for (String key : columnsMap.keySet()) {
	        	String value = columnsMap.get(key);
	        	if (value.equals(businessColumn.getId())) {
	        		// Found it: the alias is the key
	        		alias = key;
	        		break;
	        	}
	        }
        }
        SqlAndTables sqlAndTables = getBusinessColumnSQL(model, orderItem.getSelection(), tableAliases, databaseMeta, locale);
        query.addOrderBy(sqlAndTables.getSql(), alias, orderItem.getType() != Type.ASC ? OrderType.DESCENDING : null); //$NON-NLS-1$
      }
    }
  }
  
  private static String genString(String base, int val) {
    if (val < 10) {
      return base + "0" + val;
    }
    return base + val;
  }
  
  /**
   * this method generates a unique alias name, limited to a specific length
   * 
   * @param alias The name of the original alias to use
   * @param maxLength the maximum length the alias can be
   * @param existingAliases existing aliases
   * 
   * @return
   */
  protected String generateUniqueAlias(String alias, int maxLength, Collection<String> existingAliases) {
    if (alias.length() <= maxLength) {
      if (!existingAliases.contains(alias)) {
        return alias;
      } else {
        if (alias.length() > maxLength - 2) {
          alias = alias.substring(0, maxLength - 2);
        }
      }
    } else {
      alias = alias.substring(0, maxLength - 2);
    }

    int id = 1;
    String aliasWithId = genString(alias, id);
    while (existingAliases.contains(aliasWithId)) {
      aliasWithId = genString(alias, ++id);
    }
    return aliasWithId;
  }
  
  public MappedQuery generateSql(
      Query query, String locale, IMetadataDomainRepository repo, 
      DatabaseMeta databaseMeta) throws PentahoMetadataException {
    
    Constraint securityConstraint = null;
    
    if (repo != null) {
      String mqlSecurityConstraint = repo.generateRowLevelSecurityConstraint(query.getLogicalModel());
      if (StringUtils.isNotBlank(mqlSecurityConstraint)) {
        securityConstraint = new Constraint(CombinationType.AND, mqlSecurityConstraint);
      }
    }

    return getSQL(query.getLogicalModel(), query.getSelections(), 
        query.getConstraints(), query.getOrders(), databaseMeta, 
        locale, query.getDisableDistinct(), securityConstraint);
  }
  
  /**
   * returns the generated SQL and additional metadata
   * 
   * @param selections The selected business columns
   * @param conditions the conditions to apply (null = no conditions)
   * @param orderBy the ordering (null = no order by clause)
   * @param databaseMeta the meta info which determines the SQL generated.
   * @param locale the locale
   * @param disableDistinct if true, disables default behavior of using DISTINCT when there
   * are no groupings.
   * @param securityConstraint if provided, applies a global security constraint to the query
   * 
   * @return a SQL query based on a column selection, conditions and a locale
   */
  protected MappedQuery getSQL(
      LogicalModel model, 
      List<Selection> selections, 
      List<Constraint> conditions, 
      List<Order> orderBy, 
      DatabaseMeta databaseMeta, 
      String locale, 
      boolean disableDistinct, 
      Constraint securityConstraint) throws PentahoMetadataException {
    
    SQLQueryModel query = new SQLQueryModel();
    
    Map<String,String> columnsMap = new HashMap<String,String>();
    
    // generate the formula objects for constraints 
    Map<Constraint, SqlOpenFormula> constraintFormulaMap = new HashMap<Constraint, SqlOpenFormula>();
    for (Constraint constraint : conditions) {
      SqlOpenFormula formula = new SqlOpenFormula(model, databaseMeta, constraint.getFormula(), null);
      formula.parseAndValidate();
      constraintFormulaMap.put(constraint, formula);
    }
    if (securityConstraint != null) {
      SqlOpenFormula formula = new SqlOpenFormula(model, databaseMeta, securityConstraint.getFormula(), null);
      formula.parseAndValidate();
      constraintFormulaMap.put(securityConstraint, formula);
    }
    
    // These are the tables involved in the field selection
    //
    List<LogicalTable> tabs = getTablesInvolved(model, selections, conditions, 
        orderBy, constraintFormulaMap, databaseMeta, locale, securityConstraint);

    // Now get the shortest path between these tables.
    Path path = getShortestPathBetween(model, tabs);
    if (path == null) {
      throw new PentahoMetadataException(Messages.getErrorString("BusinessModel.ERROR_0001_FAILED_TO_FIND_PATH")); //$NON-NLS-1$
    }

    List<LogicalTable> usedBusinessTables = path.getUsedTables();
    if (path.size() == 0) {
      // just a selection from 1 table: pick any column...
      if (selections.size() > 0) // Otherwise, why bother, right?
      {
        usedBusinessTables.add(selections.get(0).getLogicalColumn().getLogicalTable());
      }
    }

    if (usedBusinessTables.size() > 0) {

      // generate tableAliases mapping
      
      int maxAliasNameWidth = SQLDialectFactory.getSQLDialect(databaseMeta).getMaxTableNameLength();
      Map<LogicalTable, String> tableAliases = new HashMap<LogicalTable, String>();
      for (LogicalTable table : usedBusinessTables) {
        String uniqueAlias = generateUniqueAlias(table.getId(), maxAliasNameWidth, tableAliases.values());
        tableAliases.put(table, uniqueAlias);
      }
      
      boolean group = hasFactsInIt(model, selections, conditions, constraintFormulaMap, 
                                    databaseMeta, locale);

      generateSelect(query, model, databaseMeta, selections, disableDistinct, group, locale, tableAliases, columnsMap);
      generateFromAndWhere(query, usedBusinessTables, model, path, conditions, tableAliases, constraintFormulaMap, databaseMeta, locale);
      if (group) {
        generateGroupBy(query, model, selections, tableAliases, databaseMeta, locale);
      }
      generateOrderBy(query, model, orderBy, databaseMeta, locale, tableAliases, columnsMap);
      
      if (securityConstraint != null) {
        // apply current table aliases
        SqlOpenFormula securityFormula = constraintFormulaMap.get(securityConstraint);
        securityFormula.setTableAliases(tableAliases);
        
        // generate sql
        String sqlFormula = securityFormula.generateSQL(locale);
        query.setSecurityConstraint(sqlFormula, securityFormula.hasAggregate());
      }
    }

    SQLDialectInterface dialect = SQLDialectFactory.getSQLDialect(databaseMeta);
   
    return new MappedQuery(dialect.generateSelectStatement(query), columnsMap, selections);
  }

  protected List<LogicalTable> getTablesInvolved (
      LogicalModel model, 
      List<Selection> selections, 
      List<Constraint> conditions, 
      List<Order> orderBy,
      Map<Constraint, SqlOpenFormula> constraintFormulaMap,
      DatabaseMeta databaseMeta,
      String locale,
      Constraint securityConstraint) {
    Set<LogicalTable> treeSet = new TreeSet<LogicalTable>();

    // Figure out which tables are involved in the SELECT
    //
    for (Selection selection : selections) {
      // We need to figure out which tables are involved in the formula.
      // This could simply be the parent table, but it could also be another one too.
      // 
      // If we want to know all the tables involved in the query, we need to parse all the formula first
      // TODO: We re-use the static method below, maybe there is a better way to clean this up a bit.
      //
      
      SqlAndTables sqlAndTables = getBusinessColumnSQL(model, selection, null, databaseMeta, locale);
      
  	  // Add the involved tables to the list...
  	  //
      for (LogicalTable businessTable : sqlAndTables.getUsedTables()) {
    	  treeSet.add(businessTable);
      }
    }
    
    // Figure out which tables are involved in the WHERE
    //
    for(Constraint condition : conditions) {
      SqlOpenFormula formula = constraintFormulaMap.get(condition);
      List<Selection> cols = formula.getSelections();
      for (Selection selection : cols) {
        LogicalTable businessTable = selection.getLogicalColumn().getLogicalTable();
        treeSet.add(businessTable); //$NON-NLS-1$
      }
    }
    
    // Figure out which tables are involved in the ORDER BY
    //
    for(Order order : orderBy) {
    	SqlAndTables sqlAndTables = getBusinessColumnSQL(model, order.getSelection(), null, databaseMeta, locale);
    	
    	// Add the involved tables to the list...
    	//
        for (LogicalTable businessTable : sqlAndTables.getUsedTables()) {
      	  treeSet.add(businessTable);
        }
      }
    
    // find any tables listed in the security constraint

    if (securityConstraint != null) {
      SqlOpenFormula formula = constraintFormulaMap.get(securityConstraint);

      List<Selection> cols = formula.getSelections();
      for (Selection selection : cols) {
        treeSet.add(selection.getLogicalColumn().getLogicalTable());
      }
    }
    
    return new ArrayList<LogicalTable>(treeSet);
  }
  
  protected boolean hasFactsInIt(LogicalModel model, List<Selection> selections, 
      List<Constraint> conditions, Map<Constraint, SqlOpenFormula> constraintFormulaMap, 
      DatabaseMeta databaseMeta, String locale) {
	// We don't have to simply check the columns in the selection
	// If the column is made up of a calculation, we need to verify that there is no aggregation in the calculation too.
	//
	// For example, this is the case for the calculation of a ration: SUM(A) / SUM(B).
	// The resulting ratio will not have an aggregate set (none) but the used business columns (A and B) will have one set.
	// As such, we need to do this recursively.
	//
    for (Selection selection : selections) {
    
      if (hasFactsInIt(model, selection, databaseMeta, locale)) {
    	  return true;
      }
    }
    
    // Verify the conditions in the same way too
    //
    if (conditions != null) {
      for (Constraint condition : conditions) {
        List<Selection> list = constraintFormulaMap.get(condition).getSelections();
    	  for (Selection conditionColumn : list) {
    	      if (hasFactsInIt(model, conditionColumn, databaseMeta, locale)) {
    	    	  return true;
    	      }
    	  }
      }
    }
    return false;
  }
  
  /**
   * See if the business column specified has a fact in it.<br>
   * We verify the formula specified in the column to see if it contains calculations with any aggregated column.<br>
   * We even do this nested down through the used business columns in the formula.<br>
   * 
   * @param model the business model to reference
   * @param businessColumn the column to verify for facts
   * @param databaseMeta the database to reference
   * @param locale the locale to use
   * @return true if the business column uses any aggregation in the formula or is aggregated itself.
   */
  protected boolean hasFactsInIt(LogicalModel model, Selection businessColumn, DatabaseMeta databaseMeta, String locale) {
	  if (businessColumn.hasAggregate()) return true;

	  // Parse the formula in the business column to see which tables and columns are involved...
      //
      SqlAndTables sqlAndTables = getBusinessColumnSQL(model, businessColumn, null, databaseMeta, locale);
      for (Selection column : sqlAndTables.getUsedColumns()) {
	      if (column.hasAggregate()) {
	        return true;
	      }
      }
      
      // Nothing found
      //
      return false;

  }
    
  protected <T> List<List<T>> getSubsetsOfSize(int size, List<T> list) {
    if (size <= 0) return new ArrayList<List<T>>();
    return getSubsets(0, size, new ArrayList<T>(), list);
  }
  
  // recursive function to generate all subsets
  private static <T> List<List<T>> getSubsets(int indexToStart, int subSize, List<T> toClone, List<T> origList) {
    List<List<T>> allSubsets = new ArrayList<List<T>>();
    for (int i = indexToStart; i <= origList.size() - subSize; i++) {
      List<T> subset = new ArrayList<T>(toClone);
      subset.add(origList.get(i));
      if (subSize == 1) {
        allSubsets.add(subset);
      } else {
        allSubsets.addAll(getSubsets(i + 1, subSize - 1, subset, origList));
      }
    }
    return allSubsets;
  }
  
  /**
   * This method determines the shortest path between the list of included
   * tables within the MQL Query. The algorithm first determines if there is an
   * existing path between all selected tables.  If not, the algorithm 
   * continues to add new tables to the list until a path is discovered.  If 
   * more than one path is available with a certain number of tables, the 
   * algorithm uses the relative size values if specified to determine which 
   * path to traverse in the SQL Join.
   * 
   * @param model the business model
   * @param tables include tables
   * @return shortest path
   */
  protected Path getShortestPathBetween(LogicalModel model, List<LogicalTable> tables) {
    // We have the business tables.
    // Let's try to see if they are somehow connected first.
    // If they are not, we add a table that's not being used so far and add it to the equation.
    // We can continue like that until we connect all tables with joins.

    // This is a list of all the paths that we could find between all the tables...
    List<Path> paths = new ArrayList<Path>();

    // Here are the tables we need to link it all together.
    List<LogicalTable> origSelectedTables = new ArrayList<LogicalTable>(tables);
    boolean allUsed = (tables.size() == 0);
    // These are the tables that are not yet used
    List<LogicalTable> notSelectedTables = getNonSelectedTables(model, origSelectedTables);

    for (int ns = 0; ns <= notSelectedTables.size() && !allUsed; ns++) {
    
      // find unique combinations of notSelectedTables of size NS
      List<List<LogicalTable>> uniqueCombos = getSubsetsOfSize(ns, notSelectedTables);
      if (ns == 0) {
        uniqueCombos.add(new ArrayList<LogicalTable>());
      }
        
      // add all the selected tables to this list
      for (int i = 0; i < uniqueCombos.size(); i++) {
        List<LogicalTable> uc = uniqueCombos.get(i);
        uc.addAll(origSelectedTables);
      }
    
      for (int p = 0; p < uniqueCombos.size(); p++) {
      
        List selectedTables = (List)uniqueCombos.get(p);
        Path path = new Path();
        
        // Generate all combinations of the selected tables...
        for (int i = 0; i < selectedTables.size(); i++) {
          for (int j = i + 1; j < selectedTables.size(); j++) {
            LogicalTable one = (LogicalTable) selectedTables.get(i);
            LogicalTable two = (LogicalTable) selectedTables.get(j);

            // See if we have a relationship that goes from one to two...
            LogicalRelationship relationship = findRelationshipUsing(model, one, two);
            if (relationship != null && !path.contains(relationship)) {
              path.addRelationship(relationship);
            }
          }
  
          // We need to have (n-1) relationships for n tables, otherwise we will not connect everything.
          if (path.size() == selectedTables.size() - 1) {
            // This is a valid path, the first we find here is probably the shortest
            paths.add(path);
            // We can stop now.
            allUsed = true;
          }
        }
      }
    }

    // Now, off all the paths, look for the shortest number of relationships
    // If we have the same number of relationships, get the one with the lowest total relative size.

    int minSize = Integer.MAX_VALUE;
    int minScore = Integer.MAX_VALUE;
    Path minPath = null;
    for (int i = 0; i < paths.size(); i++) {
      Path path = (Path) paths.get(i);
      if (path.size() < minScore || (path.size() == minScore && path.score() < minSize))
        minPath = path;
    }

    return minPath;
  }

  protected List<LogicalTable> getNonSelectedTables(LogicalModel model, List<LogicalTable> selectedTables) {
    List<BusinessTableNeighbours> extra = new ArrayList<BusinessTableNeighbours>(model.getLogicalTables().size());
    List<LogicalTable> unused = new ArrayList<LogicalTable>();
    List<LogicalTable> used = new ArrayList<LogicalTable>(selectedTables);
    
    // the first part of this algorithm looks for all the tables that are connected to the selected 
    // tables in any way.  We loop through all the tables until there are no more connections
    
    for (int i = 0; i < model.getLogicalTables().size(); i++) {
      unused.add(model.getLogicalTables().get(i));
    }
    
    boolean anyFound = true;
    
    // iterate over the list until there are no more neighbors
    while (anyFound) {
      anyFound = false;
      Iterator<LogicalTable> iter = unused.iterator();
      while (iter.hasNext()) {
        boolean found = false;        
        LogicalTable check = iter.next(); // unused.get(i);
        for (int j = 0; j < used.size(); j++) {
          LogicalTable businessTable = used.get(j);
          if (check.equals(businessTable)) {
            found = true;
          }
        }
        if (!found) {
          BusinessTableNeighbours btn = new BusinessTableNeighbours();
          btn.businessTable = check;
          btn.nrNeighbours = getNrNeighbours(model, check, used);
          if (btn.nrNeighbours > 0) {
            extra.add(btn);
            used.add(check);
            // remove check from the unused list
            iter.remove();
            anyFound = true;
          }
        }
      }
    }

    // OK, we now have a number of tables, but we want to sort this list
    // The tables with the highest numbers of neighbours should be placed first. (descending)
    //
    Collections.sort(extra);

    List<LogicalTable> retval = new ArrayList<LogicalTable>(extra.size());
    for (int i = 0; i < extra.size(); i++) {
      BusinessTableNeighbours btn = extra.get(i);
      // If the number of neighbours is 0, there is no point in returning the table for the SQL generation
      // There is no way the table can connect to the selected tables anyway as there are no neighbours.
      //
      if (btn.nrNeighbours > 0) {
        retval.add(0, btn.businessTable);
      }
    }

    return retval;
  }
  
  /**
   * @param businessTable
   *          the table to calculate the number of neighbours for
   * @param selectedTables
   *          the list of selected business tables
   * @return The number of neighbours in a list of selected tables using the relationships defined in this business model
   */
  private static int getNrNeighbours(LogicalModel model, LogicalTable businessTable, List<LogicalTable> selectedTables) {
    int nr = 0;

    for (LogicalRelationship relationship : model.getLogicalRelationships()) {
      if (relationship.isUsingTable(businessTable)) {
        // See if one of the selected tables is also using this relationship.
        // If so, we have a neighbour in the selected tables.
        //
        boolean found = false;
        for (int s = 0; s < selectedTables.size() && !found; s++) {
          LogicalTable selectedTable = selectedTables.get(s);
          if (relationship.isUsingTable(selectedTable) && !businessTable.equals(selectedTable)) {
            nr++;
          }
        }
      }
    }
    return nr;
  }
  
//  private static List<LogicalRelationship> findRelationshipsUsing(LogicalModel model, LogicalTable table) {
//    List<LogicalRelationship> list = new ArrayList<LogicalRelationship>();
//    for (LogicalRelationship rel : model.getLogicalRelationships()) {
//      if (rel.isUsingTable(table)) {
//        list.add(rel);
//      }
//    }
//    return list;
//  }

  

  private static LogicalRelationship findRelationshipUsing(LogicalModel model, LogicalTable one, LogicalTable two) {
    for (LogicalRelationship rel : model.getLogicalRelationships()) {
      if (rel.isUsingTable(one) && rel.isUsingTable(two)) {
        return rel;
      }
    }
    return null;
  }

  public static SqlAndTables getBusinessColumnSQL(LogicalModel businessModel, Selection column, Map<LogicalTable, String> tableAliases, DatabaseMeta databaseMeta, String locale) {
    String targetColumn = (String)column.getLogicalColumn().getProperty(SqlPhysicalColumn.TARGET_COLUMN);
    LogicalTable logicalTable = column.getLogicalColumn().getLogicalTable();
      if (column.getLogicalColumn().getProperty(SqlPhysicalColumn.TARGET_COLUMN_TYPE) == TargetColumnType.OPEN_FORMULA) { 
        // convert to sql using libformula subsystem
        
        try {
          // we'll need to pass in some context to PMSFormula so it can resolve aliases if necessary
          SqlOpenFormula formula = new SqlOpenFormula(businessModel, logicalTable, databaseMeta, targetColumn, tableAliases);
          formula.parseAndValidate();
          
          String formulaSql = formula.generateSQL(locale);
          
          // check for old style, where function is hardcoded in the model.
          if (column.hasAggregate() && !hasAggregateDefinedAlready(formulaSql, databaseMeta)) {
            formulaSql = getFunctionExpression(column, formulaSql, databaseMeta);
          }
          
          return new SqlAndTables(formulaSql, formula.getLogicalTables(), formula.getSelections());
        } catch (PentahoMetadataException e) {
          // this is for backwards compatibility.
          // eventually throw any errors
          logger.error(Messages.getErrorString("BusinessColumn.ERROR_0001_FAILED_TO_PARSE_FORMULA", targetColumn), e); //$NON-NLS-1$

          // Report just this table and column as being used along with the formula.
          //
          return new SqlAndTables(targetColumn, logicalTable, column);
        }
      } else {
          String tableColumn = ""; //$NON-NLS-1$
          
          // this step is required because this method is called in two contexts.  The first
          // call determines all the tables involved, making it impossible to guarantee
          // unique aliases.
          
          String tableAlias = null;
          if (tableAliases != null) {
            tableAlias = tableAliases.get(logicalTable);
          } else {
            tableAlias = logicalTable.getId(); 
          }
          tableColumn += databaseMeta.quoteField( tableAlias );
          tableColumn += "."; //$NON-NLS-1$
          
          // TODO: WPG: instead of using formula, shouldn't we use the physical column's name?
          tableColumn += databaseMeta.quoteField( targetColumn );
          
          // For the having clause, for example: HAVING sum(turnover) > 100
          if (column.hasAggregate()) {
              return new SqlAndTables(getFunctionExpression(column, tableColumn, databaseMeta), logicalTable, column);
          } else {
              return new SqlAndTables(tableColumn, logicalTable, column);
          }
      }
  }

  // This method is for backwards compatibility of already defined
  // isExact formulas that may contain at the root an aggregate function.
  private static boolean hasAggregateDefinedAlready(String sql, DatabaseMeta databaseMeta) {
    String trimmed = sql.trim();
    return 
      trimmed.startsWith(databaseMeta.getFunctionAverage() + "(") ||
      trimmed.startsWith(databaseMeta.getFunctionCount() + "(") ||
      trimmed.startsWith(databaseMeta.getFunctionMaximum() + "(") ||
      trimmed.startsWith(databaseMeta.getFunctionMinimum()  + "(") ||
      trimmed.startsWith(databaseMeta.getFunctionSum() + "(");
  }
  
  public static String getFunctionExpression(Selection column, String tableColumn, DatabaseMeta databaseMeta) {
      String expression=getFunction(column, databaseMeta); //$NON-NLS-1$
      
      switch(column.getActiveAggregationType()) {
          case DISTINCT_COUNT : expression+="(DISTINCT "+tableColumn+")"; break;   //$NON-NLS-1$ //$NON-NLS-2$
          default: expression+="("+tableColumn+")"; break;  //$NON-NLS-1$ //$NON-NLS-2$
      }
      
      return expression;
  }
  
  private static String getFunction(Selection column, DatabaseMeta databaseMeta) {
      String fn=""; //$NON-NLS-1$
      
      switch(column.getActiveAggregationType()) {
          case AVG: fn=databaseMeta.getFunctionAverage(); break;
          case DISTINCT_COUNT:
          case COUNT: fn=databaseMeta.getFunctionCount(); break;
          case MAX: fn=databaseMeta.getFunctionMaximum(); break;
          case MIN: fn=databaseMeta.getFunctionMinimum(); break;
          case SUM: fn=databaseMeta.getFunctionSum(); break;
          default: break;
      }
      
      return fn;
  }

  protected String getJoin(LogicalModel businessModel, LogicalRelationship relation, Map<LogicalTable, String> tableAliases, DatabaseMeta databaseMeta, String locale) {
    String join=""; //$NON-NLS-1$
    if (relation.isComplex()) {
      try {
        // parse join as MQL
        SqlOpenFormula formula = new SqlOpenFormula(businessModel, databaseMeta, relation.getComplexJoin(), tableAliases);
        formula.parseAndValidate();
        join = formula.generateSQL(locale);
      } catch(PentahoMetadataException e) {
        // backward compatibility, deprecate
        logger.error(Messages.getErrorString("MQLQueryImpl.ERROR_0017_FAILED_TO_PARSE_COMPLEX_JOIN", relation.getComplexJoin()), e); //$NON-NLS-1$
        join = relation.getComplexJoin();
      }
    } else if (relation.getFromTable() != null && relation.getToTable() != null && 
               relation.getFromColumn() !=null && relation.getToColumn() != null) {
        // Left side
        String leftTableAlias = null;
        if (tableAliases != null) {
          leftTableAlias = tableAliases.get(relation.getFromColumn().getLogicalTable());  
        } else {
          leftTableAlias = relation.getFromColumn().getLogicalTable().getId();
        }
      
        join  = databaseMeta.quoteField(leftTableAlias);
        join += "."; //$NON-NLS-1$
        join += databaseMeta.quoteField((String)relation.getFromColumn().getProperty(SqlPhysicalColumn.TARGET_COLUMN));
        
        // Equals
        join += " = "; //$NON-NLS-1$
        
        // Right side
        String rightTableAlias = null;
        if (tableAliases != null) {
          rightTableAlias = tableAliases.get(relation.getToColumn().getLogicalTable());  
        } else {
          rightTableAlias = relation.getToColumn().getLogicalTable().getId();
        }
        
        join += databaseMeta.quoteField(rightTableAlias);
        join += "."; //$NON-NLS-1$
        join += databaseMeta.quoteField((String)relation.getToColumn().getProperty(SqlPhysicalColumn.TARGET_COLUMN));
    }
    
    return join;
  }
}