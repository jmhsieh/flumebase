// (c) Copyright 2010 Odiago, Inc.

package com.odiago.rtengine.parser;

import java.util.ArrayList;
import java.util.List;

import org.apache.avro.Schema;

import com.odiago.rtengine.exec.AliasSymbol;
import com.odiago.rtengine.exec.AssignedSymbol;
import com.odiago.rtengine.exec.HashSymbolTable;
import com.odiago.rtengine.exec.StreamSymbol;
import com.odiago.rtengine.exec.Symbol;
import com.odiago.rtengine.exec.SymbolTable;

import com.odiago.rtengine.plan.NamedSourceNode;
import com.odiago.rtengine.plan.PlanContext;
import com.odiago.rtengine.plan.PlanNode;

import com.odiago.rtengine.util.Ref;

/**
 * Specify a source for the FROM clause of a SELECT statement that
 * references the literal name of a stream.
 *
 * A LiteralSource is not an executable SQLStatement, but it shares
 * the common hierarchy.
 */
public class LiteralSource extends SQLStatement {
  /** The actual name of the source stream. */
  private String mSourceName;

  /** A user-specified alias to identify fields of this stream in expressions. */
  private String mAlias;

  /** SymbolTable containing all the fields of this source with their assigned
   * labels.*/
  private SymbolTable mSymbols;

  public LiteralSource(String name) {
    mSourceName = name;
  }

  public void setAlias(String alias) {
    mAlias = alias;
  }

  @Override
  public void format(StringBuilder sb, int depth) {
    pad(sb, depth);
    sb.append("Literal source: name=");
    sb.append(mSourceName);
    if (null != mAlias) {
      sb.append(", alias=");
      sb.append(mAlias);
    }
    sb.append("\n");
  }


  /**
   * Returns the actual name of the source object.
   */
  public String getName() {
    return mSourceName;
  }

  /**
   * Returns the user-specified alias for this object.
   */
  public String getAlias() {
    return mAlias;
  }


  /**
   * Given an input symbol table that defines this source, return a
   * SymbolTable that also includes the fields of this source. Memoizes the
   * created symbol table for later.
   *
   * <p>Modifies nextFieldId to contain the next id after applying ids to all
   * fields of this stream.</p>
   */
  public SymbolTable getFieldsSymbolTable(SymbolTable inTable, Ref<Integer> nextFieldId) {
    int nextId = nextFieldId.item.intValue();
    SymbolTable outTable = new HashSymbolTable(inTable);

    // Guaranteed non-null by our typechecker.
    StreamSymbol streamSym = (StreamSymbol) inTable.resolve(getName()).resolveAliases();

    String streamAlias = getAlias();
    if (null == streamAlias) {
      streamAlias = getName();
    }

    for (TypedField field : streamSym.getFields()) {
      String fieldName = field.getUserAlias();

      // This field is available as 'streamName.fieldName'.
      String fullName = streamAlias + "." + fieldName;
      Symbol sym = new AssignedSymbol(fullName, field.getType(), "__f_" + nextId + "_");
      nextId++;
      outTable.addSymbol(sym);

      // And also as an alias of just the fieldName.
      outTable.addSymbol(new AliasSymbol(fieldName, sym));
    }

    nextFieldId.item = Integer.valueOf(nextId);
    mSymbols = outTable;
    return outTable;
  }


  @Override
  public PlanContext createExecPlan(PlanContext planContext) {
    // The execution plan for a literal source is to just open the resouce
    // specified by this abstract source, by looking up its parameters in
    // the symbol table at plan resolution time.

    // The output PlanContext contains a new symbol table defining the fields
    // of this source.

    PlanContext outContext = new PlanContext(planContext);
    SymbolTable inTable = planContext.getSymbolTable();
    SymbolTable outTable = mSymbols;
    outContext.setSymbolTable(outTable);

    // streamSym is guaranteed to be a non-null StreamSymbol by the typechecker.
    StreamSymbol streamSym = (StreamSymbol) inTable.resolve(mSourceName).resolveAliases();
    List<TypedField> fields = streamSym.getFields();
    List<String> fieldNames = new ArrayList<String>();
    for (TypedField field : fields) {
      String fieldName = field.getAvroName();
      if (!fieldNames.contains(fieldName)) {
        fieldNames.add(fieldName);
      }
    }

    // Create an Avro output schema for this node, specifying all the fields
    // we can emit.  Use our internal symbol (mSymbols a.k.a. outTable) to
    // create more precise TypedFields that use the proper avro names.
    List<TypedField> outFields = new ArrayList<TypedField>();
    for (String fieldName : fieldNames) {
      AssignedSymbol sym = (AssignedSymbol) outTable.resolve(fieldName).resolveAliases();
      outFields.add(new TypedField(fieldName, sym.getType(), sym.getAssignedName(), fieldName));
    }

    PlanNode node = new NamedSourceNode(mSourceName, outFields);
    planContext.getFlowSpec().addRoot(node);
    Schema outSchema = createFieldSchema(outFields);
    outContext.setSchema(outSchema);
    outContext.setOutFields(outFields);
    node.setAttr(PlanNode.OUTPUT_SCHEMA_ATTR, outSchema);

    return outContext;
  }
}

