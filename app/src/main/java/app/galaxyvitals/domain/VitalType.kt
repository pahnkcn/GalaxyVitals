package app.galaxyvitals.domain

/** Extensible vital kinds. ECG is implemented; BP is a reserved slot. */
enum class VitalType {
    ECG,
    BLOOD_PRESSURE,
}
