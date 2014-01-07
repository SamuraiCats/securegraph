package com.altamiracorp.securegraph.accumulo.blueprints;

import com.altamiracorp.securegraph.accumulo.blueprints.util.AccumuloBlueprintsGraphTestHelper;
import com.altamiracorp.securegraph.blueprints.SecureGraphBlueprintsVertexTestBase;

public class AccumuloVertexTest extends SecureGraphBlueprintsVertexTestBase {
    public AccumuloVertexTest() {
        super(new AccumuloBlueprintsGraphTestHelper());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        ((AccumuloBlueprintsGraphTestHelper) this.graphTest).setUp();
    }
}