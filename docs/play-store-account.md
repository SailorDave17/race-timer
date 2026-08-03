# Play Store Account — Which Google Account Holds Race Timer

The Google Play developer account this app publishes under, written down so a future release does not
have to guess. Sign in with this account at [play.google.com/console](https://play.google.com/console).

This matters more than it looks. To Play, an app is owned by the account that first uploads it: a build
published, signed, or updated from a *different* Google account is a **different app**, with its own
listing and no upgrade path for anyone who already installed this one. There is no self-service way to
merge the two afterwards.

Registration and verification are
[#67](https://github.com/SailorDave17/race-timer/issues/67), under epic
[#66](https://github.com/SailorDave17/race-timer/issues/66).

## The account

| | |
|---|---|
| Developer name | `SailorDave17` |
| Account type | Personal |
| Account ID | `7959489197612268555` |
| Google account | `hsc.coach@gmail.com` |

**The account ID is the identifier to trust.** It appears in Console under **Settings → Developer
account**, and unlike the developer name it cannot be edited. If there is ever doubt about which
account a browser is signed in as, compare that number rather than the display name.

## Status as of 2026-08-03

- Registered; the one-time **$25** fee is paid.
- Identity verification **approved** — not merely submitted.
- Device verification completed by signing in to Play on a certified **phone**. The watch does not
  satisfy this requirement.
- The Console reaches the **Create app** flow with no blocking banner.

Nothing about the account gates the rest of epic #66 any more.

## What "personal" costs downstream

An organization account was rejected deliberately: it requires a D-U-N-S number and a verifiable
business, which is weeks of work for no benefit to an app like this one.

The one consequence that reaches the release plan: **personal accounts created after November 2023 must
complete a 12-tester / 14-day closed test before they can request production access.** Internal
testing — the target of epic [#66](https://github.com/SailorDave17/race-timer/issues/66) — is
unaffected either way, so this blocks nothing today. It does mean that any later production release has
a 14-day clock in front of it, and that clock only runs against a real closed-test track. Recruiting
testers early is [#80](https://github.com/SailorDave17/race-timer/issues/80).

## Maintainer note

Written for the last acceptance criterion of
[#67](https://github.com/SailorDave17/race-timer/issues/67).

The table was supplied by the account holder on 2026-08-03, and the statuses above are as recorded on
#67, whose criteria were ticked against the Console itself. **Nothing in this file is read from the
Play Console by tooling**, so it cannot notice drift: if the developer name changes, or the account is
ever migrated, this file will go stale silently. The account ID is the field least likely to do that.
