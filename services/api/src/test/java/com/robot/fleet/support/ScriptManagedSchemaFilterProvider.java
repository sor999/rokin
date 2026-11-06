package com.robot.fleet.support;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

public class ScriptManagedSchemaFilterProvider implements SchemaFilterProvider {
    private static final SchemaFilter GENERATED_TABLES = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) { return true; }
        @Override
        public boolean includeTable(Table table) { return !table.getName().equals("simulation_robot"); }
        @Override
        public boolean includeSequence(Sequence sequence) { return true; }
    };

    @Override
    public SchemaFilter getCreateFilter() { return GENERATED_TABLES; }
    @Override
    public SchemaFilter getDropFilter() { return GENERATED_TABLES; }
    @Override
    public SchemaFilter getTruncatorFilter() { return GENERATED_TABLES; }
    @Override
    public SchemaFilter getMigrateFilter() { return GENERATED_TABLES; }
    @Override
    public SchemaFilter getValidateFilter() { return SchemaFilter.ALL; }
}
