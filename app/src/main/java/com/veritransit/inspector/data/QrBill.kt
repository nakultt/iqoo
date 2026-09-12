package com.veritransit.inspector.data

/**
 * What a scanned E-Way Bill QR actually yielded.
 *
 * [ewb] is the 12-digit bill number formatted the way the rest of the app
 * prints it (`7819-2044-8831`); [vehicle] a registration plate if the payload
 * carried one. Both are null when the QR was not bill-shaped — a scanned
 * random QR should still lock the frame honestly rather than invent fields,
 * and the manifest step exists to correct whatever the code did say.
 */
data class QrBillFields(
    val ewb: String?,
    val vehicle: String?,
    val raw: String,
)

/**
 * Pure extraction of bill fields out of a decoded QR/barcode payload.
 *
 * Real E-Way Bill QRs are not one fixed format: the printout carries anything
 * from a bare 12-digit number to JSON with vehicle and route fields. Rather
 * than parse one vendor shape, this pulls out the two identifiers the manifest
 * needs — a standalone 12-digit run and a plate-shaped token — and leaves
 * everything else to the officer.
 *
 * Internal so the JVM test suite can exercise the exact path the scanner
 * takes on device.
 */
object QrBill {

    /** The bill number: exactly 12 digits, not part of a longer run. */
    private val EWB = Regex("(?<![0-9])[0-9]{12}(?![0-9])")

    /**
     * Plate-shaped token: two letters, one or two digits, up to three letters,
     * three or four digits — `TN 38 BX 4491` or `TN38BX4491`. Boundaries keep
     * it from matching inside longer alphanumeric blobs.
     */
    private val VEHICLE = Regex("(?<![A-Z0-9])[A-Z]{2}\\s*[0-9]{1,2}\\s*[A-Z]{0,3}\\s*[0-9]{3,4}(?![0-9])")

    fun parse(payload: String): QrBillFields {
        val upper = payload.uppercase()
        val ewb = EWB.find(upper)?.value
        val vehicle = VEHICLE.find(upper)?.value?.replace(Regex("\\s+"), " ")
        return QrBillFields(
            ewb = ewb?.chunked(4)?.joinToString("-"),
            vehicle = vehicle,
            raw = payload,
        )
    }
}
