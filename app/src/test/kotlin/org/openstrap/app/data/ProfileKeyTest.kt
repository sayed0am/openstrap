package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.testing.profile

/** The daily energy profile key is stored and compared after a restart, so it has to be the same in every process (P2). */
class ProfileKeyTest {
    private fun key(p: UserProfile) = DailyCaloriesHistory.profileKey(p)

    @Test fun `equal profiles built separately have one key`() {
        assertEquals(key(profile(maxHeartRate = 190)), key(profile(maxHeartRate = 190)))
        assertEquals(key(UserProfile()), key(UserProfile()))
    }

    @Test fun `the key is a fixed number, not one that changes with the process`() {
        // An enum's hashCode is the object's identity, so a key built from it differed after every restart.
        val p = UserProfile(sex = UserProfile.Sex.Male, weightKg = 70f, heightCm = 180, maxHeartRate = 190)
        assertEquals(1_799_101_530L, key(p))
    }

    @Test fun `the key changes with each field that moves a day's energy`() {
        val base = profile(maxHeartRate = 190)
        val changed = listOf(
            profile(maxHeartRate = 190, weightKg = 71f),
            profile(maxHeartRate = 190, heightCm = 176),
            profile(maxHeartRate = 190, birthYear = 1991),
            profile(maxHeartRate = 190, sex = UserProfile.Sex.Female),
            profile(maxHeartRate = 190, sex = null),
            profile(maxHeartRate = 191),
            profile(maxHeartRate = null),
        )
        for (p in changed) assertNotEquals(p.toString(), key(base), key(p))
        // Other fields don't move it.
        assertEquals(key(base), key(base.copy(sleepNeedMinutes = 480, imperial = true)))
    }
}
