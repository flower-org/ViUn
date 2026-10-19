package com.viun.mysql;

/** Raised when a {@link MySqlViUnSource} operation violates ViUn model constraints. */
public class ViUnSourceException extends RuntimeException {
    public ViUnSourceException(String message) {
        super(message);
    }

    /** The entity referenced by an operation does not exist. */
    public static class NotFound extends ViUnSourceException {
        public NotFound(String message) {
            super(message);
        }
    }
}
