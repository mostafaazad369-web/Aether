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


## Resource acquisition inventory (follow-up inspection)

The current service has a split ownership model: some resources are held in instance fields, some are process/static singletons, and some are local variables until startup returns. The last category is the most important race to close.

| Resource | Acquisition / publication observed | Release observed | Ownership concern |
|---|---|---|---|
| Session `AetherProcess` | Several paths use `engine = AetherProcess(...).also { it.start(...) }` (including the ordinary connect and custom paths) | `cleanupNativeOnlyImpl()`, fallback paths | The assignment to `engine` occurs only after `start()` returns. If destruction overlaps a blocking/partially completed start, cleanup may see `engine == null` while the local process object has already spawned a child. |
| Smart Plus lane engines | Local `AetherProcess` is created in `runLane()` and added to `raceEngines` before `engine.start(profile)` | lane `finally`, plus `runRace()` final sweep | The current race path correctly registers before spawn; preserve this ordering when moving it into a shared owner. Its registry is still instance-scoped and must be drained by destruction. Parent cancellation only helps while the coroutine remains alive and is joined. |
| External transport | Factory result is assigned to `externalTransport` before `transport.start()` | `cleanupNativeOnlyImpl()` and explicit fallback | Better publication order than the engine expression, but start/stop concurrency and transport-specific guarantees still need auditing. |
| Xray | A runner is passed into `startXray()`; `runner.start()` executes on IO; `xray` is a service field | `stopXrayOnly()` under `xrayLock`; watcher may restart | The runner's exact publication point must be traced at each caller. The watcher can restart a process, so invalidating/cancelling it must be part of the same ownership transaction as stop. |
| hev tunnel | `HevTunnel.start(config, fd)` then `tunnelStarted = true` | `HevTunnel.stop()` when `tunnelStarted` | A failure or teardown between native start and setting the flag can leave native state unrepresented by the boolean. |
| userspace TUN bridge | Constructed, `bridge.start()`, then assigned to `tunBridge` | `tunBridge.stop()` and clear | Same publish-after-start window: teardown may not see a bridge whose start has completed or is in progress. |
| TUN descriptor | `establishTun()` assigns the service's `tun` field (and lockdown separately rebuilds it) | `tun.close()` | Descriptor lifetime is coupled to hev/bridge; close must not race an in-flight bridge start or native reader. |
| LAN share bridge | Static `ShareBridge.start/startSync` | static `ShareBridge.stop()` | Process-global state, not naturally scoped to a Service instance; must be covered by the process owner. |
| Tor SOCKS front | Static `TorSocksFront.start()` | static `TorSocksFront.stop()` | Process-global listener; ordering relative to engine stop matters. |
| Custom session | `CustomSession.set(...)` and service `customSelection` | `CustomSession.set(null)` in `stopCustom()` | Metadata/session publication must not outlive the actual Xray/chain processes. |

The table is an initial call-site map, not proof of each dependency's internal thread safety. Before coding, inspect each implementation (especially `ExternalTransport`, `SocksTunBridge`, `HevTunnel`, `ShareBridge`, and `TorSocksFront`) and all start call sites, then define the owner API around their actual contracts.

## Concrete race demonstrated by assignment order

At multiple session-engine call sites the service assigns the field using an expression equivalent to:

```kotlin
engine = AetherProcess(nativeLibraryDir, filesDir).also { it.start(profile) }
```

Kotlin evaluates the right-hand side completely before assigning the field. `AetherProcess.start()` calls `ProcessBuilder.start()` and only then stores its `Process` handle inside that local `AetherProcess` instance. If `onDestroy()` runs while startup is in progress, service cleanup can observe `engine == null`; the local instance is not yet published to the service and its child process may survive. A generation check after `start()` would prevent a stale connection from being announced, but would not by itself stop that unpublished child.

The owner refactor therefore needs a two-phase acquisition contract: register an owned resource handle before starting it, and make start completion/rollback part of that same ownership transaction. A plain post-start registration is insufficient.

Similarly, `HevTunnel.start()` precedes `tunnelStarted = true`, and `SocksTunBridge.start()` precedes assignment to `tunBridge`. Those are additional start-before-publication windows. A boolean or nullable field cannot reliably represent a resource whose native start is in flight.

## Revised implementation decision

Do not introduce a coordinator that merely serializes calls to `cleanupNativeOnlyImpl()`. The first implementation slice, once the dependency contracts above are inspected, should be an ownership API with explicit states such as registered / starting / active / stopping / stopped, plus a generation/session lease. Resource registration must happen before any operation that can spawn a process, bind a listener, or enter JNI. The API must also support the lockdown TUN as a deliberately retained resource while forwarding resources are released.

Until that API can own every listed resource and the crash path, leave the runtime teardown code unchanged. This avoids creating a false sense of safety from tests that only exercise a standalone mutex or generation counter.


## Dependency-contract inspection (2026-11-23)

The implementations named in the inventory were inspected on this branch. This narrows the design, but does not yet make a runtime owner safe to introduce.

### Hev JNI facade

HevTunnel is a process-wide Kotlin object with a volatile Boolean running. start() checks the flag, enters TProxyStartService(configPath, tunFd), and sets the flag only after JNI reports success. stop() checks the same flag, calls TProxyStopService(), then clears it. There is no common monitor/lock around start and stop, and the Boolean cannot represent a start currently inside JNI. Two concurrent starts can both pass the check; a stop concurrent with a start can observe running == false and return while JNI startup is still in flight. The service also sets tunnelStarted = true only after HevTunnel.start() returns. The facade's stop is idempotent at the Boolean level, but the native stop completion/boundedness contract is not established by this Kotlin wrapper.

