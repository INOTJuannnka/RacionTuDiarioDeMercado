package com.racion.diariomercado.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import com.racion.diariomercado.data.local.entity.UserProfileEntity
import com.racion.diariomercado.domain.model.SportFocus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The uid re-key, against a real in-memory Room database.
 *
 * ## Why this test exists at all
 * Everything here is a claim about what happens to a user's data when they reclaim an anonymous
 * account. Those claims are invisible in the UI: if the re-key silently moved zero rows, the
 * profile screen would still render, the diary would still be there, and the only symptom would be
 * that a goal the user set three weeks ago quietly reverted to the default 1900 kcal — days later,
 * on a different screen. So the assertions are on the rows, not on a returned boolean.
 *
 * ## What it deliberately does NOT touch
 * The diary. `diary_entries`, `sync_outbox` and `food_products` have no `userId` column at all
 * (`DiaryDao` contains no reference to it), which is the whole reason the existing-account path is
 * affordable. If someone later adds a `userId` to `diary_entries`, this test will keep passing and
 * the guarantee will quietly become false — that is the trap worth knowing about, and the comment on
 * `SessionDataReassigner` repeats it.
 *
 * `allowMainThreadQueries()` for the same reason as `DiaryDaoTest`: Robolectric runs the body on the
 * main thread.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDataReassignerTest {

    private lateinit var db: RacionDatabase
    private lateinit var reassigner: RoomSessionDataReassigner

    private val anonymousUid = "anon-uid-1"
    private val existingUid = "existing-uid-2"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        reassigner = RoomSessionDataReassigner(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Whether the re-key reported success, so the assertions read as one line.
     *
     * A local helper rather than a new member on `AppResult`: the codebase branches with
     * `when (val result = ...) { is Success -> ...; is Failure -> ... }` everywhere, and adding a
     * production API to make eight assertions shorter is the wrong direction to grow in.
     */
    private fun AppResult<Unit>.succeeded(): Boolean = this is AppResult.Success

    // -- The happy path ----------------------------------------------------------------------

    @Test
    fun profileRowFollowsTheUidToItsNewOwner() = runTest {
        db.profileDao().saveProfile(
            UserProfileEntity(userId = anonymousUid, displayName = "Juli", sportFocus = SportFocus.FUERZA)
        )

        val result = reassigner.reassign(anonymousUid, existingUid)

        assertTrue(result.succeeded())
        assertNull(
            "the anonymous row must not survive the re-key",
            db.profileDao().observeProfile(anonymousUid).first()
        )
        val moved = db.profileDao().observeProfile(existingUid).first()
        assertNotNull("the row must exist under the new uid", moved)
        // The VALUES travel with the key. A re-key that reset them to their defaults would look
        // identical to a successful migration in every screen that only renders a label.
        assertEquals("Juli", moved?.displayName)
        assertEquals(SportFocus.FUERZA, moved?.sportFocus)
    }

    @Test
    fun goalsRowFollowsTheUidToItsNewOwner() = runTest {
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = anonymousUid, kcalPerDay = 2450))

        val result = reassigner.reassign(anonymousUid, existingUid)

        assertTrue(result.succeeded())
        assertNull(db.goalsDao().observeGoals(anonymousUid).first())
        assertEquals(2450, db.goalsDao().observeGoals(existingUid).first()?.kcalPerDay)
    }

    @Test
    fun bothTablesAreHandledInOneCallBecauseTheyAlwaysBelongToTheSameUser() = runTest {
        db.profileDao().saveProfile(UserProfileEntity(userId = anonymousUid, displayName = "Juli"))
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = anonymousUid, kcalPerDay = 2450))

        reassigner.reassign(anonymousUid, existingUid)

        // Not two tests' worth of luck: a re-assigner that only knew about one table would pass the
        // two tests above and leave the user with a claimed account and a default goal.
        assertNotNull(db.profileDao().observeProfile(existingUid).first())
        assertNotNull(db.goalsDao().observeGoals(existingUid).first())
    }

    // -- The primary-key collision ------------------------------------------------------------

    @Test
    fun aRowAlreadyOwnedByTheTargetAccountWinsAndTheSourceIsDropped() = runTest {
        // Reachable, not hypothetical: sign into B, sign out (which is free on a permanent
        // session), fall back to anonymous, then sign into B again. The device still holds B's row.
        db.profileDao().saveProfile(
            UserProfileEntity(userId = existingUid, displayName = "Nombre de la cuenta real")
        )
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = existingUid, kcalPerDay = 2100))
        db.profileDao().saveProfile(UserProfileEntity(userId = anonymousUid, displayName = "Invitado"))
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = anonymousUid, kcalPerDay = 3000))

        val result = reassigner.reassign(anonymousUid, existingUid)

        assertTrue(result.succeeded())
        val profile = db.profileDao().observeProfile(existingUid).first()
        val goals = db.goalsDao().observeGoals(existingUid).first()
        // The user explicitly asked to enter THAT account, so that account's own settings win. The
        // alternative — letting the anonymous rows overwrite — would make the account's real
        // configuration depend on whichever device it was last opened on.
        assertEquals("Nombre de la cuenta real", profile?.displayName)
        assertEquals(2100, goals?.kcalPerDay)
        // And the losing rows are gone, not orphaned: a leftover row under the dead anonymous uid
        // would resurface if the user ever got that uid back.
        assertNull(db.profileDao().observeProfile(anonymousUid).first())
        assertNull(db.goalsDao().observeGoals(anonymousUid).first())
    }

    @Test
    fun aCollisionIsNotAConstraintViolationCrash() = runTest {
        db.profileDao().saveProfile(UserProfileEntity(userId = existingUid))
        db.profileDao().saveProfile(UserProfileEntity(userId = anonymousUid))

        val result = reassigner.reassign(anonymousUid, existingUid)

        // `userId` is the PRIMARY KEY of both tables, so the naive `UPDATE ... SET userId = :new`
        // throws SQLiteConstraintException the moment the target row exists. This asserts the
        // ordering that avoids it, and it is the single most likely way to get this feature wrong.
        assertTrue("must not surface a constraint violation", result.succeeded())
    }

    // -- Cases that are not the user's fault ----------------------------------------------------

    @Test
    fun reassigningAUserWithNoLocalRowsSucceedsQuietly() = runTest {
        // The user reclaimed the account from a second device: no anonymous rows exist here at all.
        // Zero rows moved is a success, not a failure, and reporting it as an error would put an
        // error message in front of a user who did nothing wrong.
        val result = reassigner.reassign("never-seen-anon-uid", existingUid)

        assertTrue(result.succeeded())
    }

    @Test
    fun reassigningToTheSameUidIsANoOpAndNotACorruptingSelfJoin() = runTest {
        db.profileDao().saveProfile(UserProfileEntity(userId = anonymousUid, displayName = "Juli"))

        val result = reassigner.reassign(anonymousUid, anonymousUid)

        assertTrue(result.succeeded())
        val profile = db.profileDao().observeProfile(anonymousUid).first()
        assertNotNull("the row must still be there", profile)
        assertEquals("Juli", profile?.displayName)
    }

    @Test
    fun anotherUsersRowsAreNeverTouched() = runTest {
        db.profileDao().saveProfile(UserProfileEntity(userId = anonymousUid, displayName = "Juli"))
        db.profileDao().saveProfile(UserProfileEntity(userId = "somebody-else", displayName = "Otra"))

        reassigner.reassign(anonymousUid, existingUid)

        // Guards against an `UPDATE` written without a `WHERE`, which would re-key every row in the
        // table and hand one user's settings to another.
        assertEquals("Otra", db.profileDao().observeProfile("somebody-else").first()?.displayName)
    }
}
