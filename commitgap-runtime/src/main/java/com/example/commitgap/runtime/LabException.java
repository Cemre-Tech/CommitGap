package com.example.commitgap.runtime;

/** A problem with the lab itself (Docker, containers, connectivity), as opposed to a measured result. */
public class LabException extends RuntimeException {

    public LabException(String message) {
        super(message);
    }

    public LabException(String message, Throwable cause) {
        super(message, cause);
    }
}
