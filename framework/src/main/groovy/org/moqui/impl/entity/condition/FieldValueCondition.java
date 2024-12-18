/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.entity.condition;

import org.apache.commons.lang3.StringUtils;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityException;
import org.moqui.impl.entity.*;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;

import org.moqui.util.CollectionUtilities;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

public class FieldValueCondition implements EntityConditionImplBase, Externalizable {
    protected final static Logger logger = LoggerFactory.getLogger(FieldValueCondition.class);
    private static final Class thisClass = FieldValueCondition.class;

    protected ConditionField field;
    protected ComparisonOperator operator;
    protected Object value;
    protected boolean ignoreCase = false;
    private int curHashCode;

    // actual SQL code for the condition
    // this one shall be used to replace the SQL with a fixed value
    protected String sqlAppended;

    public FieldValueCondition() { }
    public FieldValueCondition(ConditionField field, ComparisonOperator operator, Object value) {
        this.field = field;
        this.value = value;

        // default to EQUALS
        ComparisonOperator tempOp = operator != null ? operator : EQUALS;
        // if EQUALS and we have a Collection value the IN operator is implied, similar with NOT_EQUAL
        if (value instanceof Collection) {
            if (tempOp == EQUALS) tempOp = IN;
            else if (tempOp == NOT_EQUAL) tempOp = NOT_IN;
        }
        this.operator = tempOp;

        curHashCode = createHashCode();
    }

    public ComparisonOperator getOperator() { return operator; }
    public String getFieldName() { return field.fieldName; }
    public Object getValue() { return value; }
    public boolean getIgnoreCase() { return ignoreCase; }

    public String getSqlAppended() {return sqlAppended;}

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
        StringBuilder sql = eqb.sqlTopLevel;
        String sqlAtStart = eqb.sqlTopLevel.toString();
        boolean valueDone = false;
        EntityDefinition curEd = subMemberEd != null ? subMemberEd : eqb.getMainEd();
        FieldInfo fi = field.getFieldInfo(curEd);
        if (fi == null) throw new EntityException("Could not find field " + field.fieldName + " in entity " + curEd.getFullEntityName());

