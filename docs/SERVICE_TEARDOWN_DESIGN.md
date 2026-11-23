# VPN service teardown: lifecycle findings and safe refactor boundary

Status: investigation only; no runtime teardown behavior is changed by this document.

## Scope inspected

Branch: `feature/aether-pro-branding`

Relevant source at the time of inspection:

- `AetherVpnService.kt` blob: `d4c7017240636634a8f5c7761a2814baedcabc39`
- `AetherServiceIntegrationTest.kt` blob: `a4b3c31082bfe1a2ca1d100904638f4ade4751d0`
- `AetherProcess.kt` blob: `c025e9b1ae49bb95f24ba87fb89a3e1b0169f586`
- `XrayProcess.kt` blob: `a00e5b7b36dbd650eaeb0fdd3b24168af64d2f13`
- `AetherApp.kt` blob: `151d679788d61d4af13da168d01c9666ab93b804`

## Observed lifecycle paths

1. `stopEverything()` cancels `runJob`, increments `stopGeneration`, then launches teardown in the service-owned `scope`. It waits for a previous `stopJob`, checks its generation, performs native cleanup and UI finalization, then joins the cancelled run.
2. `enterLockdown()` has a similar stop-job sequence, but retains/builds a blackhole TUN after stopping forwarding.
3. `startTunnel()` supersedes stale stop finalization and waits for the previous stop before it cancels/cleans/joins a previous run and starts a new connection.
4. `onDestroy()` cancels `runJob`, calls `cleanupNativeOnly()` and `stopCustom()` synchronously, then cancels the service scope. It does not await `runJob` or an in-flight `stopJob`.
5. `onRevoke()` enters the ordinary stop path.
6. The uncaught-exception handler calls `killNativesFromCrashHandler()`, which synchronously invokes native cleanup and custom-process cleanup on the crashing thread.
7. Smart Plus race lanes own temporary `AetherProcess` instances. Their coroutine `finally` blocks stop/remove them, and `runRace()` performs a final stop/clear pass. Cancellation of the parent race waits for child coroutine completion, but `onDestroy()` currently does not join that parent run.

## Why a thin coordinator is not a safe fix

A pure Kotlin generation/Mutex helper could prove ordering rules in isolation, but it would not establish that all actual resource acquisition and release is serialized. In particular:

- The service has more mutable native handles than the session engine: TUN descriptor, hev tunnel, forwarding bridge, external transport, Tor front, Xray process, custom session, and Smart Plus lane processes.
- Native starts and stops are currently called from multiple suspend and synchronous methods. Protecting only cleanup entry points leaves a window in which a cancelled connect coroutine can still create a resource after teardown.
- `AetherProcess.stop()` and `XrayProcess.stop()` perform blocking process waits (250 ms and 400 ms grace periods respectively) and additional cleanup. The hev stop path crosses JNI. Their full worst-case latency is not established here.
- The service coroutine scope is cancelled at the end of `onDestroy()`; launching cleanup into that scope does not give cleanup an independent lifetime.
- Blocking the main thread in `onDestroy()` to join jobs is not acceptable. Conversely, returning immediately after dispatching cleanup to an executor does not guarantee that Android will keep the app process alive long enough to run it.
- Crash-handler cleanup is a separate synchronous entry point and can overlap ordinary teardown unless it shares the same resource-ownership protocol.

Therefore, adding a `Mutex`, a generation counter, or a test-only `ServiceTeardownCoordinator` without changing resource ownership would provide misleading confidence. No such runtime change is made in this step.

## Required design boundary for a future implementation

The refactor should establish one process-level owner for native session resources, independent of an individual Service instance's coroutine scope. The owner must serialize both acquisition and release, not merely serialize cleanup calls.

A viable design must explicitly answer all of the following before implementation:

- How does every resource-start operation acquire a valid session lease and atomically reject starts after terminal destruction has been requested?
- How are in-flight native starts accounted for so final teardown cannot miss a resource published just after it took its cleanup snapshot?
- How do stop, lockdown, reconnect, service destruction, VPN revoke, and crash handling share one ownership state machine while preserving their different desired outcomes (notably lockdown retains a blackhole TUN)?
- Which work is safe on the main thread, which must run on a dedicated process-level executor, and what bounded completion guarantee exists before `onDestroy()` returns?
- What is the fallback if the process is killed before asynchronous cleanup completes? Child processes must not be assumed to die with their parent.
- How are Smart Plus lane processes registered and drained by the same owner, without racing their own `finally` cleanup?
- How are identity sealing and Psiphon orphan reaping coordinated with the last engine and Xray teardown?

A possible direction is an application-process resource owner with a serialized command/state machine and explicit session leases. It must not be adopted until the Android destruction/crash constraints and all native call sites have been mapped. A process-level executor alone is not sufficient evidence of completion.

## Minimum verification before wiring into the Service

Pure JVM tests can verify the coordinator's state machine and deterministic ordering, but cannot prove Android Service destruction behavior. Tests should include:

- stop racing with a new connect: old generation cannot publish or start resources after the new lease is granted;
- destruction racing with a blocked/in-flight resource start: the resource is either rejected or registered and subsequently stopped;
- duplicate stop/destruction/crash cleanup: each owned handle is stopped safely once, with idempotent handling of already-stopped handles;
- Smart Plus cancellation: every lane is registered, stopped, and removed before session ownership is released;
- lockdown transition: forwarding is stopped while the blackhole TUN remains owned and active; a failed TUN establish is never reported as active lockdown;
- failure injection at every cleanup stage: later resources still receive cleanup;
- Robolectric/instrumented lifecycle coverage for service destruction and revoke, plus a process-level test for child-process survival/cleanup where feasible.

The existing integration test uses Robolectric for selected service logic, but does not currently exercise `onDestroy()`, real native processes, JNI shutdown, or process death. Passing pure coordinator tests must not be reported as proof of those behaviors.
