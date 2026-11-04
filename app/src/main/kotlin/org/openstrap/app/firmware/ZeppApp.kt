package org.openstrap.app.firmware

/**
 * The Zepp app OpenStrap names itself as to Zepp's servers, for the firmware check and the account calls
 * ([org.openstrap.app.zepp.ZeppCloud]). The firmware server keys its answer on this version, so a new strap firmware
 * that Zepp only offers to its newer app shows up as "up to date" until this follows Zepp. One place, so both kinds of
 * call always say the same.
 */
object ZeppApp {
    const val ID = "428135909242707968"
    const val NAME = "com.huami.midong"

    /** Zepp 10.10.5 from Google Play, released 2026-11-04. */
    const val VERSION_NAME = "10.10.5-play"
    const val VERSION_CODE = "151997"
}
