/*
 * Lumos - android-app/app / The Room DAO for the profile table.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-PRO-001, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The typed path, for the one table WP-02 S3 actually reads and writes.
 * The other six kinds go through the generic SqlDatabase seam, because Room
 * cannot parameterise a table name and a DAO per kind would be six copies of
 * these four methods with a different type each.
 *
 * The value Room adds here is real and worth naming: these queries are verified
 * against the schema AT COMPILE TIME. A column renamed in the entity and not in
 * the query is a build failure, which is the class of defect the generic
 * string-SQL path can only discover at runtime.
 *
 * Every method takes and returns SEALED bytes. The DAO never sees profile
 * content, and it has no way to: the payload is opened above it by
 * SealedRecordCodec, so a DAO-level logging mistake cannot leak a profile.
 */

package com.lumos.app.store.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/** Typed access to the local_profile table. */
@Dao
public interface ProfileDao {

    /**
     * Inserts or replaces a profile row.
     *
     * @param row the sealed row
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SealedRecordEntity.LocalProfileRow row);

    /**
     * Reads one profile row.
     *
     * @param id the row identifier
     * @return the row, or null when absent
     */
    @Query("SELECT * FROM local_profile WHERE id = :id")
    SealedRecordEntity.LocalProfileRow byId(String id);

    /**
     * Reads every profile row, newest update first.
     *
     * @return the rows
     */
    @Query("SELECT * FROM local_profile ORDER BY updated_at DESC")
    List<SealedRecordEntity.LocalProfileRow> all();

    /**
     * Counts profile rows.
     *
     * @return the row count
     */
    @Query("SELECT COUNT(*) FROM local_profile")
    int count();

    /**
     * Deletes one profile row.
     *
     * @param id the row identifier
     */
    @Query("DELETE FROM local_profile WHERE id = :id")
    void deleteById(String id);

    /**
     * Deletes every profile row - the content stage of the keys-first wipe.
     */
    @Query("DELETE FROM local_profile")
    void deleteAll();
}
