# bosca-communications

Push notification and email infrastructure for the Bosca platform. Provides FCM (Firebase Cloud Messaging) and APNs push delivery, email sending via SendGrid or Mailgun, and a message templating system with support for scheduled delivery through background jobs.

## Modules

| Module | Description |
|---|---|
| `core-communications` | Core contracts -- models (Channel, Message, MessageTemplate), service interfaces |
| `communications` | Implementation -- push sending (FCM/APNs), email (SendGrid/Mailgun), background jobs |

## Prerequisites

- Java 25

## Build

Run these commands from the **workspace root**. `communications` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :communications:test  # Test both communications modules
./gradlew :communications:communications:koverHtmlReport  # Coverage report
```

## Architecture

Follows the core-contract pattern: `core-communications` defines interfaces and models, while `communications` provides the implementations. Multi-channel delivery is unified through `MessageService`, supporting push (FCM/APNs) and email (SendGrid or Mailgun). Background job processing via `MessageJob` handles async and scheduled delivery. APNs JWT token signing uses BouncyCastle, and message delivery integrates with SharedQueue for reliability.

A BML `<message>` unit may contain sibling `<email>` and `<push>` regions. Push delivery renders from the same typed payload, published version, and recipient locale as email. Event producers supply grouping, routing, action destinations, and rich-content metadata through `Message.pushOptions`; a BML `<push>` owns visible title/body copy and localized action labels. Declarative `<action id="…">` tags select producer-supplied actions, with at most one marked `default`. `PushOptions.richContent` carries remote media and exactly one current conversation message; Android and iOS own history, deduplication, and grouping. Actions and rich content are sent as `bosca_push_actions_v1` and `bosca_rich_push_v1` provider data for capable clients.

Select the email provider with `MAILER_TYPE=sendgrid` or `MAILER_TYPE=mailgun`. Provider credentials are encrypted platform configuration entries: `sendgrid` uses `apiKey`; `mailgun` uses `apiKey`, `domain`, and an optional `apiBaseUrl` (`https://api.mailgun.net` by default, or `https://api.eu.mailgun.net` for EU domains).

## Dependencies

Consumes from Maven:
- `io.bosca:core`, `io.bosca:core-security` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":communications:core-communications"))
    implementation(project(":communications:communications"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
