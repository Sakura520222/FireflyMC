# Security Review: FireflyMC

## Scope

Read-only standard audit of exact Git revision 9055bb2ec96c4bf3cc0287a3b1bbf9ecdfc8cc2b at D:\\Project\\FireflyMC. Authentication, networking, relay/P2P, AI, credential storage, and privileged release workflow paths were source-reviewed.

- Scan mode: repository
- Target kind: git_revision
- Target ID: target_sha256_7c692afd03a5493d745fdea792f87873d4ee0a3cb036a4279b59306bdd78314b
- Revision: 9055bb2ec96c4bf3cc0287a3b1bbf9ecdfc8cc2b
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Runtime or test status: not_run
- Artifacts reviewed: .github/workflows/release-on-pr-merge.yml, src/main/java/firefly520/fireflymc/ModEventHandler.java, src/main/java/firefly520/fireflymc/ServerConfig.java, src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java, src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java, src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java, src/main/java/firefly520/fireflymc/client/auth/ClientAuthLockoutManager.java, src/main/java/firefly520/fireflymc/mixin/ConnectScreenMixin.java, src/main/java/firefly520/fireflymc/ai/AIConfig.java, src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java, src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java, src/main/java/firefly520/fireflymc/ai/AIApiClient.java, src/main/java/firefly520/fireflymc/ai/ToolContext.java, src/main/java/firefly520/fireflymc/ai/ChatHistoryManager.java, src/main/java/firefly520/fireflymc/ai/ChatMessage.java, src/main/java/firefly520/fireflymc/ai/function/GetBlocksFunctionTool.java, src/main/java/firefly520/fireflymc/ai/function/SetTimeFunctionTool.java, src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java, src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java, src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java, src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java, src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java, src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java, src/main/java/firefly520/fireflymc/client/relay/p2p/ReorderBuffer.java, src/main/java/firefly520/fireflymc/client/relay/p2p/P2PHostBridge.java, src/main/java/firefly520/fireflymc/music/MusicApiClient.java, src/main/java/firefly520/fireflymc/client/UpdateChecker.java
- Scan context: Host-backed continuation used the authoritative scan context supplied by the user; no new setup workspace was opened. TAC was advisory only and returned USER_NOT_LOGGED_IN.

Limitations and exclusions:
- Target inventory has 279 tracked files; these are the security-relevant files fully audited by workers and parent, while other files were inventory-scanned only.
- No application runtime, Minecraft/NeoForge integration, Docker/production deployment, external relay/event service, or live GitHub settings were executed or inspected.
- TAC status could not be verified because the connector returned USER_NOT_LOGGED_IN.
- Excluded external relay/event service: Implementation and live authentication/quotas are outside the repository.
- Excluded Minecraft/NeoForge internals and dependency source: Framework internals were not executed or separately audited.
- Excluded live GitHub settings, PAT scopes, firewall, provider billing, production E2E: These controls require external access and were not inferred.

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 12 |
| Severity mix | high: 4, medium: 7, low: 1 |
| Confidence mix | high: 11, medium: 1 |
| Coverage | partial |
| Validation mode | static source/control/sink review with independent baseline and architecture investigators plus parent revalidation |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

FireflyMC is a Java 21 NeoForge 1.21.1 mod covering multiplayer authentication, integrated-server/LAN bridging, relay/P2P networking, AI provider calls/tools, local persistence, and GitHub Actions release automation. Client input, relay/provider/model output, LAN peers, and PR metadata are untrusted at their boundaries.

### Assets

- offline-mode player identity and world state
- password records and auth sessions
- AI credentials/history/tool authority/API quota
- LAN sockets and relay/P2P state
- release tags/JARs/write tokens/build provenance

### Trust Boundaries

- client to server payload/command boundary
- server to AI provider/model tools
- player/console to shared AI history
- client to relay/LAN/P2P
- GitHub metadata to privileged Actions shell

### Attacker Capabilities

- modified remote client
- ordinary player sending chat or /ai
- reachable LAN/UDP sender
- malicious relay/provider
- PR author whose metadata reaches merged event
- attacker obtaining local files

### Security Objectives

- authenticate before gameplay
- preserve peer/session authenticity
- bound memory/thread/socket/provider resources
- enforce authorization floors and principal separation
- bind artifacts to immutable source
- resist offline password guessing

### Assumptions

