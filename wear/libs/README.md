# Samsung Health Tracking client

Hardware ECG on Galaxy Watch uses Samsung’s Privileged Health SDK.

Place the official AAR here as:

```
wear/libs/samsung-health-sensor-api.aar
```

Then Gradle will compile `:wear` against that client instead of `:samsung-health-api` stubs.

Without the AAR the watch app still builds and can **Record demo** so live Data Layer sync with the phone can be verified. `ECG_ON_DEMAND` will not be granted to `app.healthtrack` on a stock Galaxy Watch unless Samsung has partner-whitelisted this package.
