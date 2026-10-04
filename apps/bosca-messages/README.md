# Bosca — Transactional Communications

The Bosca platform's first-party transactional communications as a **BML message project**
. New multi-channel units use a `<message>` root with sibling `<email>` and `<push>`
regions plus shared server Kotlin. They compile to `BmlMessageTemplate` entries in
`bml.generated.BmlMessages` and render against one `BmlMessageContext` (recipient fields, locale,
delivery metadata, and a JSON payload decoded inside the template).

## The templates

| Key | Trigger | Subject |
|---|---|---|
| `welcome` | Account created | Welcome to {appName} |
| `verify-email` | Email-verification challenge | Verify your email address |
| `reset-password` | Forgot-password request | Reset your {appName} password |
| `security-alert` | Security event (new sign-in, password changed) | Security alert for your {appName} account |
| `account-link` | Account-link confirmation requested | Confirm your {appName} account link |
| `form-submission` | Form submission created → reviewer profiles | New submission: {formName} |
| `form-submission-receipt` | Form submission created → the submitter | Your {formName} submission was received |
| `git-pull-request` | Pull-request lifecycle activity, reviews, and comments | Event-specific pull-request activity subject |
| `git-ref-update` | Branch/tag created, updated, or deleted | Event-specific ref activity subject |
| `workops-notification` | WorkOps task, spec, requirement, workflow, or comment activity | {entityKey}: {title} |
| `chat-message` | Chat message sent while the recipient is not connected | {senderName} sent a message in {channelName} |
| `chat-reaction` | A reaction is added to the recipient's chat message | {reactorName} reacted to your message |
| `prayer-reaction` | Another profile prays for or likes the recipient's prayer | Prayer activity |
| `prayer-comment` | Another profile comments on the recipient's prayer or replies to their comment | New prayer comment / New reply |
| `channel-invitation` | Chat channel invitation sent | {inviterName} invited you to {channelName} |
| `channel-joined` | A profile joins a chat channel | {profileName} joined {channelName} |
| `relationship-request` | Relationship requested | {requesterName} sent you a relationship request |
| `relationship-added` | Relationship added | {profileName} was added to your relationships |

Variants are **send-time state, not separate templates**: the recipient's first name is an
optional clause (`BmlMessageContext.recipientName`), expiry lines render only when the payload
carries wording, `security-alert` collapses its detail rows when there are none, and
`form-submission` shows an explicit empty state for field-less submissions. Payload models live
in `src/main/kotlin/bosca/messages/Payloads.kt` — they are the contract the send path encodes
`MessageBmlTemplate(project = "bosca-messages", templateKey, payload)` against.

## The visual system

A **light** product email in the Bosca Studio light theme: gray page (`#f6f7f9`), the logo and
wordmark masthead, a white hairline-bordered card, plain headlines with a "Hi {name}," greeting
line, solid ink CTAs (`#0e1019`), soft gray panels (`#f9fafb`) for records — with a green left
rule marking security events — and the muted footer below the card (preference links +
per-email reason line). Text-level green accents use `#047a52` (a darkened brand green —
`#00dc82` fails contrast as small text on white); Geist / Geist Mono (OFL, variable files under
`public/fonts`) are progressive enhancement over the system stacks.

**Branding is configured at send time through ConfigurationService**. The communications renderer
reads `bosca.messages.branding` for every `bosca-messages` render and overlays the template payload:

```json
{
  "title": "Bosca",
  "logoUrl": "https://cdn.example.com/email-logo.png",
  "logoOnly": false,
  "primaryColor": "#0e1019",
  "accentColor": "#047a52"
}
```

`logoUrl` must be absolute because delivered emails have no origin. `logoOnly` hides the title beside
the masthead logo while leaving the document title and brand-aware copy intact. Colors accept CSS hex
notation; invalid colors fall back to the built-in values. If the configuration entry is absent, the Bosca
defaults remain in effect. The payload models retain the five resulting fields because that is the
contract the BML server decodes after communications enriches it. The cube mark ships as a hosted PNG
(Gmail strips inline SVG), generated (PIL) from the mark in `web` — regenerate rather than hand-edit.

## Layout

```
src/main/bml/
  messages/     message units with email and/or push regions
  components/   bosca-shell (document chrome), bosca-heading, cta-button, panel
  pages/        the local preview site (index + /preview/{slug})
src/main/kotlin/bosca/messages/
  Payloads.kt   @Serializable payload models (decoded inside the templates)
  Samples.kt    the design-review sample contexts (14 preview variants)
  Theme.kt      URL-bearing head CSS built from the absolute asset base
  Main.kt       the preview BmlServer
public/         the cube-mark PNG + fonts (flat tree — assetsUrl points at this root)
```

## Preview

Run from the workspace root:

```bash
./gradlew :bosca-messages:run   # -> http://localhost:4567/
./gradlew :bosca-messages:test  # renders every variant and pins the state-dependent copy
```

Each preview responds the template's **raw rendered document** — what actually ships — and
`?text` shows the plain-text alternative with the subject line. Geist is progressive
enhancement: clients that strip `@font-face` (Gmail, Outlook-Windows) get the system stack,
which is exactly what the previews fall back to without the served fonts.

## How these send (the contract)

Rendering: `BmlMessages.byKey["<key>"].renderMessage(BmlMessageContext(recipient…, payload))` →
`RenderedMessage(email, push)`. The email HTML is safe (scripts stripped via `EmailRenderer`),
the text alternative comes from `PlainTextRenderer`, and push title/body resolve through the same
recipient locale and message source. In production
`bml-message-server` hosts this project's published jar and the communications send path
assembles the message context — unsubscribe/preferences links are minted there, and every asset
reference builds from `BmlMessageContext.assetsUrl` (the server's version-pinned
`/assets/bosca-messages/<version>` base; templates fail loudly without it). Email link destinations
arrive fully formed in the payload. Push destinations arrive as producer-owned actions; templates
select them by stable ID and provide the localized labels. Chat message and reaction content stays
out of the persisted payload and is retrieved through authenticated GraphQL only while rendering.

## Publish

The root [message-template pipeline](../../.bosca/pipelines/apps-bosca-messages-publish.yaml)
runs on workspace tags or manually, builds, tests, and publishes
`build/libs/bosca-messages.jar` to the artifacts registry as
`(type=raw, namespace=bml-message, coordinate=bosca-messages, version=<utc-timestamp>-<sha>)`.
The registry's publish event hot-reloads `bml-message-server`; register/pin the project for
sends via the communications `bmlMessageProjects` admin GraphQL.

Rich push data stays producer-owned. A template opts into the available presentation with empty
`<image/>`, `<attachments/>`, and `<conversation/>` tags; communications supplies the URLs,
bounded history, grouping, routing, and provider options through the message context.

## Dev loop

Run Gradle from the `bosca-workspace` root. The BML Gradle plugin is available from the root
build logic, and the root build substitutes `io.bosca:*` dependencies with local source projects.
No local Maven publication is needed.