- Git tree equals targetRevision 9055bb2ec96c4bf3cc0287a3b1bbf9ecdfc8cc2b.
- External relay/event auth, branch protection, firewall, and token scopes are not established here.
- AI model compliance is probabilistic; source-level principal isolation is required.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [Shared AI history lets low-privilege messages influence later high-privilege tool execution](#finding-1) | high | medium | inline below |
| [Merged pull request titles are interpolated directly into a privileged shell workflow](#finding-2) | high | high | inline below |
| [Password authentication is not enforced as a server-side authorization gate](#finding-3) | high | high | inline below |
| [Reliable UDP P2P transport accepts unauthenticated packets and lets any sender replace the active peer](#finding-4) | high | high | inline below |
| [AI cooling is per-player only; no global or in-flight request budget exists](#finding-5) | medium | high | inline below |
| [Release attachments are built from moving main rather than the triggering merge commit](#finding-6) | medium | high | inline below |
| [Guest relay proxies advertised as loopback bind to all network interfaces](#finding-7) | medium | high | inline below |
| [Relay WebSocket messages and stream creation have no resource limits](#finding-8) | medium | high | inline below |
| [Unauthenticated UDP packets can grow P2P reorder and pre-registration state without bound](#finding-9) | medium | high | inline below |
| [Configured global AI tool permission floor is ignored](#finding-10) | medium | high | inline below |
| [Password brute-force lockout is enforced only by the untrusted client](#finding-11) | medium | high | inline below |
| [Player PINs are stored using a single fast SHA-256 digest](#finding-12) | low | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] Shared AI history lets low-privilege messages influence later high-privilege tool execution

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | medium |
| Confidence rationale | The shared history and authority transition are deterministic; final model interpretation is probabilistic. |
| Category | authorization-and-confused-deputy |
| CWE | CWE-441, CWE-284 |
| Affected lines | src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63, src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:99-122, src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:64-70, src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:100-105, src/main/java/firefly520/fireflymc/ai/ToolContext.java:20-37 |

#### Summary

All player chat and /ai messages share one server history, while tools execute with the later triggerer's ToolContext. Player-authored messages have no provenance or permission boundary.

#### Root Cause

A server-wide transcript is reused as an authorization context. Low-privilege player content reaches later model calls, while tool authorization is recomputed from the later OP/console caller.

**History is keyed only by server** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No player, permission, or conversation principal is in the key.

```java
private static final ConcurrentHashMap<MinecraftServer, ChatHistoryManager> HISTORY_MANAGERS = new ConcurrentHashMap<>();
```

**Player messages enter shared history** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:99-122`

Any player can insert arbitrary user content.

```java
historyManager.addMessage(ChatMessage.of(player.getGameProfile().getName(), message, MessageType.PLAYER));
```

**Later calls send mixed history** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:64-70`

The model receives mixed history while current context supplies authority.

```java
var snapshot = List.copyOf(history.getHistory());\nAIApiClient.callAIWithFunctions(snapshot, prompt, playerName, tools);
```

**Tools execute under current context** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:100-105`

Message authors are not consulted.

```java
FunctionCallResult result = executeOne(ctx, call);
```

**Console is always highest authority** — `src/main/java/firefly520/fireflymc/ai/ToolContext.java:20-37`

Console loops bypass permission checks.

```java
if (player == null) return true;
```

#### Validation

Source confirms shared history, mixed snapshots, and current-context tool execution; no provenance or principal isolation exists.

Validation method: static source/control/sink trace

**History is keyed only by server** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No player, permission, or conversation principal is in the key.

```java
private static final ConcurrentHashMap<MinecraftServer, ChatHistoryManager> HISTORY_MANAGERS = new ConcurrentHashMap<>();
```

**Player messages enter shared history** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:99-122`

Any player can insert arbitrary user content.

```java
historyManager.addMessage(ChatMessage.of(player.getGameProfile().getName(), message, MessageType.PLAYER));
```

**Later calls send mixed history** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:64-70`

The model receives mixed history while current context supplies authority.

```java
var snapshot = List.copyOf(history.getHistory());\nAIApiClient.callAIWithFunctions(snapshot, prompt, playerName, tools);
```

**Tools execute under current context** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:100-105`

Message authors are not consulted.

```java
FunctionCallResult result = executeOne(ctx, call);
```

**Console is always highest authority** — `src/main/java/firefly520/fireflymc/ai/ToolContext.java:20-37`

Console loops bypass permission checks.

```java
if (player == null) return true;
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

low-privilege chat -\> shared history -\> privileged snapshot -\> model tool call -\> current context

**History is keyed only by server** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No player, permission, or conversation principal is in the key.

```java
private static final ConcurrentHashMap<MinecraftServer, ChatHistoryManager> HISTORY_MANAGERS = new ConcurrentHashMap<>();
```

**Player messages enter shared history** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:99-122`

Any player can insert arbitrary user content.

```java
historyManager.addMessage(ChatMessage.of(player.getGameProfile().getName(), message, MessageType.PLAYER));
```

**Later calls send mixed history** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:64-70`

The model receives mixed history while current context supplies authority.

```java
var snapshot = List.copyOf(history.getHistory());\nAIApiClient.callAIWithFunctions(snapshot, prompt, playerName, tools);
```

**Tools execute under current context** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:100-105`

Message authors are not consulted.

```java
FunctionCallResult result = executeOne(ctx, call);
```

**Console is always highest authority** — `src/main/java/firefly520/fireflymc/ai/ToolContext.java:20-37`

Console loops bypass permission checks.

```java
if (player == null) return true;
```

#### Reachability

Any player can write chat; a later authorized trigger is routine.

#### Severity

**High** — A low-privilege player can plant instruction-like content that a later OP4/console invocation may execute with higher authority.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** high
- **Why:** High-authority tools can kick players or modify world regions.

Likelihood assessment:
- **Level:** medium
- **Why:** The crossing is deterministic but model compliance is probabilistic.

#### Remediation

Separate histories by principal and permission, label player content as non-instruction data, bind every tool call to the original initiator, isolate console/OP histories, and redact private tool results.

Tests:
- Inject a low-privilege instruction then invoke AI as OP4/console; verify no privileged tool executes from prior history.
- Test cross-player/private reply isolation.

Preventive controls:
- Treat history as untrusted multi-tenant data.
- Require confirmation for privileged AI actions and log the initiating principal.

<a id="finding-2"></a>

### [2] Merged pull request titles are interpolated directly into a privileged shell workflow

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | The expressions and job permissions are literal in the workflow and are evaluated before Bash runs. |
| Category | ci-cd-command-injection |
| CWE | CWE-78 |
| Affected lines | .github/workflows/release-on-pr-merge.yml:13-16, .github/workflows/release-on-pr-merge.yml:24-34, .github/workflows/release-on-pr-merge.yml:43-47 |

#### Summary

The post-merge release workflow expands an attacker-controlled pull request title into Bash source while holding repository write permissions and an optional PAT.

#### Root Cause

The workflow treats a PR title as trusted shell text and interpolates it into two run blocks instead of passing it through an environment variable or file.

**Merged job has write permissions** — `.github/workflows/release-on-pr-merge.yml:13-16`

The shell runs in a privileged post-merge job.

```yaml
if: github.event.pull_request.merged == true\npermissions:\n  contents: write\n  pull-requests: write
```

**Checkout carries a PAT or write token** — `.github/workflows/release-on-pr-merge.yml:24-29`

The optional PAT can broaden impact.

```yaml
token: ${{ secrets.MY_RELEASE_PAT || secrets.GITHUB_TOKEN }}
```

**Raw title is injected into Bash** — `.github/workflows/release-on-pr-merge.yml:31-47`

Quotes, substitutions, newlines, and metacharacters become shell syntax.

```yaml
echo "PR 标题: ${{ github.event.pull_request.title }}"\nTITLE="${{ github.event.pull_request.title }}"
```

#### Validation

An attacker-controlled metadata field reaches privileged Bash before parsing; a later safe env mapping does not protect these steps.

Validation method: static source/control/sink trace

**Merged job has write permissions** — `.github/workflows/release-on-pr-merge.yml:13-16`

The shell runs in a privileged post-merge job.

```yaml
if: github.event.pull_request.merged == true\npermissions:\n  contents: write\n  pull-requests: write
```

**Checkout carries a PAT or write token** — `.github/workflows/release-on-pr-merge.yml:24-29`

The optional PAT can broaden impact.

```yaml
token: ${{ secrets.MY_RELEASE_PAT || secrets.GITHUB_TOKEN }}
```

**Raw title is injected into Bash** — `.github/workflows/release-on-pr-merge.yml:31-47`

Quotes, substitutions, newlines, and metacharacters become shell syntax.

```yaml
echo "PR 标题: ${{ github.event.pull_request.title }}"\nTITLE="${{ github.event.pull_request.title }}"
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

PR title -\> expression interpolation -\> Bash parse -\> release runner -\> repository/release APIs

**Merged job has write permissions** — `.github/workflows/release-on-pr-merge.yml:13-16`

The shell runs in a privileged post-merge job.

```yaml
if: github.event.pull_request.merged == true\npermissions:\n  contents: write\n  pull-requests: write
```

**Checkout carries a PAT or write token** — `.github/workflows/release-on-pr-merge.yml:24-29`

The optional PAT can broaden impact.

```yaml
token: ${{ secrets.MY_RELEASE_PAT || secrets.GITHUB_TOKEN }}
```

**Raw title is injected into Bash** — `.github/workflows/release-on-pr-merge.yml:31-47`

Quotes, substitutions, newlines, and metacharacters become shell syntax.

```yaml
echo "PR 标题: ${{ github.event.pull_request.title }}"\nTITLE="${{ github.event.pull_request.title }}"
```

#### Reachability

The attacker needs a PR accepted and merged; title content is otherwise fully controlled.

#### Severity

**High** — A merged PR author can turn metadata into arbitrary commands in a release job that can modify repository contents and release assets.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** high
- **Why:** Repository contents, releases, assets, comments, and available secrets can be modified or read.

Likelihood assessment:
- **Level:** medium
- **Why:** Merge approval is external, but the injection is deterministic after merge.

#### Remediation

Pass every event-derived field through step-level env variables/files and reference only quoted shell variables; remove MY_RELEASE_PAT unless necessary, minimize permissions, and separate analysis from publishing.

Tests:
- Exercise titles containing quotes, substitutions, semicolons, newlines, and GITHUB_OUTPUT syntax; verify no extra command executes.
- Audit token scopes.

Preventive controls:
- Treat all GitHub event fields as untrusted at shell boundaries.
- Review privileged workflows for env/file handoff.

<a id="finding-3"></a>

### [3] Password authentication is not enforced as a server-side authorization gate

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | The login transition, sole marker consumer, and absence of gameplay gates were confirmed in source and symbol searches. |
| Category | authentication-and-authorization |
| CWE | CWE-306 |
| Affected lines | src/main/java/firefly520/fireflymc/ModEventHandler.java:44-57, src/main/java/firefly520/fireflymc/ModEventHandler.java:63-72, src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java:112-124 |

#### Summary

A multiplayer player remains in the normal play phase while password verification is only a cooperative client prompt; pending players are not denied gameplay, chat, inventory, interaction, or command operations.

#### Root Cause

Pending authentication is an in-memory marker plus invulnerability flag; no authoritative operation checks it, so a modified client can act as the claimed identity.

**Login starts a prompt but only grants invulnerability** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:44-57`

The server clears the marker, enters normal play, and relies on the client prompt.

```java
ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(playerUuid);\nserverPlayer.setInvulnerable(true);\nPlayerPasswordManager.getInstance().startVerification(serverPlayer);
```

**Marker is consumed only for join completion** — `src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java:112-124`

The marker controls invulnerability/starter-kit completion, not gameplay authorization.

```java
boolean passwordOk = !passwordRequired || PASSWORD_VERIFIED_PLAYERS.getOrDefault(playerUuid, false);\nif (rulesConfirmed && passwordOk) { serverPlayer.setInvulnerable(false); }
```

**Timeout removes protection instead of failing closed** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:63-72`

The timeout removes protection without disconnecting or revoking capabilities.

```java
if (player != null && !ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(playerUuid, false)) { player.setInvulnerable(false); }
```

#### Validation

The only PASSWORD_VERIFIED_PLAYERS consumer is tryCompleteJoin; no movement, interaction, inventory, chat, command, or custom-payload gate was found.

Validation method: static source/control/sink trace

**Login starts a prompt but only grants invulnerability** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:44-57`

The server clears the marker, enters normal play, and relies on the client prompt.

```java
ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(playerUuid);\nserverPlayer.setInvulnerable(true);\nPlayerPasswordManager.getInstance().startVerification(serverPlayer);
```

**Marker is consumed only for join completion** — `src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java:112-124`

The marker controls invulnerability/starter-kit completion, not gameplay authorization.

```java
boolean passwordOk = !passwordRequired || PASSWORD_VERIFIED_PLAYERS.getOrDefault(playerUuid, false);\nif (rulesConfirmed && passwordOk) { serverPlayer.setInvulnerable(false); }
```

**Timeout removes protection instead of failing closed** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:63-72`

The timeout removes protection without disconnecting or revoking capabilities.

```java
if (player != null && !ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(playerUuid, false)) { player.setInvulnerable(false); }
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

login -\> pending marker -\> normal packet/command handlers -\> world/chat/inventory effects

**Login starts a prompt but only grants invulnerability** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:44-57`

The server clears the marker, enters normal play, and relies on the client prompt.

```java
ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(playerUuid);\nserverPlayer.setInvulnerable(true);\nPlayerPasswordManager.getInstance().startVerification(serverPlayer);
```

**Marker is consumed only for join completion** — `src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java:112-124`

The marker controls invulnerability/starter-kit completion, not gameplay authorization.

```java
boolean passwordOk = !passwordRequired || PASSWORD_VERIFIED_PLAYERS.getOrDefault(playerUuid, false);\nif (rulesConfirmed && passwordOk) { serverPlayer.setInvulnerable(false); }
```

**Timeout removes protection instead of failing closed** — `src/main/java/firefly520/fireflymc/ModEventHandler.java:63-72`

The timeout removes protection without disconnecting or revoking capabilities.

```java
if (player != null && !ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(playerUuid, false)) { player.setInvulnerable(false); }
```

#### Reachability

Any client permitted to connect to a multiplayer server with authentication enabled can send the requests.

#### Severity

**High** — A remote player can impersonate an offline-mode identity and use that identity's ordinary server capabilities during the authentication window.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** high
- **Why:** World changes, inventory access, chat, and commands can occur as the victim identity.

Likelihood assessment:
- **Level:** medium
- **Why:** Only a modified client is needed; the source supplies no server gate.

#### Remediation

Keep the connection in a server-controlled login phase until verification, or enforce an authoritative pre-auth predicate on all gameplay, command, and custom-payload boundaries; fail closed and disconnect.

Tests:
- A non-cooperating protocol client attempts movement, chat, command, inventory, interaction, and payloads; all are rejected before authentication.
- The same operations succeed only after server verification.

Preventive controls:
- Review every server entry point for the pre-auth predicate.
- Treat client prompts and invulnerability as UX aids, never authorization.

<a id="finding-4"></a>

### [4] Reliable UDP P2P transport accepts unauthenticated packets and lets any sender replace the active peer

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | The codec and receive loop explicitly omit a MAC/session check and overwrite the peer from packet sources. |
| Category | network-protocol-authentication |
| CWE | CWE-345 |
| Affected lines | src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:29-106, src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:299-351, src/main/java/firefly520/fireflymc/client/relay/p2p/P2PHostBridge.java:63-70 |

#### Summary

P2P punch and binary data packets lack inbound authentication; any sender can replace peerAddress and reach payload, ACK, and FIN handling connected to the local Minecraft bridge.

#### Root Cause

The protocol has an outbound-only token and no authenticated inbound envelope. Public headers/substrings are treated as peer proof before binding and forwarding attacker-controlled bytes.

**Token is only in outbound JSON** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:29-50`

The token is serialized for outbound signaling, not verified on inbound packets.

```java
json.addProperty("type", "punch");\njson.addProperty("p2pToken", token);
```

**Binary header has no authentication tag** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:53-106`

Magic/version/length checks do not authenticate payload or control fields.

```java
buffer.putInt(streamId); buffer.putInt(seq); buffer.putInt(ack); buffer.put(flags); buffer.put(payload, 0, safeLength);
```

**Any sender becomes peer** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:299-351`

Fake punch and every binary packet replace the response destination before ACK/FIN/payload handling.

```java
} else if (text.contains("punch")) { punchedPeer = true; peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort()); }\npeerAddress = new InetSocketAddress(source.getAddress(), source.getPort());
```

**Payload reaches integrated server** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PHostBridge.java:63-70`

The bridge forwards the stream to the integrated server.

```java
Socket socket = new Socket("127.0.0.1", lanPort);
```

#### Validation

The codec, receive loop, peer overwrite, and local TCP sink form a complete unauthenticated data path.

Validation method: static source/control/sink trace

**Token is only in outbound JSON** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:29-50`

The token is serialized for outbound signaling, not verified on inbound packets.

```java
json.addProperty("type", "punch");\njson.addProperty("p2pToken", token);
```

**Binary header has no authentication tag** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:53-106`

Magic/version/length checks do not authenticate payload or control fields.

```java
buffer.putInt(streamId); buffer.putInt(seq); buffer.putInt(ack); buffer.put(flags); buffer.put(payload, 0, safeLength);
```

**Any sender becomes peer** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:299-351`

Fake punch and every binary packet replace the response destination before ACK/FIN/payload handling.

```java
} else if (text.contains("punch")) { punchedPeer = true; peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort()); }\npeerAddress = new InetSocketAddress(source.getAddress(), source.getPort());
```

**Payload reaches integrated server** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PHostBridge.java:63-70`

The bridge forwards the stream to the integrated server.

```java
Socket socket = new Socket("127.0.0.1", lanPort);
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

UDP datagram -\> header/substr check -\> peerAddress/stream state -\> local P2PHostBridge socket

**Token is only in outbound JSON** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:29-50`

The token is serialized for outbound signaling, not verified on inbound packets.

```java
json.addProperty("type", "punch");\njson.addProperty("p2pToken", token);
```

**Binary header has no authentication tag** — `src/main/java/firefly520/fireflymc/client/relay/p2p/UdpPacketCodec.java:53-106`

Magic/version/length checks do not authenticate payload or control fields.

```java
buffer.putInt(streamId); buffer.putInt(seq); buffer.putInt(ack); buffer.put(flags); buffer.put(payload, 0, safeLength);
```

**Any sender becomes peer** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:299-351`

Fake punch and every binary packet replace the response destination before ACK/FIN/payload handling.

```java
} else if (text.contains("punch")) { punchedPeer = true; peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort()); }\npeerAddress = new InetSocketAddress(source.getAddress(), source.getPort());
```

**Payload reaches integrated server** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PHostBridge.java:63-70`

The bridge forwards the stream to the integrated server.

```java
Socket socket = new Socket("127.0.0.1", lanPort);
```

#### Reachability

Any host able to send to the active P2P port can exercise the receiver; inbound token possession is not required.

#### Severity

**High** — A network attacker can inject or hijack the tunnel and feed protocol bytes to an integrated server or disrupt an active session.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** high
- **Why:** The sink permits injection, forged ACK/FIN controls, session hijack, or disconnect.

Likelihood assessment:
- **Level:** medium
- **Why:** Port reachability is deployment-dependent, but no cryptographic proof is required once reachable.

#### Remediation

Derive per-session directional keys from authenticated signaling; authenticate every packet with AEAD/HMAC over session, direction, sequence, flags, and payload; reject before state changes, bind the peer after handshake, enforce source matching and replay protection.

Tests:
- Forge/alter payload, ACK, FIN, source, or token and verify rejection without state changes.
- Replay a valid packet and verify rejection.

Preventive controls:
- Keep cryptographic-envelope and replay tests in CI.
- Do not treat magic/version/length as authentication.

<a id="finding-5"></a>

### [5] AI cooling is per-player only; no global or in-flight request budget exists

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | availability-and-cost-abuse |
| CWE | CWE-400 |
| Affected lines | src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63, src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:83-122, src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:223-248, src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:44-71, src/main/java/firefly520/fireflymc/ai/AIApiClient.java:373-425 |

#### Summary

The only trigger throttle is a UUID timestamp. Futures enter the common asynchronous pool without single-flight, global concurrency, queue, or cost limits; each provider call can last 120 seconds and retry.

#### Root Cause

The implementation confuses last-trigger cooldown with admission control and never counts outstanding futures, provider calls, queue, or aggregate cost.

**Only per-player timestamp state exists** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No global/in-flight counter exists.

```java
private static final ConcurrentHashMap<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();
```

**Ordinary chat and /ai reach the loop** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:83-122`

Ordinary players can trigger AI.

```java
Commands.literal("ai") ...\nAgenticToolLoop.run(ctx, historyManager, message, ...);
```

**Cooldown records time, not completion** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:223-248`

A new request is eligible while a prior one waits.

```java
Long lastTriggerTime = PLAYER_COOLDOWNS.get(playerId);\nPLAYER_COOLDOWNS.put(player.getUUID(), System.currentTimeMillis());
```

**Each trigger submits another future** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:44-71`

No semaphore, single-flight, bounded queue, or cancellation exists.

```java
CompletableFuture.supplyAsync(() -> AIApiClient.callAIWithFunctions(...));
```

**Provider call can remain active and retry** — `src/main/java/firefly520/fireflymc/ai/AIApiClient.java:373-425`

Slow providers extend occupancy.

```java
HttpRequest.newBuilder().timeout(Duration.ofSeconds(120))...\nfor (int attempt = 0; attempt < 3; attempt++) { ... }
```

#### Validation

Trigger access, timestamp-only cooldown, asynchronous submission, timeout, and retry behavior are explicit; no global budget is present.

Validation method: static source/control/sink trace

**Only per-player timestamp state exists** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No global/in-flight counter exists.

```java
private static final ConcurrentHashMap<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();
```

**Ordinary chat and /ai reach the loop** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:83-122`

Ordinary players can trigger AI.

```java
Commands.literal("ai") ...\nAgenticToolLoop.run(ctx, historyManager, message, ...);
```

**Cooldown records time, not completion** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:223-248`

A new request is eligible while a prior one waits.

```java
Long lastTriggerTime = PLAYER_COOLDOWNS.get(playerId);\nPLAYER_COOLDOWNS.put(player.getUUID(), System.currentTimeMillis());
```

**Each trigger submits another future** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:44-71`

No semaphore, single-flight, bounded queue, or cancellation exists.

```java
CompletableFuture.supplyAsync(() -> AIApiClient.callAIWithFunctions(...));
```

**Provider call can remain active and retry** — `src/main/java/firefly520/fireflymc/ai/AIApiClient.java:373-425`

Slow providers extend occupancy.

```java
HttpRequest.newBuilder().timeout(Duration.ofSeconds(120))...\nfor (int attempt = 0; attempt < 3; attempt++) { ... }
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

player trigger -\> timestamp cooldown -\> supplyAsync -\> long/retried HTTP request -\> shared pool/provider quota

**Only per-player timestamp state exists** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:56-63`

No global/in-flight counter exists.

```java
private static final ConcurrentHashMap<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();
```

**Ordinary chat and /ai reach the loop** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:83-122`

Ordinary players can trigger AI.

```java
Commands.literal("ai") ...\nAgenticToolLoop.run(ctx, historyManager, message, ...);
```

**Cooldown records time, not completion** — `src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:223-248`

A new request is eligible while a prior one waits.

```java
Long lastTriggerTime = PLAYER_COOLDOWNS.get(playerId);\nPLAYER_COOLDOWNS.put(player.getUUID(), System.currentTimeMillis());
```

**Each trigger submits another future** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:44-71`

No semaphore, single-flight, bounded queue, or cancellation exists.

```java
CompletableFuture.supplyAsync(() -> AIApiClient.callAIWithFunctions(...));
```

**Provider call can remain active and retry** — `src/main/java/firefly520/fireflymc/ai/AIApiClient.java:373-425`

Slow providers extend occupancy.

```java
HttpRequest.newBuilder().timeout(Duration.ofSeconds(120))...\nfor (int attempt = 0; attempt < 3; attempt++) { ... }
```

#### Reachability

AI is enabled by default and ordinary players can trigger it.

#### Severity

**Medium** — Connected players can accumulate provider requests and consume executor capacity or paid API quota.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** Server responsiveness and external API availability/cost can degrade.

Likelihood assessment:
- **Level:** high
- **Why:** Repeated triggers and no in-flight limit make accumulation likely.

#### Remediation

Add per-player single-flight and global concurrency/queue/token/cost budgets; reserve/release around all calls including proactive checks, cancel stale requests, and bound retries.

Tests:
- Use a slow provider and trigger repeatedly; verify bounded in-flight/queued work.
- Verify multiple players share one global budget.

Preventive controls:
- Treat provider calls as metered resources.
- Expose in-flight/queue metrics and apply budgets to retries.

<a id="finding-6"></a>

### [6] Release attachments are built from moving main rather than the triggering merge commit

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Both checkout refs, tag checks, and clobber upload were directly inspected. |
| Category | release-integrity |
| CWE | CWE-345, CWE-367 |
| Affected lines | .github/workflows/release-on-pr-merge.yml:24-29, .github/workflows/release-on-pr-merge.yml:371-384, .github/workflows/release-on-pr-merge.yml:408-455, .github/workflows/release-on-pr-merge.yml:457-518 |

#### Summary

Both release jobs independently checkout ref: main and the second job uploads that moving worktree to a tag selected by the first; no merge-SHA or tag-commit comparison occurs.

#### Root Cause

The pipeline uses branch names across independent jobs instead of carrying an immutable merge SHA or tag commit, then clobbers release assets from the moving build.

**First job reads mutable main** — `.github/workflows/release-on-pr-merge.yml:24-29`

Version/release decisions use whichever main commit is current.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Build job reads mutable main again** — `.github/workflows/release-on-pr-merge.yml:371-384`

The artifact can observe a different commit from the event/first job.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Tag existence is not identity** — `.github/workflows/release-on-pr-merge.yml:408-455`

No target-tag commit versus HEAD comparison is performed.

```yaml
gh release view ...\nif git rev-parse ...; then ...
```

**Moving artifact clobbers tag assets** — `.github/workflows/release-on-pr-merge.yml:457-518`

The second job publishes from its unpinned checkout.

```yaml
find build/libs -name "*.jar" -type f | head -n 1\ngh release upload ... --clobber
```

#### Validation

A later main update can be selected by the second checkout; tag/release existence checks do not bind source identity.

Validation method: static source/control/sink trace

**First job reads mutable main** — `.github/workflows/release-on-pr-merge.yml:24-29`

Version/release decisions use whichever main commit is current.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Build job reads mutable main again** — `.github/workflows/release-on-pr-merge.yml:371-384`

The artifact can observe a different commit from the event/first job.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Tag existence is not identity** — `.github/workflows/release-on-pr-merge.yml:408-455`

No target-tag commit versus HEAD comparison is performed.

```yaml
gh release view ...\nif git rev-parse ...; then ...
```

**Moving artifact clobbers tag assets** — `.github/workflows/release-on-pr-merge.yml:457-518`

The second job publishes from its unpinned checkout.

```yaml
find build/libs -name "*.jar" -type f | head -n 1\ngh release upload ... --clobber
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

merged event -\> moving-main checkouts -\> build -\> target-tag asset deletion/upload

**First job reads mutable main** — `.github/workflows/release-on-pr-merge.yml:24-29`

Version/release decisions use whichever main commit is current.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Build job reads mutable main again** — `.github/workflows/release-on-pr-merge.yml:371-384`

The artifact can observe a different commit from the event/first job.

```yaml
uses: actions/checkout@v4\nwith:\n  ref: main
```

**Tag existence is not identity** — `.github/workflows/release-on-pr-merge.yml:408-455`

No target-tag commit versus HEAD comparison is performed.

```yaml
gh release view ...\nif git rev-parse ...; then ...
```

**Moving artifact clobbers tag assets** — `.github/workflows/release-on-pr-merge.yml:457-518`

The second job publishes from its unpinned checkout.

```yaml
find build/libs -name "*.jar" -type f | head -n 1\ngh release upload ... --clobber
```

#### Reachability

Requires another main update during the workflow; branch protection/concurrency are external.

#### Severity

**Medium** — Concurrent or subsequent main changes can put an unrelated, non-reproducible binary under the target release tag.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** Consumers cannot audit or reproduce the binary from the tag and a correct asset may be overwritten.

Likelihood assessment:
- **Level:** low
- **Why:** The timing window is constrained but the source has no immutable binding.

#### Remediation

Capture merge SHA or target-tag commit, pass it to every job, verify HEAD equals it before publishing, serialize with concurrency, and select a deterministic artifact.

Tests:
- Advance main during a test and verify merge SHA is built.
- Fail on HEAD/tag mismatch and test multiple JAR outputs.

Preventive controls:
- Pin release jobs to immutable SHAs.
- Record source commit and artifact digest.

<a id="finding-7"></a>

### [7] Guest relay proxies advertised as loopback bind to all network interfaces

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | network-exposure |
| CWE | CWE-668 |
| Affected lines | src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:52-62, src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:127-139, src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java:84-93, src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java:138-150 |

#### Summary

Both guest proxies call new ServerSocket(0), which binds wildcard interfaces despite a loopback log; accept loops do not reject non-loopback clients.

#### Root Cause

The proxies rely on a loopback log and target, but the Java listener is wildcard-bound and accepts any source.

**Relay proxy is unbound but logged loopback** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:52-62`

The listener is wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**P2P proxy has same mismatch** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java:84-93`

The P2P variant is also wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("P2P Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**Accept loop trusts any socket** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:127-139`

No source check or local capability prevents another host from becoming a stream.

```java
Socket socket = serverSocket.accept();\nstreamSockets.put(streamId, socket);
```

#### Validation

Both constructors and accept paths lack an explicit bind address or remote-address check.

Validation method: static source/control/sink trace

**Relay proxy is unbound but logged loopback** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:52-62`

The listener is wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**P2P proxy has same mismatch** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java:84-93`

The P2P variant is also wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("P2P Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**Accept loop trusts any socket** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:127-139`

No source check or local capability prevents another host from becoming a stream.

```java
Socket socket = serverSocket.accept();\nstreamSockets.put(streamId, socket);
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

wildcard listener -\> arbitrary Socket -\> stream registration/relay

**Relay proxy is unbound but logged loopback** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:52-62`

The listener is wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**P2P proxy has same mismatch** — `src/main/java/firefly520/fireflymc/client/relay/p2p/P2PGuestProxy.java:84-93`

The P2P variant is also wildcard-bound.

```java
serverSocket = new ServerSocket(0);\nLOGGER.info("P2P Guest 本地代理已启动: 127.0.0.1:{}", localPort);
```

**Accept loop trusts any socket** — `src/main/java/firefly520/fireflymc/client/relay/RelayGuestProxy.java:127-139`

No source check or local capability prevents another host from becoming a stream.

```java
Socket socket = serverSocket.accept();\nstreamSockets.put(streamId, socket);
```

#### Reachability

Any network path allowed by the host firewall can reach the listener.

#### Severity

**Medium** — A LAN attacker who discovers the ephemeral port can race or consume the proxy and inject tunnel traffic.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** The attacker can race, inject, consume, or disrupt the guest relay connection.

Likelihood assessment:
- **Level:** medium
- **Why:** Local port discovery is feasible and no application check exists.

#### Remediation

Bind to InetAddress.getLoopbackAddress(), reject non-loopback peers, use a one-shot local capability, accept only the intended connection, and close after it is established.

Tests:
- Verify loopback bind on IPv4/IPv6 and reject non-loopback/racing clients.

Preventive controls:
- Review all local proxies for explicit bind addresses.
- Use firewalls only as defense in depth.

<a id="finding-8"></a>

### [8] Relay WebSocket messages and stream creation have no resource limits

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | resource-exhaustion |
| CWE | CWE-400 |
| Affected lines | src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:181-225, src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:296-299, src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:30-58, src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:103-112 |

#### Summary

Fragmented frames expand without a maximum, and every stream_open can create a local socket and cached-pool worker with a 512 KiB buffer; no aggregate quotas exist.

#### Root Cause

The relay listener trusts remote frame boundaries and stream IDs without limits; unbounded accumulators and cached stream resources permit aggregate growth.

**Text fragments have no maximum** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:181-188`

A peer controls how much data arrives before the final fragment.

```java
textAccumulator.append(data);\nif (last) { String json = textAccumulator.toString(); }
```

**Binary fragments expand and duplicate** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:194-225`

The accumulator doubles without a maximum and the frame is copied again.

```java
int newCapacity = (binaryAccumulator.position() + slice.remaining()) * 2;\nbyte[] bytes = new byte[fullData.remaining()];
```

**Every stream_open reaches host bridge** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:296-299`

Distinct remote IDs trigger resource creation without quota.

```java
case "stream_open" -> hostBridge.openStream(message.streamId());
```

**Bridge uses cached workers/sockets** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:30-58`

Each stream can allocate a worker and socket.

```java
Executors.newCachedThreadPool(...);\nprivate final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();\nSocket socket = new Socket("127.0.0.1", lanPort);
```

**Each worker retains a 512 KiB buffer** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:103-112`

Workers block while streams stay open.

```java
byte[] buffer = new byte[RELAY_BUFFER_SIZE];
```

#### Validation

Remote message size and stream cardinality reach resource allocation without a client-side quota.

Validation method: static source/control/sink trace

**Text fragments have no maximum** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:181-188`

A peer controls how much data arrives before the final fragment.

```java
textAccumulator.append(data);\nif (last) { String json = textAccumulator.toString(); }
```

**Binary fragments expand and duplicate** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:194-225`

The accumulator doubles without a maximum and the frame is copied again.

```java
int newCapacity = (binaryAccumulator.position() + slice.remaining()) * 2;\nbyte[] bytes = new byte[fullData.remaining()];
```

**Every stream_open reaches host bridge** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:296-299`

Distinct remote IDs trigger resource creation without quota.

```java
case "stream_open" -> hostBridge.openStream(message.streamId());
```

**Bridge uses cached workers/sockets** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:30-58`

Each stream can allocate a worker and socket.

```java
Executors.newCachedThreadPool(...);\nprivate final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();\nSocket socket = new Socket("127.0.0.1", lanPort);
```

**Each worker retains a 512 KiB buffer** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:103-112`

Workers block while streams stay open.

```java
byte[] buffer = new byte[RELAY_BUFFER_SIZE];
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

relay frame/control -\> accumulator or openStream -\> executor/socket/buffer

**Text fragments have no maximum** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:181-188`

A peer controls how much data arrives before the final fragment.

```java
textAccumulator.append(data);\nif (last) { String json = textAccumulator.toString(); }
```

**Binary fragments expand and duplicate** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:194-225`

The accumulator doubles without a maximum and the frame is copied again.

```java
int newCapacity = (binaryAccumulator.position() + slice.remaining()) * 2;\nbyte[] bytes = new byte[fullData.remaining()];
```

**Every stream_open reaches host bridge** — `src/main/java/firefly520/fireflymc/client/relay/RelayLobbyWebSocketClient.java:296-299`

Distinct remote IDs trigger resource creation without quota.

```java
case "stream_open" -> hostBridge.openStream(message.streamId());
```

**Bridge uses cached workers/sockets** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:30-58`

Each stream can allocate a worker and socket.

```java
Executors.newCachedThreadPool(...);\nprivate final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();\nSocket socket = new Socket("127.0.0.1", lanPort);
```

**Each worker retains a 512 KiB buffer** — `src/main/java/firefly520/fireflymc/client/relay/RelayHostBridge.java:103-112`

Workers block while streams stay open.

```java
byte[] buffer = new byte[RELAY_BUFFER_SIZE];
```

#### Reachability

Reachable after connecting to a configured relay; external service policy is not a client-side bound.

#### Severity

**Medium** — A malicious or compromised relay participant can exhaust the client or hosted world's resources.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** Heap, threads, file descriptors, and integrated-server connections can be exhausted.

Likelihood assessment:
- **Level:** medium
- **Why:** No local quota limits a controlled relay channel.

#### Remediation

Set strict frame/field byte limits, close oversized messages, avoid full-frame duplication, use bounded executors, enforce per-room/global stream quotas, validate IDs, and add idle/lifetime timeouts.

Tests:
- Oversized fragmented frames close without large allocation.
- Many stream_open controls are rejected after a fixed quota.

Preventive controls:
- Make relay limits part of the protocol contract.
- Use bounded queues/executors for remote streams.

<a id="finding-9"></a>

### [9] Unauthenticated UDP packets can grow P2P reorder and pre-registration state without bound

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | resource-exhaustion |
| CWE | CWE-400 |
| Affected lines | src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:53-54, src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:323-360, src/main/java/firefly520/fireflymc/client/relay/p2p/ReorderBuffer.java:8-30 |

#### Summary

Packet-controlled stream IDs allocate entries in unbounded maps, and arbitrary future sequence numbers are retained in a TreeMap; the per-list cap does not bound aggregate state.

#### Root Cause

Unauthenticated streamId/seq fields allocate persistent maps and byte arrays with no global cardinality, receive-window, or eviction bound.

**Transport maps are not globally bounded** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:53-54`

Both maps are keyed by packet-supplied stream IDs.

```java
private final Map<Integer, ReorderBuffer> reorderBuffers = new ConcurrentHashMap<>();\nprivate final Map<Integer, List<DecodedData>> pendingBeforeRegister = new ConcurrentHashMap<>();
```

**Stream IDs allocate state before validation** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:323-360`

The per-list cap does not cap map cardinality.

```java
reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());\npendingBeforeRegister.computeIfAbsent(decoded.streamId(), ignored -> ...);
```

**Future sequences have no window** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReorderBuffer.java:8-30`

Arbitrary gaps remain retained.

```java
private final Map<Integer, byte[]> pending = new TreeMap<>();\npending.putIfAbsent(seq, payload);
```

#### Validation

The packet fields reach persistent buffering directly; 1500-byte datagrams do not bound aggregate retained state.

Validation method: static source/control/sink trace

**Transport maps are not globally bounded** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:53-54`

Both maps are keyed by packet-supplied stream IDs.

```java
private final Map<Integer, ReorderBuffer> reorderBuffers = new ConcurrentHashMap<>();\nprivate final Map<Integer, List<DecodedData>> pendingBeforeRegister = new ConcurrentHashMap<>();
```

**Stream IDs allocate state before validation** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:323-360`

The per-list cap does not cap map cardinality.

```java
reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());\npendingBeforeRegister.computeIfAbsent(decoded.streamId(), ignored -> ...);
```

**Future sequences have no window** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReorderBuffer.java:8-30`

Arbitrary gaps remain retained.

```java
private final Map<Integer, byte[]> pending = new TreeMap<>();\npending.putIfAbsent(seq, payload);
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

UDP header -\> decodeData -\> computeIfAbsent/ReorderBuffer.accept -\> retained map entries

**Transport maps are not globally bounded** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:53-54`

Both maps are keyed by packet-supplied stream IDs.

```java
private final Map<Integer, ReorderBuffer> reorderBuffers = new ConcurrentHashMap<>();\nprivate final Map<Integer, List<DecodedData>> pendingBeforeRegister = new ConcurrentHashMap<>();
```

**Stream IDs allocate state before validation** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReliableUdpChannel.java:323-360`

The per-list cap does not cap map cardinality.

```java
reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());\npendingBeforeRegister.computeIfAbsent(decoded.streamId(), ignored -> ...);
```

**Future sequences have no window** — `src/main/java/firefly520/fireflymc/client/relay/p2p/ReorderBuffer.java:8-30`

Arbitrary gaps remain retained.

```java
private final Map<Integer, byte[]> pending = new TreeMap<>();\npending.putIfAbsent(seq, payload);
```

#### Reachability

Only public header/length checks are required.

#### Severity

**Medium** — A reachable UDP sender can exhaust client heap or CPU.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** Client heap/CPU exhaustion can freeze or terminate the client/world.

Likelihood assessment:
- **Level:** medium
- **Why:** Small datagrams and arbitrary IDs make sustained flooding feasible once the port is reachable.

#### Remediation

Authenticate before allocation, accept only negotiated IDs, cap streams and buffered bytes, enforce a receive window, reject far-future values, evict stale gaps, and close after repeated invalid packets.

Tests:
- Flood distinct IDs and far-future sequences; verify bounded state and rejection.

Preventive controls:
- Track aggregate bytes/entries, not only per-list counts.
- Fuzz stream and sequence bounds.

<a id="finding-10"></a>

### [10] Configured global AI tool permission floor is ignored

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | authorization |
| CWE | CWE-863 |
| Affected lines | src/main/java/firefly520/fireflymc/ServerConfig.java:215-218, src/main/java/firefly520/fireflymc/ai/AIConfig.java:86-88, src/main/java/firefly520/fireflymc/ai/AIChatEventHandler.java:88-92, src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:139-146, src/main/java/firefly520/fireflymc/ai/function/GetBlocksFunctionTool.java:75-78, src/main/java/firefly520/fireflymc/ai/function/SetTimeFunctionTool.java:75-78 |

#### Summary

functionsRequireOpLevel defaults to 4 and is exposed by AIConfig, but the dispatcher checks only each tool's intrinsic level; /ai has no command-level floor.

#### Root Cause

The code defines a global policy but calculates authorization from per-tool metadata alone; the omission affects advertisement and all trigger paths.

**Configuration promises a minimum of four** — `src/main/java/firefly520/fireflymc/ServerConfig.java:215-218`

The administrator-visible setting is an independent floor.

```java
aiFunctionsRequireOpLevel = builder.comment("AI函数调用所需的最低OP等级（0-4）").defineInRange("functionsRequireOpLevel", 4, 0, 4);
```

**Getter is not consumed** — `src/main/java/firefly520/fireflymc/ai/AIConfig.java:86-88`

The value is exposed but not used by execution.

```java
public static int getFunctionsRequireOpLevel() { return ServerConfig.SERVER.aiFunctionsRequireOpLevel.get(); }
```

**Dispatcher checks only tool level** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:139-146`

The global floor is never combined.

```java
if (!ctx.hasPermission(tool.getRequiredPermissionLevel())) { return FunctionCallResult.failure(...); }
```

**World-mutating tool declares level three** — `src/main/java/firefly520/fireflymc/ai/function/SetTimeFunctionTool.java:75-78`

OP3 can pass while floor remains 4.

```java
public int getRequiredPermissionLevel() { return 3; }
```

#### Validation

Configuration, unused getter, dispatcher, command registration, and representative tool levels establish the bypass.

Validation method: static source/control/sink trace

**Configuration promises a minimum of four** — `src/main/java/firefly520/fireflymc/ServerConfig.java:215-218`

The administrator-visible setting is an independent floor.

```java
aiFunctionsRequireOpLevel = builder.comment("AI函数调用所需的最低OP等级（0-4）").defineInRange("functionsRequireOpLevel", 4, 0, 4);
```

**Getter is not consumed** — `src/main/java/firefly520/fireflymc/ai/AIConfig.java:86-88`

The value is exposed but not used by execution.

```java
public static int getFunctionsRequireOpLevel() { return ServerConfig.SERVER.aiFunctionsRequireOpLevel.get(); }
```

**Dispatcher checks only tool level** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:139-146`

The global floor is never combined.

```java
if (!ctx.hasPermission(tool.getRequiredPermissionLevel())) { return FunctionCallResult.failure(...); }
```

**World-mutating tool declares level three** — `src/main/java/firefly520/fireflymc/ai/function/SetTimeFunctionTool.java:75-78`

OP3 can pass while floor remains 4.

```java
public int getRequiredPermissionLevel() { return 3; }
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

/ai/chat -\> AgenticToolLoop.executeOne -\> intrinsic level check -\> world/info tool

**Configuration promises a minimum of four** — `src/main/java/firefly520/fireflymc/ServerConfig.java:215-218`

The administrator-visible setting is an independent floor.

```java
aiFunctionsRequireOpLevel = builder.comment("AI函数调用所需的最低OP等级（0-4）").defineInRange("functionsRequireOpLevel", 4, 0, 4);
```

**Getter is not consumed** — `src/main/java/firefly520/fireflymc/ai/AIConfig.java:86-88`

The value is exposed but not used by execution.

```java
public static int getFunctionsRequireOpLevel() { return ServerConfig.SERVER.aiFunctionsRequireOpLevel.get(); }
```

**Dispatcher checks only tool level** — `src/main/java/firefly520/fireflymc/ai/AgenticToolLoop.java:139-146`

The global floor is never combined.

```java
if (!ctx.hasPermission(tool.getRequiredPermissionLevel())) { return FunctionCallResult.failure(...); }
```

**World-mutating tool declares level three** — `src/main/java/firefly520/fireflymc/ai/function/SetTimeFunctionTool.java:75-78`

OP3 can pass while floor remains 4.

```java
public int getRequiredPermissionLevel() { return 3; }
```

#### Reachability

AI/functions are enabled by default and /ai has no requires predicate.

#### Severity

**Medium** — OP3 or ordinary players can reach tools below the configured global floor, including world mutation or information tools.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** World state or server information can change/disclose contrary to administrator policy.

Likelihood assessment:
- **Level:** medium
- **Why:** The attacker can prompt the model; the missing check is deterministic.

#### Remediation

Compute max(global floor, tool level) in dispatch and tool advertisement; apply it to all chat/command/wake-word/proactive/console paths and test floors 0–4.

Tests:
- With floor 4 verify OP0–OP3 cannot execute/receive tools and OP4 can.
- Test every tool and trigger path under floors 0–4.

Preventive controls:
- Centralize effective-permission calculation.
- Treat configuration promises as security policy.

<a id="finding-11"></a>

### [11] Password brute-force lockout is enforced only by the untrusted client

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | authentication-rate-limiting |
| CWE | CWE-307 |
| Affected lines | src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:160-190, src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:226-236, src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java:81-90, src/main/java/firefly520/fireflymc/mixin/ConnectScreenMixin.java:50-69 |

#### Summary

Attempt exhaustion disconnects the player and sends a lockout payload, but each reconnect receives a fresh AuthSession; persistence and reconnect blocking are client-side.

#### Root Cause

Lockout is a client feature; the server map holds only the current AuthSession and deletes it on failure.

**Each login gets a full attempt budget** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:160-171`

Reconnects replace prior state with a new counter.

```java
int maxAttempts = ServerConfig.SERVER.playerAuthMaxAttempts.get();\nAuthSession session = new AuthSession(isFirstTime, maxAttempts);
```

**Server removes session without expiry** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:226-236`

No server lockout timestamp or failure record is written.

```java
session.remainingAttempts--;\nPacketDistributor.sendToPlayer(player, new AuthLockoutPayload(lockoutMinutes));\npendingSessions.remove(uuid);
```

**Client persists the lockout** — `src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java:81-90`

A modified client can omit this handler.

```java
ClientAuthLockoutManager.getInstance().recordLockout(key, payload.lockoutMinutes());
```

**Stock client blocks its own reconnect** — `src/main/java/firefly520/fireflymc/mixin/ConnectScreenMixin.java:50-69`

Client interception is not authoritative.

```java
if (remaining > 0) { ci.cancel(); }
```

#### Validation

Failure was traced to disconnect/client persistence and compared with the next login path; no server lockout lookup exists.

Validation method: static source/control/sink trace

**Each login gets a full attempt budget** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:160-171`

Reconnects replace prior state with a new counter.

```java
int maxAttempts = ServerConfig.SERVER.playerAuthMaxAttempts.get();\nAuthSession session = new AuthSession(isFirstTime, maxAttempts);
```

**Server removes session without expiry** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:226-236`

No server lockout timestamp or failure record is written.

```java
session.remainingAttempts--;\nPacketDistributor.sendToPlayer(player, new AuthLockoutPayload(lockoutMinutes));\npendingSessions.remove(uuid);
```

**Client persists the lockout** — `src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java:81-90`

A modified client can omit this handler.

```java
ClientAuthLockoutManager.getInstance().recordLockout(key, payload.lockoutMinutes());
```

**Stock client blocks its own reconnect** — `src/main/java/firefly520/fireflymc/mixin/ConnectScreenMixin.java:50-69`

Client interception is not authoritative.

```java
if (remaining > 0) { ci.cancel(); }
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

wrong submissions -\> remove session -\> reconnect -\> fresh AuthSession

**Each login gets a full attempt budget** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:160-171`

Reconnects replace prior state with a new counter.

```java
int maxAttempts = ServerConfig.SERVER.playerAuthMaxAttempts.get();\nAuthSession session = new AuthSession(isFirstTime, maxAttempts);
```

**Server removes session without expiry** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:226-236`

No server lockout timestamp or failure record is written.

```java
session.remainingAttempts--;\nPacketDistributor.sendToPlayer(player, new AuthLockoutPayload(lockoutMinutes));\npendingSessions.remove(uuid);
```

**Client persists the lockout** — `src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java:81-90`

A modified client can omit this handler.

```java
ClientAuthLockoutManager.getInstance().recordLockout(key, payload.lockoutMinutes());
```

**Stock client blocks its own reconnect** — `src/main/java/firefly520/fireflymc/mixin/ConnectScreenMixin.java:50-69`

Client interception is not authoritative.

```java
if (remaining > 0) { ci.cancel(); }
```

#### Reachability

Any client able to connect can repeat the sequence.

#### Severity

**Medium** — A modified client can repeatedly reconnect and obtain new attempt budgets, defeating the configured lockout.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** medium
- **Why:** Online guessing remains possible against offline-mode accounts.

Likelihood assessment:
- **Level:** medium
- **Why:** The source supplies no server enforcement and reconnect is normal.

#### Remediation

Persist server-side failure/lockout state keyed by identity with secondary IP throttling; check it before creating sessions and submissions, persist across restart, delay and log failures.

Tests:
- Reconnect with a client that ignores AuthLockoutPayload and verify server rejection/delay.
- Restart during lockout and verify expiry persists.

Preventive controls:
- Make throttles authoritative on the server.
- Keep client lockout only as UX.

<a id="finding-12"></a>

### [12] Player PINs are stored using a single fast SHA-256 digest

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Confirmed by static source review. |
| Category | credential-storage |
| CWE | CWE-916 |
| Affected lines | src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:31-40, src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:91-105, src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:279-285 |

#### Summary

Passwords may be six decimal digits and are stored as one SHA-256(salt + password) computation; the salt prevents precomputation but adds no per-guess work.

#### Root Cause

A fast general-purpose digest protects low-entropy PINs only with a salt, not a password-KDF cost.

**Policy permits six-digit numeric PINs** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:279-285`

The minimum secret space is one million possibilities.

```java
if (password == null || password.length() < 6 || password.length() > 32) return false;\nif (c < '0' || c > '9') return false;
```

**Hashing performs one SHA-256 digest** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:91-105`

The random salt has no work factor.

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");\nbyte[] hash = digest.digest((salt + password).getBytes(StandardCharsets.UTF_8));
```

**Salt and hash are persisted** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:115-124`

A disclosed record supports offline guessing.

```java
String hash = hashPassword(salt, rawPassword);\nPasswordRecord record = new PasswordRecord(hash, salt, ...);
```

#### Validation

Accepted entropy, hash computation, and persisted record were traced; no iteration/memory/parallelism parameter exists.

Validation method: static source/control/sink trace

**Policy permits six-digit numeric PINs** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:279-285`

The minimum secret space is one million possibilities.

```java
if (password == null || password.length() < 6 || password.length() > 32) return false;\nif (c < '0' || c > '9') return false;
```

**Hashing performs one SHA-256 digest** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:91-105`

The random salt has no work factor.

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");\nbyte[] hash = digest.digest((salt + password).getBytes(StandardCharsets.UTF_8));
```

**Salt and hash are persisted** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:115-124`

A disclosed record supports offline guessing.

```java
String hash = hashPassword(salt, rawPassword);\nPasswordRecord record = new PasswordRecord(hash, salt, ...);
```

Assertions:
- The attacker-controlled input reaches the described state or sink.
- The expected security control is absent at that boundary.

Limitations:
- No live application or external-service reproduction was run.

#### Dataflow

password file -\> salt/hash -\> offline guesses -\> verifyPassword

**Policy permits six-digit numeric PINs** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:279-285`

The minimum secret space is one million possibilities.

```java
if (password == null || password.length() < 6 || password.length() > 32) return false;\nif (c < '0' || c > '9') return false;
```

**Hashing performs one SHA-256 digest** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:91-105`

The random salt has no work factor.

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");\nbyte[] hash = digest.digest((salt + password).getBytes(StandardCharsets.UTF_8));
```

**Salt and hash are persisted** — `src/main/java/firefly520/fireflymc/auth/PlayerPasswordManager.java:115-124`

A disclosed record supports offline guessing.

```java
String hash = hashPassword(salt, rawPassword);\nPasswordRecord record = new PasswordRecord(hash, salt, ...);
```

#### Reachability

Requires a separate file/backup disclosure; no network access to the hash is needed afterward.

#### Severity

**Low** — An attacker with the password file can cheaply exhaust the six-digit space and impersonate accounts.

Additional runtime or deployment evidence could raise or lower this severity.

Impact assessment:
- **Level:** low
- **Why:** Recovered PIN enables offline-mode account impersonation and possible password reuse.

Likelihood assessment:
- **Level:** low
- **Why:** File disclosure is a separate prerequisite, so likelihood is low.

#### Remediation

Use Argon2id, scrypt, bcrypt, or calibrated PBKDF2 with per-record parameters; migrate on successful login and permit stronger non-numeric passphrases.

Tests:
- Benchmark/encode KDF parameters and migrate a legacy record after successful login.

Preventive controls:
- Protect password files/backups with least privilege.
- Review credential storage against a password-KDF standard.

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| multiplayer password authentication and lockout | authentication | Reported | Login state, session lifecycle, client lockout, and password KDF were reviewed; three findings reported. |
| P2P UDP protocol and stream buffering | network-authentication-and-availability | Reported | Codec, receive loop, peer binding, reorder state, and local bridge were reviewed; two findings reported. |
| relay WebSocket and local proxies | remote-resource-and-local-network-exposure | Reported | Frame accumulation, stream_open, host bridge, and both proxies were reviewed; two findings reported. |
| AI authorization, history, and provider admission | authorization-and-availability | Reported | Configuration, triggers, history, dispatcher, context authority, and provider calls were reviewed; three findings reported. |
| CI/CD release workflow | command-execution-and-release-integrity | Reported | Permissions, event interpolation, checkout refs, tag checks, asset deletion, and upload were reviewed; two findings reported. |
| music and update HTTP paths | external-http-and-cache | No issue found | Identifiers and cache paths were validated; HTTP CDN fallback remains hardening without a demonstrated code or command sink. |
| custom payload codecs and general Java sinks | injection-and-deserialization | No issue found | No SQL/NoSQL, XML/XXE, native deserialization, Runtime.exec/ProcessBuilder, script engine, or dynamic code-generation sink was established. |

## Open Questions And Follow Up

- What authentication/quotas do the external relay and event services enforce?
- What GitHub branch protection, concurrency, environment approval, and PAT scopes apply?
- What is the complete CI JAR output set under the target build environment?
- External service authentication and quotas are unresolved.
  - Follow-up prompt: Review deferred unit external-relay-auth-policy and close its stated proof gap. Paths: src/main/java/firefly520/fireflymc/client/relay.
- Inbound maintenance/control authorization depends on an external event-service contract.
  - Follow-up prompt: Review deferred unit event-websocket-control-auth and close its stated proof gap. Paths: src/main/java/firefly520/fireflymc/client.
- Unreviewed framework hooks could add gates outside product source; none was established in reviewed paths.
  - Follow-up prompt: Review deferred unit neo-forge-preauth-gating and close its stated proof gap. Paths: src/main/java/firefly520/fireflymc.
- find build/libs | head -n 1 lacks artifact identity checks, but multiple runtime JARs were not proven; retained as an amplifier, not a separate finding.
  - Follow-up prompt: Review deferred unit release-first-arbitrary-jar and close its stated proof gap. Paths: .github/workflows/release-on-pr-merge.yml, build.gradle.
