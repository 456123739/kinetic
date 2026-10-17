package com.kinetic.model.catalog.source;

public final class ModelPackException extends CatalogBuildException {
    public ModelPackException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelPackException(String message) {
        super(message);
    }
}
