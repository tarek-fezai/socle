// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

/** Motif d'inscription dans {@code git_purge_queue}. */
public enum GitPurgeMotif {
    RETENTION("retention"),
    RGPD("rgpd"),
    CORBEILLE("corbeille");

    private final String dbValue;

    GitPurgeMotif(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