### Userspace TUN bridge

SocksTunBridge is an instance resource. start() uses AtomicBoolean.getAndSet(true) and then starts writer and reader threads; the service publishes the instance to tunBridge only after start() returns. The read thread can block in TUN input and the writer polls a queue. stop() flips the flag, closes TCP/UDP sessions, shuts down its cached executor and interrupts the two threads, but it does not join them, await executor termination, or close the supplied ParcelFileDescriptor. Consequently stop is a request, not proof that all bridge work has exited. The publication race remains, and the descriptor must remain owned separately by the service until bridge readers are known to have stopped.

### External transport and Psiphon front

ExternalTransport defines a suspending start(), isAlive(), and synchronous stop(); the factory currently creates PsiphonTransport. The service stores the transport in externalTransport before awaiting start(), which is the correct publication order for this object. However, PsiphonTransport.start() is a long-running readiness wait (up to its configured 200-second timeout), registers PsiphonEngine.onServerChanged, and eventually starts the process-global PsiphonSocksFront. Its stop() clears that callback and stops the front, but does not itself cancel or join a concurrently executing start(). A cancellation/teardown can therefore overlap the readiness coroutine unless the future owner tracks in-flight start completion. The Psiphon front itself is a large process-global component with synchronized start/stop/retarget entry points and atomic running state; it owns listener, worker pools, UDP associations and background threads. Its own synchronization is stronger than the small Tor front, but shutdown of worker pools is still shutdownNow() rather than an explicit drain/join contract.

### Share bridge

ShareBridge is a process-global object. startSync() serializes setup under synchronized(this), binds loopback listeners, and starts accept loops. stop() first clears the externally visible active/LAN flags, increments a session token under the object monitor, then performs closeAllLocked() asynchronously on a daemon thread only if that token is still current. This protects a newer startSync() from being closed by an older queued stop, which is a useful generation pattern to preserve. It does mean stop() returns before listener closure is complete; the resource owner must track the stop operation, not infer completion from return. The asynchronous start() wrapper has a similar queued-start consideration. The bridge starts per-connection daemon threads; closeAllLocked() closes listeners but does not synchronously join every accepted client thread. The static bridge's lifecycle cannot be treated as a simple service-field resource.

### Tor SOCKS front

TorSocksFront is also a process-global object, but unlike PsiphonSocksFront its public start() and stop() are not synchronized. start() may call stop(), mutates proxy host/port and counters, binds a ServerSocket, publishes listener/pool, sets running, and submits an accept loop. stop() clears running, closes the listener, calls shutdownNow() on the pool and clears association tracking. Concurrent start/stop calls can interleave these assignments and may close or overwrite a newer listener/pool. The caller's current ordering (stop the front before stopping its upstream engine) is intentional and must remain a dependency edge in the owner. This front needs its own serialized lifecycle contract or must be exclusively serialized by the owner.

### Child process wrappers and Xray watcher

AetherProcess.start() calls ProcessBuilder.start() and then stores the returned process in its instance field; stop() clears that field and waits up to 250 ms before force-killing. XrayProcess.start() first calls stop(), writes a config file, starts the child, and only then publishes its process; stop() waits up to 400 ms before force-killing and deletes the config file. Neither wrapper serializes its own concurrent start/stop operations. The service's Xray watcher also has a generation token and lock, but the runner's public lifecycle is not intrinsically thread-safe. The owner must register the wrapper before start() and serialize watcher restarts with teardown; a generation check alone cannot reap an unpublished child.

### Remaining unknowns and dependency edges

- CustomSession is imported and used by AetherVpnService, but no CustomSession source file appears in the branch tree. Its backing storage, static/process scope, and thread-safety therefore remain unverified; do not assume it is merely UI metadata until its dependency/source origin is located.
- TProxyService is the JNI boundary behind HevTunnel. Its native stop semantics and whether it waits for the native event loop to exit need inspection in the JNI/C implementation.
- The service cleanup order is deliberate: stop sharing first; stop the userspace/hev forwarder; stop the external transport; stop TorSocksFront before the engine that provides its upstream; stop the engine; close the TUN descriptor. Lockdown cleanup uses the forwarding-only path and intentionally retains the TUN. These are dependency edges, not arbitrary list ordering.
- ShareBridge.stop() is asynchronous, and SocksTunBridge.stop() / TorSocksFront.stop() request shutdown without joining all workers. The current code does not establish a single bounded "all owned work has exited" barrier.
- PsiphonSocksFront and ShareBridge spawn daemon threads that are not represented by the service's coroutine jobs. A future owner needs explicit drain semantics or documented process-death fallback for those threads and their sockets.

### Updated implementation decision

Do not add runtime ownership code yet. The inspection confirms that the resource registry must represent both the resource handle and its in-flight lifecycle operation, and must preserve dependency ordering. A safe first slice should not be a generic AutoCloseable bag: it needs resource-specific start/stop adapters, session leases, a start-in-flight barrier, and a teardown result that distinguishes "stop requested" from "workers/process/native loop confirmed stopped". It must also model the lockdown TUN as retained while forwarding resources drain. Before implementation, inspect TProxyService JNI/C shutdown and resolve the CustomSession source, then write integration tests around the real service lifecycle and fakeable resource adapters.