        if (value instanceof Collection && ((Collection) value).isEmpty()) {
            if (operator == IN) {
                sql.append(" 1 = 2 ");
                valueDone = true;
            } else if (operator == NOT_IN) {
                sql.append(" 1 = 1 ");
                valueDone = true;
            }
        } else {
            if (ignoreCase && fi.typeValue == 1) sql.append("UPPER(");
            sql.append(field.getColumnName(curEd));
            if (ignoreCase && fi.typeValue == 1) sql.append(')');
            sql.append(' ');

            if (value == null) {
                if (operator == EQUALS || operator == LIKE || operator == IN || operator == BETWEEN) {
                    sql.append(" IS NULL");
                    valueDone = true;
                } else if (operator == NOT_EQUAL || operator == NOT_LIKE || operator == NOT_IN || operator == NOT_BETWEEN) {
                    sql.append(" IS NOT NULL");
                    valueDone = true;
                }
            }
        }
        if (operator == IS_NULL || operator == IS_NOT_NULL) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator));
            valueDone = true;
        }
        if (!valueDone) {
            String operatorStr = eqb.efi.jsonFieldManipulator.findComparisonOperator(
                    operator, fi, curEd.groupName, EntityConditionFactoryImpl.getComparisonOperatorString(operator)
            );
            sql.append(operatorStr);
            // for IN/BETWEEN change string to collection
            if (operator == IN || operator == NOT_IN || operator == BETWEEN || operator == NOT_BETWEEN)
                // @todo - CHECK IF IS CORRECT TO USE
                // value = valueToCollection(value);
                if (value instanceof CharSequence) {
                    String valueStr = value.toString();
                    if (valueStr.contains(",")) value = Arrays.asList(valueStr.split(","));
                }
            if (operator == IN || operator == NOT_IN) {
                if (value instanceof Collection) {
                    sql.append(" (");
                    boolean isFirst = true;
                    for (Object curValue : (Collection) value) {
                        if (isFirst) isFirst = false; else sql.append(", ");
                        sql.append("?");
                        if (ignoreCase && (curValue instanceof CharSequence)) curValue = curValue.toString().toUpperCase();
                        eqb.parameters.add(new EntityConditionParameter(fi, curValue, eqb));
                    }
                    sql.append(')');
                } else {
                    if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                    sql.append(" (?)");
                    eqb.parameters.add(new EntityConditionParameter(fi, value, eqb));
                }
            } else if ((operator == BETWEEN || operator == NOT_BETWEEN) && value instanceof Collection &&
                    ((Collection) value).size() == 2) {
                sql.append(" ? AND ?");
                Iterator iterator = ((Collection) value).iterator();
                Object value1 = iterator.next();
                if (ignoreCase && (value1 instanceof CharSequence)) value1 = value1.toString().toUpperCase();
                Object value2 = iterator.next();
                if (ignoreCase && (value2 instanceof CharSequence)) value2 = value2.toString().toUpperCase();
                eqb.parameters.add(new EntityConditionParameter(fi, value1, eqb));
                eqb.parameters.add(new EntityConditionParameter(fi, value2, eqb));
            } else {
                if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                // tweaking JSON-related conditions
                String fc = eqb.efi.jsonFieldManipulator.fieldCondition(fi, curEd.groupName, "where", " ?");
                sql.append(fc);
                eqb.parameters.add(new EntityConditionParameter(fi, value, eqb));
            }
        }

        this.sqlAppended = StringUtils.difference(sqlAtStart, sql.toString());
    }
    Object valueToCollection(Object value) {
        if (value instanceof CharSequence) {
            String valueStr = value.toString();
            // note: used to do this, now always put in List: if (valueStr.contains(","))
            value = Arrays.asList(valueStr.split(","));
        }
        // TODO: any other useful types to convert?
        return value;
    }
    @Override
    public void makeSearchFilter(List<Map<String, Object>> filterList) {
        boolean isNot = false;
        switch (operator) {
            case NOT_EQUAL:
                isNot = true;
            case EQUALS:
                Map<String, Object> termMap = CollectionUtilities.toHashMap("term",
                    CollectionUtilities.toHashMap(field.fieldName,
                        CollectionUtilities.toHashMap("value", value, "case_insensitive", ignoreCase)));
                if (isNot) {
                    filterList.add(CollectionUtilities.toHashMap("bool",
                            CollectionUtilities.toHashMap("must_not", termMap)));
                } else {
                    filterList.add(termMap);
                }
                break;
            case NOT_IN:
                isNot = true;
            case IN:
                value = valueToCollection(value);
                Map<String, Object> termsMap = CollectionUtilities.toHashMap("terms",
                    CollectionUtilities.toHashMap(field.fieldName, value));
                if (isNot) {
                    filterList.add(CollectionUtilities.toHashMap("bool",
                            CollectionUtilities.toHashMap("must_not", termsMap)));
                } else {
                    filterList.add(termsMap);
                }
                break;
            case NOT_LIKE:
                isNot = true;
            case LIKE:
                // this won't be quite the same as SQL, but close:
                // - % => * same, zero to many of any char
                // - _ => ? not same, _ is one of any char while ? is zero to one of any char
                if (value instanceof CharSequence) {
                    String valueStr = value.toString();
                    valueStr = valueStr.replaceAll("%", "*");
                    valueStr = valueStr.replaceAll("_", "?");
                    value = valueStr;
                }
                Map<String, Object> wildcardMap = CollectionUtilities.toHashMap("wildcard",
                    CollectionUtilities.toHashMap(field.fieldName,
                        CollectionUtilities.toHashMap("value", value)));
                if (isNot) {
                    filterList.add(CollectionUtilities.toHashMap("bool",
                            CollectionUtilities.toHashMap("must_not", wildcardMap)));
                } else {
                    filterList.add(wildcardMap);
                }
                break;
            case NOT_BETWEEN:
                isNot = true;
            case BETWEEN:
                value = valueToCollection(value);
                if (value instanceof Collection && ((Collection) value).size() == 2) {
                    Iterator iterator = ((Collection) value).iterator();
                    Object value1 = iterator.next();
                    Object value2 = iterator.next();

                    Map<String, Object> rangeMap = CollectionUtilities.toHashMap("range",
                        CollectionUtilities.toHashMap(field.fieldName,
                            CollectionUtilities.toHashMap("gte", value1, "lte", value2)));
                    if (isNot) {
                        filterList.add(CollectionUtilities.toHashMap("bool",
                                CollectionUtilities.toHashMap("must_not", rangeMap)));
                    } else {
                        filterList.add(rangeMap);
                    }
                } else {
                    throw new IllegalArgumentException("BETWEEN requires a Collection type value with 2 entries");
                }
                break;
            case IS_NULL:
                filterList.add(CollectionUtilities.toHashMap("bool",
                    CollectionUtilities.toHashMap("must_not",
                        CollectionUtilities.toHashMap("exists",
                            CollectionUtilities.toHashMap("field", field.fieldName)))));
                break;
            case IS_NOT_NULL:
                filterList.add(CollectionUtilities.toHashMap("exists",
                    CollectionUtilities.toHashMap("field", field.fieldName)));
                break;
            case LESS_THAN:
            case LESS_THAN_EQUAL_TO:
            case GREATER_THAN:
            case GREATER_THAN_EQUAL_TO:
                filterList.add(CollectionUtilities.toHashMap("range",
                    CollectionUtilities.toHashMap(field.fieldName,
                        CollectionUtilities.toHashMap(getElasticOperator(), value))));
                break;
        }
    }
    String getElasticOperator() {
        switch (operator) {
            case LESS_THAN: return "lt";
            case LESS_THAN_EQUAL_TO: return "lte";
            case GREATER_THAN: return "gt";
            case GREATER_THAN_EQUAL_TO: return "gte";
            default: return null;
        }
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        return EntityConditionFactoryImpl.compareByOperator(map.get(field.fieldName), operator, value);
    }
    @Override
    public boolean mapMatchesAny(Map<String, Object> map) { return mapMatches(map); }
    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) { return !map.containsKey(field.fieldName); }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        if (operator != EQUALS || ignoreCase || field instanceof ConditionAlias) return false;
        map.put(field.fieldName, value);
        return true;
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field instanceof ConditionAlias) {
            entityAliasSet.add(((ConditionAlias) field).entityAlias);
        } else {
            fieldAliasSet.add(field.fieldName);
        }
    }
    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        // only called for view-entity
        FieldInfo fi = field.getFieldInfo(mainEd);
        MNode fieldMe = fi.directMemberEntityNode;
        if (entityAlias == null) {
            if (fieldMe != null) {
                String subSelectAttr = fieldMe.attribute("sub-select");
                if ("true".equals(subSelectAttr) || "non-lateral".equals(subSelectAttr)) return null;
            }
            return this;
        } else {
            if (fieldMe != null && entityAlias.equals(fieldMe.attribute("entity-alias"))) {
                if (fi.aliasFieldName != null && !fi.aliasFieldName.equals(field.fieldName)) {
                    FieldValueCondition newCond = new FieldValueCondition(new ConditionField(fi.aliasFieldName), operator, value);
                    if (ignoreCase) newCond.ignoreCase();
                    return newCond;
                }
                return this;
            }
            return null;
        }
    }

    @Override
    public EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode++; return this; }

    @Override
    public String toString() {
        return field.toString() + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " +
                (value != null ? value.toString() + " (" + value.getClass().getName() + ")" : "null");
    }

    @Override
    public int hashCode() { return curHashCode; }
    private int createHashCode() {
        return (field != null ? field.hashCode() : 0) + operator.hashCode() + (value != null ? value.hashCode() : 0) + (ignoreCase ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        FieldValueCondition that = (FieldValueCondition) o;
        if (!field.equals(that.field)) return false;
        if (value != null) {
            if (!value.equals(that.value)) return false;
        } else {
            if (that.value != null) return false;
        }
        return operator == that.operator && ignoreCase == that.ignoreCase;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        field.writeExternal(out);
        // NOTE: found that the serializer in Hazelcast is REALLY slow with writeUTF(), uses String.chatAt() in a for loop, crazy
        out.writeObject(operator.name().toCharArray());
        out.writeObject(value);
        out.writeBoolean(ignoreCase);
    }
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        field = new ConditionField();
        field.readExternal(in);
        operator = ComparisonOperator.valueOf(new String((char[]) in.readObject()));
        value = in.readObject();
        ignoreCase = in.readBoolean();
        curHashCode = createHashCode();
    }
}
