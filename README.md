# Wallet toolbar + system Dark/Light mode crash fix

This revision fixes the theme resource cycle that could crash the app at startup.

Root cause in previous revision:
- `colorAccent` was defined as `?android:attr/colorAccent` inside the app theme.
- `colorPrimary` also depended on that same self-resolving chain.
- This could cause recursive theme attribute resolution at runtime.

Fix:
- Do not redefine `colorAccent`.
- Inherit AppCompat's `colorAccent` from `Theme.AppCompat.DayNight.NoActionBar`.
- `colorPrimary` references the inherited app `?attr/colorAccent`.
- `colorPrimaryDark` references `?attr/colorPrimary`.
- Light/Dark selection remains controlled by `Theme.AppCompat.DayNight.NoActionBar`.
- No app palette/colors.xml was added.

The Bitcoin wallet logic was not changed by this crash fix.

Gradle build could not be completed in this environment because Gradle 5.6.4 is not cached and network access to services.gradle.org is unavailable.
