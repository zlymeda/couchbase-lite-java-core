package com.couchbase.lite;

import java.util.List;

/**
 * Context passed into a Validator.
 */
public interface ValidationContext {

    /**
     * The contents of the current revision of the document, or nil if this is a new document.
     */
    SavedRevision getCurrentRevision();

    /**
     * Gets the keys whose values have changed between the current and new Revisions
     */
    List<String> getChangedKeys();

    /**
     * Rejects the new Revision.
     */
    void reject();

    /**
     * Rejects the new Revision. The specified message will be included with the resulting error.
     */
    void reject(String message);

    /**
     * Calls the ChangeValidator for each key/value that has changed, passing both the old
     * and new values. If any delegate call returns false, the enumeration stops and false is
     * returned, otherwise true is returned.
     */
    boolean validateChanges(ChangeValidator changeValidator);
}
