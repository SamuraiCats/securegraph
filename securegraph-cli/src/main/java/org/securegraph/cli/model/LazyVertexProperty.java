package org.securegraph.cli.model;

import org.securegraph.Property;
import org.securegraph.Vertex;
import org.securegraph.Visibility;

public class LazyVertexProperty extends LazyProperty {
    private final String vertexId;

    public LazyVertexProperty(String vertexId, String key, String name, Visibility visibility) {
        super(key, name, visibility);
        this.vertexId = vertexId;
    }

    @Override
    protected String getToStringHeaderLine() {
        return "vertex @|bold " + getVertexId() + "|@ property";
    }

    @Override
    protected Property getP() {
        Vertex vertex = getGraph().getVertex(getVertexId(), getAuthorizations());
        if (vertex == null) {
            return null;
        }
        return vertex.getProperty(getKey(), getName(), getVisibility());
    }

    public String getVertexId() {
        return vertexId;
    }
}
