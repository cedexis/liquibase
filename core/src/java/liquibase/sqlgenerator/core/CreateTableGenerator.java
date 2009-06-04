package liquibase.sqlgenerator.core;

import liquibase.database.*;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.statement.CreateTableStatement;
import liquibase.statement.ForeignKeyConstraint;
import liquibase.statement.UniqueConstraint;
import liquibase.util.StringUtils;
import liquibase.util.log.LogFactory;
import liquibase.sqlgenerator.SqlGenerator;
import liquibase.sqlgenerator.SqlGeneratorChain;

import java.util.Iterator;
import java.util.logging.Level;

public class CreateTableGenerator implements SqlGenerator<CreateTableStatement> {
    public int getPriority() {
        return PRIORITY_DEFAULT;
    }

    public boolean supports(CreateTableStatement statement, Database database) {
        return true;
    }

    public ValidationErrors validate(CreateTableStatement createTableStatement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        ValidationErrors validationErrors = new ValidationErrors();
        validationErrors.checkRequiredField("tableName", createTableStatement.getTableName());
        validationErrors.checkRequiredField("columns", createTableStatement.getColumns());
        return validationErrors;
    }

    public Sql[] generateSql(CreateTableStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {
            StringBuffer buffer = new StringBuffer();
        buffer.append("CREATE TABLE ").append(database.escapeTableName(statement.getSchemaName(), statement.getTableName())).append(" ");
        buffer.append("(");
        Iterator<String> columnIterator = statement.getColumns().iterator();
        while (columnIterator.hasNext()) {
            String column = columnIterator.next();
            boolean isAutoIncrement = statement.getAutoIncrementColumns().contains(column);

            buffer.append(database.escapeColumnName(statement.getSchemaName(), statement.getTableName(), column));
            buffer.append(" ").append(database.getColumnType(statement.getColumnTypes().get(column), isAutoIncrement));

            if ((database instanceof SQLiteDatabase) &&
					(statement.getPrimaryKeyConstraint()!=null) &&
					(statement.getPrimaryKeyConstraint().getColumns().size()==1) &&
					(statement.getPrimaryKeyConstraint().getColumns().contains(column)) &&
					isAutoIncrement) {
            	String pkName = StringUtils.trimToNull(statement.getPrimaryKeyConstraint().getConstraintName());
	            if (pkName == null) {
	                pkName = database.generatePrimaryKeyName(statement.getTableName());
	            }
	            buffer.append(" CONSTRAINT ");
	            buffer.append(database.escapeConstraintName(pkName));
				buffer.append(" PRIMARY KEY AUTOINCREMENT");
			}

            if (statement.getDefaultValue(column) != null) {
                if (database instanceof MSSQLDatabase) {
                    buffer.append(" CONSTRAINT ").append(((MSSQLDatabase) database).generateDefaultConstraintName(statement.getTableName(), column));
                }
                buffer.append(" DEFAULT ");
                buffer.append(statement.getDefaultValue(column));
            }

            if (isAutoIncrement &&
					(database.getAutoIncrementClause()!=null) &&
					(!database.getAutoIncrementClause().equals(""))) {
                if (database.supportsAutoIncrement()) {
                    buffer.append(" ").append(database.getAutoIncrementClause()).append(" ");
                } else {
                    LogFactory.getLogger().log(Level.WARNING, database.getProductName()+" does not support autoincrement columns as request for "+(database.escapeTableName(statement.getSchemaName(), statement.getTableName())));
                }
            }

            if (statement.getNotNullColumns().contains(column)) {
                buffer.append(" NOT NULL");
            } else {
                if (database instanceof SybaseDatabase || database instanceof SybaseASADatabase) {
                    buffer.append(" NULL");
                }
            }

            if ((database instanceof InformixDatabase) &&
					(statement.getPrimaryKeyConstraint()!=null) &&
					(statement.getPrimaryKeyConstraint().getColumns().size()==1) &&
					(statement.getPrimaryKeyConstraint().getColumns().contains(column))) {
            	buffer.append(" PRIMARY KEY");
            }

            if (columnIterator.hasNext()) {
                buffer.append(", ");
            }
        }

        buffer.append(",");

        // TODO informixdb
        if (!( (database instanceof SQLiteDatabase) &&
				(statement.getPrimaryKeyConstraint()!=null) &&
				(statement.getPrimaryKeyConstraint().getColumns().size()==1) &&
				statement.getAutoIncrementColumns().contains(statement.getPrimaryKeyConstraint().getColumns().get(0)) ) &&

				!((database instanceof InformixDatabase) &&
				(statement.getPrimaryKeyConstraint()!=null) &&
				(statement.getPrimaryKeyConstraint().getColumns().size()==1)
				)) {
        	// ...skip this code block for sqlite if a single column primary key
        	// with an autoincrement constraint exists.
        	// This constraint is added after the column type.

	        if (statement.getPrimaryKeyConstraint() != null && statement.getPrimaryKeyConstraint().getColumns().size() > 0) {
	        	if (!(database instanceof InformixDatabase)) {
		            String pkName = StringUtils.trimToNull(statement.getPrimaryKeyConstraint().getConstraintName());
		            if (pkName == null) {
		                pkName = database.generatePrimaryKeyName(statement.getTableName());
		            }
		            buffer.append(" CONSTRAINT ");
		            buffer.append(database.escapeConstraintName(pkName));
	        	}
	            buffer.append(" PRIMARY KEY (");
	            buffer.append(database.escapeColumnNameList(StringUtils.join(statement.getPrimaryKeyConstraint().getColumns(), ", ")));
	            buffer.append(")");
	            buffer.append(",");
	        }
        }

        for (ForeignKeyConstraint fkConstraint : statement.getForeignKeyConstraints()) {
        	if (!(database instanceof InformixDatabase)) {
        		buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(fkConstraint.getForeignKeyName()));
        	}
            buffer.append(" FOREIGN KEY (")
                    .append(database.escapeColumnName(statement.getSchemaName(), statement.getTableName(), fkConstraint.getColumn()))
                    .append(") REFERENCES ")
                    .append(fkConstraint.getReferences());

            if (fkConstraint.isDeleteCascade()) {
                buffer.append(" ON DELETE CASCADE");
            }

            if ((database instanceof InformixDatabase)) {
            	buffer.append(" CONSTRAINT ");
            	buffer.append(database.escapeConstraintName(fkConstraint.getForeignKeyName()));
            }

            if (fkConstraint.isInitiallyDeferred()) {
                buffer.append(" INITIALLY DEFERRED");
            }
            if (fkConstraint.isDeferrable()) {
                buffer.append(" DEFERRABLE");
            }
            buffer.append(",");
        }

        for (UniqueConstraint uniqueConstraint : statement.getUniqueConstraints()) {
            if (uniqueConstraint.getConstraintName() != null && !constraintNameAfterUnique(database)) {
                buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(uniqueConstraint.getConstraintName()));
            }
            buffer.append(" UNIQUE (");
            buffer.append(database.escapeColumnNameList(StringUtils.join(uniqueConstraint.getColumns(), ", ")));
            buffer.append(")");
            if (uniqueConstraint.getConstraintName() != null && constraintNameAfterUnique(database)) {
                buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(uniqueConstraint.getConstraintName()));
            }
            buffer.append(",");
        }

//        if (constraints != null && constraints.getCheck() != null) {
//            buffer.append(constraints.getCheck()).append(" ");
//        }
//    }

        String sql = buffer.toString().replaceFirst(",\\s*$", "") + ")";

//        if (StringUtils.trimToNull(tablespace) != null && database.supportsTablespaces()) {
//            if (database instanceof MSSQLDatabase) {
//                buffer.append(" ON ").append(tablespace);
//            } else if (database instanceof DB2Database) {
//                buffer.append(" IN ").append(tablespace);
//            } else {
//                buffer.append(" TABLESPACE ").append(tablespace);
//            }
//        }

        if (statement.getTablespace() != null && database.supportsTablespaces()) {
            if (database instanceof MSSQLDatabase || database instanceof SybaseASADatabase) {
                sql += " ON " + statement.getTablespace();
            } else if (database instanceof DB2Database || database instanceof InformixDatabase) {
                sql += " IN " + statement.getTablespace();
            } else {
                sql += " TABLESPACE " + statement.getTablespace();
            }
        }

        return new Sql[] {
                new UnparsedSql(sql)
        };
    }

    private boolean constraintNameAfterUnique(Database database) {
		return database instanceof InformixDatabase;
	}

}