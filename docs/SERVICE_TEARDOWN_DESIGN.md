# VPN service teardown: lifecycle findings and safe refactor boundary

Status: the HEV lifecycle patch, three-ABI native build, Kotlin compilation, and JVM unit tests are verified in GitHub Actions run 45449752505; native runtime/concurrency and Android Service teardown verification remain outstanding.

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

- CustomSession is defined in custom/CustomConfigStore.kt as a process-global object backed by MutableStateFlow<Info?>. Its set() simply assigns the flow value; it has no resource handles, locks, generation, or lifecycle coordination. It is presentation/session metadata rather than a native resource owner, but the value can still be cleared or replaced by overlapping sessions unless the service owner scopes publication to a valid lease.
- TProxyService is the JNI boundary behind HevTunnel. Its native stop semantics and whether it waits for the native event loop to exit need inspection in the JNI/C implementation.
- The service cleanup order is deliberate: stop sharing first; stop the userspace/hev forwarder; stop the external transport; stop TorSocksFront before the engine that provides its upstream; stop the engine; close the TUN descriptor. Lockdown cleanup uses the forwarding-only path and intentionally retains the TUN. These are dependency edges, not arbitrary list ordering.
- ShareBridge.stop() is asynchronous, and SocksTunBridge.stop() / TorSocksFront.stop() request shutdown without joining all workers. The current code does not establish a single bounded "all owned work has exited" barrier.
- PsiphonSocksFront and ShareBridge spawn daemon threads that are not represented by the service's coroutine jobs. A future owner needs explicit drain semantics or documented process-death fallback for those threads and their sockets.

### Updated implementation decision

Do not add runtime ownership code yet. The inspection confirms that the resource registry must represent both the resource handle and its in-flight lifecycle operation, and must preserve dependency ordering. A safe first slice should not be a generic AutoCloseable bag: it needs resource-specific start/stop adapters, session leases, a start-in-flight barrier, and a teardown result that distinguishes "stop requested" from "workers/process/native loop confirmed stopped". It must also model the lockdown TUN as retained while forwarding resources drain. Before implementation, inspect TProxyService JNI/C shutdown and resolve the CustomSession source, then write integration tests around the real service lifecycle and fakeable resource adapters.


### JNI shutdown detail

The native bridge was also inspected in scripts/aethertun-jni.c and its Kotlin declaration in TProxyService.kt. The C bridge protects its own running Boolean with a pthread mutex and creates the hev event loop on a detached pthread. The worker sets the native running flag false only after hev_socks5_tunnel_main() returns. However, TProxyStopService() only calls hev_socks5_tunnel_quit(); it does not join the detached worker or expose a completion acknowledgement. Therefore Kotlin HevTunnel.stop() returning is not evidence that the native event loop has exited.

There is a concrete cross-layer race: HevTunnel.stop() calls the native quit function and immediately sets its Kotlin running flag false. If a new HevTunnel.start() arrives before the old detached worker exits, the Kotlin check permits it, but the C bridge still sees running=true and returns JNI_TRUE as "already running" without creating a new pthread. Kotlin then sets its flag true. When the old worker finally exits, C sets its own running flag false, but Kotlin's flag remains true. A later start can then be incorrectly treated as an already-running no-op even though no native worker remains. This is stronger than an unverified stop-latency concern and requires lifecycle serialization across the Kotlin facade and native bridge, plus an explicit native completion/join or generation-aware handshake. Do not patch only the Kotlin Boolean: the native worker completion must be represented.

The native C bridge also claims running=true before allocating/copying startup arguments and rolls it back on setup or pthread_create failure. This protects duplicate native starts during creation, but does not solve the asynchronous quit/restart window because the worker is detached and quit has no join acknowledgement.


### Upstream source pin and shutdown contract

The native-source fetch path was inspected as part of the lifecycle audit. Before this change, scripts/fetch-natives.sh left HEV_REF empty and silently fell back to the upstream default branch when a requested ref could not be cloned. That meant future builds could compile a different native implementation without a project change. The fetch script now defaults to the reviewed upstream commit fc3f90040b8d4b537341c96538669a9b2e35d514 (upstream main as observed on 2026-11-23), fetches the ref explicitly, checks out FETCH_HEAD detached, and initializes recursive submodules after checkout. A missing ref is now a hard failure instead of a moving-branch fallback. An intentional HEV_REF override remains possible and should be treated as a source change requiring review.

The pinned upstream source was inspected at that exact commit. Its public API contract says hev_socks5_tunnel_main() blocks until hev_socks5_tunnel_quit() is called or an error occurs. The implementation routes quit to hev_socks5_tunnel_stop(), which signals the event task; that event task sets run=false, terminates sessions, and joins the lwIP I/O and timer tasks before the main function proceeds through tunnel finalization and returns. This confirms that the core has an orderly internal shutdown path, but it does not make the app's current JNI stop synchronous: Aether's bridge calls quit and returns immediately, while its outer pthread is detached. The application still has no acknowledgement that hev_socks5_tunnel_main() has returned and its cleanup has completed.

The upstream API documentation does not promise a bounded shutdown duration. Therefore the app must not wait synchronously on the Android main thread or invent a short timeout as proof of safe release. The correct adapter design is to issue quit off the main thread, observe a native completion signal associated with the same start generation, and only then mark the HEV resource fully stopped / permit a new start. If the service is destroyed while this is pending, the process-level owner must retain the pending completion state independently of the Service coroutine scope. The native worker may remain detached only if the completion handshake is reliable; a joinable worker with a serialized stop/restart protocol is another option, but requires careful avoidance of joining while holding the mutex needed by the worker's completion path.

Source provenance: upstream repository commit fc3f90040b8d4b537341c96538669a9b2e35d514. The application build no longer silently follows an unpinned default branch for this dependency.


### Native bridge start-in-flight race (2026-11-23 follow-up)

A second race exists before the detached pthread is even created. In `scripts/aethertun-jni.c`, `TProxyStartService()` acquires the bridge mutex, changes the single `running` Boolean to true, and releases the mutex **before** it obtains/copies the Java config string, allocates `StartArgs`, and calls `pthread_create()`. This creates an unrepresented STARTING interval:

- A concurrent second start sees `running == true` and returns JNI_TRUE as though a tunnel were already running, even though no worker may exist yet.
- If the first caller subsequently fails allocation, JNI string acquisition, or `pthread_create()`, it rolls `running` back to false. The second caller has already been told its start succeeded, but no worker was started for it.
- A concurrent stop during this interval calls `hev_socks5_tunnel_quit()` even though hev initialization/event descriptors may not yet exist. The bridge does not serialize stop against the in-flight start or defer the quit until the core is ready.

This is not fixed by adding a Kotlin synchronized block alone: native entry points can be called from other JNI callers in the future, and the C bridge must own the authoritative lifecycle state. Nor is changing the Boolean to an enum sufficient unless transitions and condition signaling cover argument preparation, thread creation, worker entry, quit request, and worker return.

The native bridge therefore needs at least distinct `IDLE`, `STARTING`, `RUNNING`, `STOP_REQUESTED`, and `EXITED` states (or equivalent state plus a monotonically increasing generation). The implementation contract should be:

1. Reserve a generation and enter STARTING under the native mutex before releasing it.
2. Prepare the copied arguments and create the worker as part of that generation's startup transaction. Concurrent starts must wait for or observe that exact transaction; they must not report success merely because STARTING is occupied.
3. A stop during STARTING must be deferred by the bridge until the worker confirms that `hev_config_init_from_file()` succeeded. After that point, the pinned HEV revision's audited `SYNC_STOP` protocol safely handles stop before `SYNC_SEND` and prevents the event loop from starting. Do not call quit before configuration parsing completes: `hev_socks5_tunnel_main()` performs config parsing before entering `hev_socks5_tunnel_main_inner()`, while `tsync` is process-global; if parsing fails after an early stop set `SYNC_STOP`, the stale bit can survive and cause a later valid invocation's `hev_socks5_tunnel_run()` to return immediately. The config-success boundary must therefore be exposed by a small, version-pinned upstream callback (or equivalent wrapper integration), and the bridge should dispatch a pending stop only after that callback. This behavior is revision-specific and must be re-audited on upgrades.
4. The worker must publish RUNNING only for its own current generation, and on every return path publish EXITED / signal waiters after `hev_socks5_tunnel_main()` has returned and its internal finalization is complete.
5. A restart must not enter a new generation until the prior generation is confirmed EXITED. Any wait/join must occur without holding the mutex needed by the worker to publish completion.
6. JNI/Kotlin callers must distinguish “start accepted/in progress” from “worker running”, and “quit requested” from “worker exited”. A bounded wait result may time out, but timeout must never be translated into STOPPED.

The pinned upstream implementation was also traced more closely: `hev_socks5_tunnel_main()` calls `hev_socks5_tunnel_main_inner()`, which initializes the task system and tunnel, runs the event loop, then performs tunnel/task-system/logger finalization before returning. `hev_socks5_tunnel_quit()` delegates to `hev_socks5_tunnel_stop()`, which signals the event task. The event task sets the run flag false, terminates sessions, and joins the lwIP I/O and timer tasks. This confirms that worker return is the correct completion boundary; observing only the event-loop stop request is too early.

### Build integration confirmation

`app/build.gradle.kts` confirms that HEV and the Aether engine are prebuilt by `scripts/build-natives.sh`; there is no Gradle CMake / externalNativeBuild target that would automatically compile or test a JNI change. The native bridge is compiled there directly from `scripts/aethertun-jni.c` with the NDK clang, `-Wall -Werror`, and `-Wl,--no-undefined`, once per ABI. The script verifies exported JNI symbols and HEV C API symbols, but it does not currently provide a native concurrency test target. A runtime patch therefore needs an explicit host-side C test harness or Android instrumentation coverage in addition to the existing symbol/link checks.

Implementation remains deferred: this audit identifies the full start/stop state contract, but no runtime JNI behavior has been changed or executed in this step.


### Startup-stop behavior in the pinned HEV core

A complete read of the pinned `src/hev-socks5-tunnel.c` corrected the earlier readiness-hook concern. The upstream core explicitly handles a stop request racing its own initialization:

- `hev_socks5_tunnel_init()` sets `SYNC_SEND` only after tunnel, gateway, event task, lwIP tasks, mapped DNS, and task mutex initialization have succeeded.
- `hev_socks5_tunnel_stop()` coordinates with that publication using the `SYNC_WAIT`, `SYNC_SEND`, `SYNC_SENT`, and `SYNC_STOP` bits in `tsync`.
- If stop wins before `SYNC_SEND` is published, it records `SYNC_STOP` rather than writing to an event fd that is not yet ready.
- `hev_socks5_tunnel_run()` checks and clears `SYNC_STOP` before scheduling the event, lwIP I/O, and timer tasks. If the stop bit was set during startup, run returns without entering the event loop; the outer main path then performs normal tunnel/task-system/logger finalization.
- If stop arrives after `SYNC_SEND` is published, the core marks `SYNC_SENT` and writes to the initialized event socket. The event task consumes that event, terminates sessions, and joins the lwIP tasks.

Thus, for this exact pinned upstream revision, the `SYNC_STOP` protocol tolerates stop after successful configuration parsing and before the event loop begins. It does **not** justify sending quit before `hev_config_init_from_file()` succeeds: configuration parsing occurs before the `tsync` protocol is entered, and a failed parse can leave a process-global `SYNC_STOP` bit for a subsequent invocation. The bridge needs a config-success notification (a small patch to the pinned upstream main wrapper is the clearest option) so it can defer pending quit until that boundary. It does not need to wait for event-fd readiness once this boundary has been crossed.

The application bridge still has two independent defects that the upstream protocol does not solve:

1. The bridge's single Boolean conflates STARTING, active execution, stop requested, and exited. Duplicate start can report success while startup allocation/thread creation is unresolved, and Kotlin can permit a new start before the previous worker exits.
2. The worker is detached and the bridge does not publish a generation-specific completion signal after `hev_socks5_tunnel_main()` returns. The Kotlin `running` flag is set on pthread creation and cleared immediately after quit request, neither of which is an authoritative native lifecycle boundary.

The revised bridge should therefore reserve a generation before startup work, serialize start/stop transitions, and use a condition variable to publish worker completion after the upstream main function has returned. It may issue the upstream quit during STARTING only after the config-success callback; before that callback it must retain a pending-stop bit locally. It must still prevent a new generation until the previous worker has published EXITED. Any wait must release the mutex needed by the worker to publish completion, and timeout must remain “completion unknown”, never “stopped”.

Early failures before `hev_socks5_tunnel_run()` also need to publish EXITED with the actual return code and wake waiters. A pthread-create success is not proof that HEV initialized successfully. The C bridge's duplicate-start result and Kotlin facade should distinguish “same generation already accepted” from “new worker confirmed ready”; public API semantics must not imply readiness where only thread creation is known.

### Build integration and test requirements

The native bridge is compiled directly from `scripts/aethertun-jni.c` by `scripts/build-natives.sh` for each Android ABI, with `-Wall -Werror` and `-Wl,--no-undefined`. Gradle has no CMake / externalNativeBuild target and the current native script does not contain a concurrency test target.

Before shipping a runtime lifecycle patch, add a host-side test harness for the bridge state machine using injected fake HEV entry points, or an equivalent native test target. It should deterministically cover: concurrent duplicate start, stop before worker entry, stop while HEV startup is in progress, stop after event-loop entry, main returning with an error, pthread creation failure, repeated stop, and restart attempted before prior completion. Then build the Android bridge for all three configured ABIs and run service-level lifecycle tests. None of those executions has been performed as part of this documentation correction.

No runtime JNI behavior was changed in this step. The previous claim that a separate upstream readiness hook is mandatory is superseded by this source-level finding; the required native fix remains generation-aware completion and serialized start/stop.



### Stop dispatch racing native-main return (2026-11-23 follow-up)

A further source-level race means that a config-success callback plus a bridge condition variable is **not by itself** a complete fix.

The bridge's worker currently calls `hev_socks5_tunnel_main()`, then (only after that call returns) acquires the bridge mutex and publishes its completion state. A concurrent JNI stop can acquire the bridge mutex in the interval after HEV has returned but before the worker publishes EXITED. If the bridge still considers the generation active, it can call the process-global `hev_socks5_tunnel_quit()` during that interval.

This matters in the pinned HEV implementation:

- The normal event-loop stop path consumes `SYNC_SENT`, and `hev_socks5_tunnel_fini()` clears `SYNC_SEND | SYNC_SENT`.
- After the main path has completed, a late `hev_socks5_tunnel_stop()` can observe that `SYNC_SEND` is no longer set and record `SYNC_STOP`.
- That late bit is process-global. A subsequent successful invocation reaches `hev_socks5_tunnel_run()`, which consumes `SYNC_STOP` and returns without starting its event loop.

So “worker has not yet published EXITED” is not equivalent to “HEV can still safely receive quit”. There is a small but real post-return/pre-publication interval, and an ordinary bridge mutex cannot close it: the worker cannot publish the return until the HEV call has already returned.

The native design must make stop generation-scoped at the HEV boundary, rather than relying solely on the legacy process-global quit entry point. Viable implementation directions include a pinned upstream API that accepts a per-invocation cancellation context/token and only signals that invocation while it is active, or an equivalent upstream lifecycle gate whose inactive transition is performed inside the HEV main path after all tunnel/task/logger finalization and is synchronized with stop dispatch. The exact placement and lock ordering must be reviewed against every early-return and initialization-failure path. Merely adding a callback after config parsing is insufficient; merely setting a bridge Boolean immediately after the C function returns is also insufficient.

The bridge must additionally prevent a previous generation's delayed stop dispatch from reaching a newer generation. A generation number stored only in the JNI bridge cannot make the legacy no-argument `hev_socks5_tunnel_quit()` generation-aware; the upstream side must participate in validating the target invocation.

Required deterministic regression tests now include:
- pause the fake/native main function immediately after its logical finalization but before the bridge publishes EXITED; race stop at that point and prove it cannot poison the next generation;
- race a delayed stop dispatcher from generation N against startup of generation N+1 and prove N+1 receives no stale stop;
- exercise config-parse failure followed by a valid invocation;
- exercise normal event-loop shutdown followed immediately by restart;
- verify every exit path clears or retires only its own invocation state.

Implementation remains deferred. No runtime JNI, Kotlin, or upstream source behavior was changed by this finding. The earlier config-success callback requirement remains necessary for stop-during-startup, but it is only one part of the complete protocol.


## Generation-aware HEV bridge implementation slice (2026-11-23)

The branch now contains an implementation of the HEV-specific start/stop boundary described above. This is a narrow resource-lifecycle change, not the broader process-level Service teardown owner.

### What changed

- `scripts/patches/hev-generation-stop.patch` adds an exported `hev_socks5_tunnel_main_with_ready_callback()` entry point to the pinned HEV source. The callback runs only after configuration parsing **and** `hev_socks5_tunnel_init()` have succeeded. A stop requested while configuration or tunnel initialization is in progress remains pending in Aether's bridge; a failed parse/init therefore cannot leave a stale HEV stop bit.
- The same pinned patch adds a small atomic lifecycle gate and a 64-bit invocation token to HEV. The token is installed atomically with `SYNC_SEND` only after tunnel initialization succeeds. Aether's new `hev_socks5_tunnel_quit_generation(token)` stops only when the active HEV invocation has that exact token; a delayed stop from generation N is therefore rejected after generation N+1 starts. `hev_socks5_tunnel_fini()` retires the active token under the same gate before clearing process-global sync bits. The legacy public main/quit API remains available and retains its “stop the currently active invocation” behavior.
- `scripts/fetch-natives.sh` applies the patch only after checking it applies cleanly to the pinned HEV checkout. A source drift or missing patch fails the fetch/build path rather than silently compiling unreviewed upstream code.
- `scripts/aethertun-jni.c` now tracks `IDLE`, `STARTING`, `RUNNING`, `STOP_REQUESTED`, and `EXITED`, and passes a monotonically increasing 64-bit token through the HEV main/quit boundary. Stop during startup is deferred until the HEV-ready callback; duplicate starts do not create a second worker; starts are rejected while a generation is draining; and a new generation is accepted only after the prior worker has returned from HEV and published EXITED.
- The bridge duplicates the caller's TUN descriptor with `dup()` before creating the worker. HEV owns that duplicate for the full native invocation, and the worker closes it only after HEV has returned and finalized. Thus Kotlin may close its original `ParcelFileDescriptor` during service teardown without invalidating a still-draining HEV worker. The worker frees its per-generation arguments before publishing EXITED and performs no further HEV or shared lifecycle work after that publication.
- `TProxyIsActive()` exposes native state to Kotlin. `HevTunnel` no longer maintains its own competing `running` Boolean, which previously could diverge from the native worker after an asynchronous stop.
- `scripts/build-natives.sh` now requires the patched HEV ready-callback and generation-specific quit symbols plus the new JNI status symbol at link/symbol-verification time.

### Important boundaries and verification status

This implementation addresses the identified HEV bridge races with three coordinated safeguards: Aether defers stop until successful tunnel initialization, every stop carries an invocation token checked inside HEV, and HEV serializes token validation with finalization. The duplicated TUN descriptor also prevents the caller's close from invalidating a worker that is still draining. The native state is authoritative; a successful JNI start means the generation was accepted, not that config parsing or tunnel initialization has already succeeded. A later worker error is represented by its transition to EXITED, not by a synchronous JNI start failure.

No Android NDK build, host native concurrency harness, device test, Gradle test, or instrumentation test has been run in this environment. In particular, the pinned-source patch has been authored and wired into the fetch script but has not yet been applied by an actual native build. Treat this as implementation committed but **not yet build-verified**. Required next checks are: run `scripts/fetch-natives.sh`, verify `git apply --check` and the patched HEV symbols, build all three configured ABIs with `scripts/build-natives.sh hev`, then exercise config failure followed by restart, stop during init, normal stop followed immediately by restart, a delayed generation-N quit after generation N+1 becomes active (which must be ignored), invalid TUN fd duplication, and closing the caller's original TUN fd while the worker-owned duplicate remains valid until HEV exits. The broader Service/process-level teardown concerns earlier in this document remain unresolved.



## Verification update — 2026-11-23

This section supersedes the implementation-path and verification statements immediately above where they refer to the old patch-file workflow or say that the native implementation has not yet been built.

### Current implementation path

- The obsolete `scripts/patches/hev-generation-stop.patch` has been removed.
- `scripts/fetch-natives.sh` now invokes `scripts/patch-hev-lifecycle.py` against the pinned HEV source. The patcher uses guarded exact replacements and is idempotent: it skips an already-applied replacement and fails closed if the expected upstream source does not match.
- The callback API and generation-specific stop API described above are supplied by this patcher, not by the deleted diff patch.

### Verified in GitHub Actions

Workflow run [45134832287](https://github.com/mostafaazad369-web/Aether/actions/runs/45134832287) completed successfully for commit `4a6561a`. It fetched and patched the pinned HEV source, built HEV and the JNI bridge for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, passed the native symbol/link checks, and compiled the Android Kotlin sources.

A follow-up workflow, [45138093802](https://github.com/mostafaazad369-web/Aether/actions/runs/45138093802), adds `:app:testDebugUnitTest` after Kotlin compilation. At the time of this update, native fetch/patch and all three ABI builds have passed in that run; Kotlin compilation is still in progress and the JVM unit-test step has not started. Do not treat the follow-up unit-test task as passed until the run reports success.

### Remaining unverified coverage

The successful native build proves compilation and required exported symbols, not live tunnel correctness or race freedom. A host-side deterministic native concurrency harness and device-level lifecycle tests are still outstanding. In particular, test config failure followed by restart, stop during initialization, immediate restart after normal stop, stale generation-N stop after generation N+1 starts, invalid TUN descriptor duplication, and closing the caller's original TUN descriptor while the worker-owned duplicate remains live. The broader process-level Service teardown concerns in this document also remain open.


## Verification update — 2026-11-24

### CI result

GitHub Actions run [45449752505](https://github.com/mostafaazad369-web/Aether/actions/runs/45449752505) completed successfully for commit `0d609f120be7b562c0794e4d7e7ce22ee9d01f45`. The run fetched and patched the pinned HEV source, built HEV and the Aether JNI bridge for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, compiled Android Kotlin sources, and completed `:app:testDebugUnitTest` successfully. This verifies build integration and JVM tests, not live-device behavior or native race freedom.

### Follow-up source audit

The following current branch blobs were inspected:

- `AetherVpnService.kt`: `09e2af5e256df3e39886771d94edb2e74376ee1d`
- `AetherProcess.kt`: `c025e9b1ae49bb95f24ba87fb89a3e1b0169f586`
- `IdentityVault.kt`: `76372cad214af67d73c3d32803df32b3e0d845a7`
- `SocksTunBridge.kt`: `e20c779fdccb0637046bba30fc7aff6a462cb8e4`
- `XrayProcess.kt`: `a00e5b7b36dbd650eaeb0fdd3b24168af64d2f13`
- `aethertun-jni.c`: `babfbdc5dae1b389ab89c936a2f9a4639d76a7de`

Additional concrete findings:

1. The session-engine paths at the ordinary connect, reconnect, and Psiphon-only start sites assign `engine` only after `AetherProcess.start()` returns. The Smart Plus lane is better ordered: it registers the process in `raceEngines` before calling `start()`, and its `finally` block stops/removes it. However, the service-wide cleanup does not drain `raceEngines` directly, and `onDestroy()` does not join the session job before cancelling the service scope.
2. `SocksTunBridge` is assigned to `tunBridge` only after `bridge.start()` returns. HEV's `tunnelStarted` flag is likewise set only after the JNI start call returns. Both leave start-in-flight ownership windows.
3. `AetherProcess.stop()` calls `sealIfLastEngine()` when its `process` field is null. That helper unconditionally calls `IdentityVault.markRunning(false)`. A start failure after identity unsealing but before `ProcessBuilder.start()` has not incremented the running-engine count; decrementing anyway can consume another live Smart Plus lane's count and may seal identity files while that lane still needs them. Replace the unpaired increment/decrement API with a per-instance, idempotent engine lease before relying on this counter for lifecycle safety.
4. Xray is stored in the service before its initial start, but `startXray()` invokes `runner.start()` outside `xrayLock`; the watchdog restart path does use that lock. Initial startup and teardown therefore still need a shared ownership/serialization contract.

### Implementation boundary

No runtime code was changed in this audit update. Do not treat publishing a handle before `start()`, or adding a standalone mutex, as a complete fix: a stop can still happen before the start call enters the resource itself. The next implementation slice must make resource acquisition cancellable/registrable before side effects, pair each engine's identity lease exactly once, and test failure before spawn, stop during spawn, and concurrent Smart Plus lanes. Keep the broader process-death and Android Service destruction limitations explicit.
## Follow-up audit — Xray child-process ownership (2026-11-25)

The Xray teardown path had the same class of failure previously corrected for the Aether engine, with an additional credential-file hazard:

- `XrayProcess.stop()` previously nulled its `Process` reference before confirming exit, sent `destroyForcibly()` without waiting for confirmation, and deleted the config file unconditionally. A still-running Xray child could therefore become untracked while its credential-bearing config was deleted underneath it.
- `XrayProcess.start()` previously ignored the result of its implicit stop and could proceed to spawn a replacement even if the previous child had not exited.
- `AetherVpnService.stopCustom()` previously discarded the Xray handle regardless of whether stop had succeeded.

### Change made on the feature branch

- `XrayProcess.stop()` now delegates to the shared bounded `ProcessReaper.terminateAndReap()` policy and returns a Boolean confirmation.
- If exit cannot be confirmed, it retains both the process reference and config-file reference, and does not delete the config.
- A subsequent `start()` refuses to spawn a replacement unless the previous process has been confirmed reaped.
- `AetherVpnService.startXray()` enforces the same gate before a new Xray generation starts.
- `stopCustom()` clears the Xray handle and custom-session metadata only after confirmed stop; otherwise it retains ownership for a later teardown retry.
- Existing `ProcessReaperTest` covers graceful exit, forced exit confirmation, failed confirmation, and interrupt restoration. These are policy tests; no Xray process integration or Android Service lifecycle test has been run for this change.

### Remaining boundary

The Xray handle is still stored in the Service instance. If `onDestroy()` returns after an unconfirmed stop and the app process remains alive while the Service instance is discarded, this handle can still be lost. A process-level resource owner remains necessary for that broader case. This change narrows the loss window and prevents premature deletion/replacement, but does not claim to solve Service-destruction ownership or child-process survival after app-process death.
## Follow-up audit — Service destruction and Xray stop serialization (2026-11-25)

Additional call-site tracing confirms the following:

- `AetherVpnService.onDestroy()` is a synchronous lifecycle callback. It cancels the session and stop jobs, then calls `cleanupNativeOnly()` and `stopCustom()` inline before cancelling the service scope.
- `stopCustom()` acquires `xrayLock` and calls `XrayProcess.stop()` while holding that monitor. The new reap policy may wait up to the graceful and forced-confirmation windows (250 ms + 2,000 ms), and this can occur on the main thread when reached from `onDestroy()`.
- `startXray()` also calls `runner.stop()` while holding `xrayLock`. This prevents a concurrent watcher/teardown from mutating the same runner during the reap, but it makes the bounded wait part of the lock hold time.
- The Xray watcher restarts through the same runner and under `xrayLock`; `XrayProcess.start()`/`stop()` are also synchronized on the runner, so the two layers serialize process replacement and stop.
- `onDestroy()` cancels `stopJob` but does not join it. Synchronous native cleanup is serialized by the service's lifecycle lock; Xray start/stop is serialized separately by `xrayLock`. This is not a process-level owner and does not preserve an unconfirmed handle after the Service instance becomes unreachable.

### Risk and decision

The Xray change closes premature handle/config deletion and prevents replacement before confirmed exit. It does not solve the broader Service-destruction ownership gap. It also introduces a bounded wait of up to 2.25 seconds for Xray when teardown is invoked synchronously; cumulative waits across other process/native cleanup paths could increase main-thread lifecycle latency. Do not move this work to an unowned coroutine or executor as a superficial fix: Android may destroy the app process before asynchronous cleanup completes. A future process-level owner must define both resource ownership and a main-thread-safe destruction contract, with explicit completion/fallback semantics.

### Verification still required

- Add focused tests around XrayProcess using an injectable process-launch/reap boundary, including failed reap retaining the config and rejecting replacement.
- Add service-level tests for destruction racing with Xray startup and watcher restart.
- Measure worst-case `onDestroy()` latency with injected slow/failing process termination and native cleanup.
- Keep Android device/process-survival verification separate from JVM policy tests.
## Follow-up audit — asynchronous shared-bridge commands (2026-11-25)

### Confirmed ShareBridge start/stop ordering gap

`ShareBridge.start()` launches a daemon `share-start` thread and returns immediately. The thread calls `startSync()`, which increments the bridge's internal `session` only when it eventually enters its synchronized body. `ShareBridge.stop()` changes the visible active/request flags, increments `session`, launches a separate daemon `share-stop` thread, and returns immediately. The stop thread closes listeners only if its captured session still matches.

This leaves a stale-start ordering case: `start()` can enqueue its thread; `stop()` can then increment the session and enqueue cleanup; if the start thread enters `startSync()` after that stop request, `startSync()` increments the session again and starts listeners. The stop thread sees a different session and deliberately skips closing them. The older start request can therefore resurrect sharing after a later stop request. The internal watcher/session token protects stale watchers, but it does not order the asynchronous public start request against stop.

`AetherVpnService` calls the asynchronous `ShareBridge.start()` in multiple connect paths and calls `ShareBridge.stop()` during cleanup. Thus this is relevant to disconnect, reconnect, service destruction, and lockdown teardown. It is independent of the service-local `nativeLifecycleLock`, because ShareBridge's worker threads and singleton state are not governed by that lock.

### Other ownership observations

- `AetherVpnService` registers Smart Plus probe engines in `raceEngines` before `engine.start()`, which is the correct local register-before-start order. However, the registry is an instance field; a failed reap retained there is lost if the Service instance becomes unreachable.
- The main Aether engine is assigned to `engine` before `start()`, and Xray is assigned to `xray` before its start path. Those are also locally registered before start, but remain Service-instance-owned.
- The userspace `SocksTunBridge` is assigned to `tunBridge` before `bridge.start()` under `nativeLifecycleLock`; HEV sets `tunnelStarted = true` before entering JNI. These are useful local patterns to preserve in a future process-level registry.
- `HevTunnel.stop()` is asynchronous. Cleanup requests stop and closes the Service-owned TUN descriptor; HEV's duplicated descriptor remains owned by the native worker until its exit. The next connect waits for `awaitStopped()`, but Service destruction has no process-level completion owner.
- `TorSocksFront.start()` is synchronous for listener creation; `stop()` closes the listener and calls `shutdownNow()` on its pool but does not await worker termination. `PsiphonTransport.stop()` delegates to `PsiphonSocksFront.stop()` and clears a global callback. These singleton fronts need explicit stop-completion semantics in the ownership design.

### Recommended next change

Fix ShareBridge command ordering before introducing a broad resource registry. Give every public start/stop command a monotonically increasing command generation reserved synchronously at API entry. A queued start must verify that its generation is still current before opening listeners; a queued stop must close only the generation it owns and must not close a newer start. Preserve synchronous `startSync()` semantics by making it participate in the same command-generation protocol. Add deterministic JVM tests for start-then-stop-before-start-worker-runs, stop-then-new-start, and stale stop completion after a newer start. Do not change the UI's asynchronous API or LAN exposure defaults.

Do not claim the ShareBridge race is fixed until those tests and the actual implementation are committed.
## Follow-up implementation — ShareBridge command generations (2026-11-25)

`ShareBridge` now reserves a monotonically increasing command generation synchronously when public `start()` and `startSync()` requests enter. The asynchronous `start()` worker passes its reserved generation into `startSyncForGeneration()`; the worker checks that the generation is still current after acquiring the ShareBridge monitor and refuses stale queued starts.

`stop()` also reserves a generation before entering its synchronized state transition. It advances the existing listener session only if its generation remains current, and its asynchronous close worker checks both the command generation and listener session immediately before closing. Therefore a stop worker superseded by a later start cannot close the newer start's listeners.

Three JVM tests were added in `ShareCommandGenerationTest.kt`: queued start invalidated by stop, newer start superseding an earlier stop, and stale stop completion being rejected after a newer start.

### Scope and remaining caveats

- These tests exercise the generation primitive deterministically; they do not instantiate Android sockets or prove end-to-end listener behavior. A future injectable listener/worker boundary should add integration-style ordering tests.
- `enableLan()`, `disableLan()`, `setMode()` and route-change watcher commands still have their own asynchronous workers and are not yet covered by a single command-order protocol. Review their interaction with full bridge stop before claiming every ShareBridge control is serialized.
- `stop()` still needs to acquire the ShareBridge monitor before it can reserve the listener-session transition. If `startSyncForGeneration()` is doing synchronous bind/setup work under that monitor, stop latency can include that work. Measure this on-device; do not move cleanup to an unowned executor as a workaround.
- This change does not provide process-level ownership after Service destruction and does not guarantee that all active client relay threads have terminated when `stop()` returns.
## ShareBridge LAN/configuration command guards (2026-11-25)

The follow-up implementation now also guards the asynchronous LAN controls and refresh workers:

- `enableLan()` and `disableLan()` reserve a shared LAN-request generation and update `lanRequested` under the ShareBridge monitor. Their queued workers verify the generation and current intent before opening or closing LAN listeners. A newer enable/disable request supersedes an older queued worker.
- `stop()` invalidates pending LAN-request and LAN-configuration workers inside the same synchronized state transition that marks the bridge inactive. This prevents stale LAN workers from reopening listeners after teardown.

- When `enableLan()` must start an inactive bridge, it now reserves the bridge-start generation in the same synchronized API-entry transition as its LAN-request generation. Its queued startup is therefore superseded by a later `stop()` rather than racing to call `start()` after the stop.
- `setMode()` and `noteEngineRoutes()` reserve a separate LAN-configuration generation while updating their state under the monitor. Their refresh workers verify the latest generation and current active/LAN-requested state before rebinding.
- JVM generation-policy tests now cover LAN enable/disable ordering, latest configuration refresh, and invalidation of pending LAN/configuration workers by stop.

### Remaining verification limits

- The added tests exercise the generation primitive, not real Android listener binding or concurrent execution of the complete ShareBridge API. A controllable listener/worker seam is still needed for deterministic integration tests.
- `startSyncForGeneration()` performs bind/setup work while holding the ShareBridge monitor. A concurrent `stop()` may therefore wait for that work before it can mark the bridge inactive; measure this latency on-device.
- `attach()`, credentials/auth changes, client relay threads, and the watcher lifecycle are not converted into one process-level resource owner by these changes.
- No claim is made that Service destruction now waits for all ShareBridge resources to terminate.

### ShareBridge queued LAN-start invalidation follow-up (2026-11-25)

- A follow-up audit found that an inactive `enableLan()` queued bridge startup using only the bridge command generation. A later `disableLan()` invalidated the LAN request generation but did not invalidate that queued bridge command; the startup could therefore overwrite the newer LAN intent by setting `lanRequested = true`.
- LAN-triggered startup now carries the LAN request generation into `startSyncForGeneration()` and is rejected under the bridge monitor if that request has since been superseded. Ordinary loopback starts remain independent and are not cancelled by `disableLan()`.
- Added a JVM policy regression test asserting that a later LAN disable invalidates the queued LAN intent while leaving the independent bridge command current. This is still a generation-policy test, not an Android listener/socket integration test; build, tests and device behavior remain unverified.

### ShareBridge listener ownership seam (2026-11-25)

- Extracted the LAN listener collection into `ShareListenerRegistry`, which owns add, retain/remove, ordering, snapshots and idempotent close-all behavior. `ShareBridge` uses this registry for its actual LAN listener set; callers continue to serialize mutations under the bridge monitor.
- Added JVM tests with fake listeners to verify that removed listeners are closed, retained listeners stay open, close-all closes each owned listener once, and ordering does not alter ownership.
- These tests exercise the production registry/cleanup primitive, but do not simulate Android socket binding, accept-loop races, scheduler ordering, or active client-socket teardown. Those remain follow-up integration concerns; no build/test result is claimed.


### ShareBridge concurrent connection admission (2026-11-25)

- Replaced the non-atomic liveConnections read-then-increment check with a compare-and-set admission gate. Concurrent loopback and LAN accept loops can no longer collectively admit more than the configured 128 active handlers.
- Removed the per-start reset of active connection counters. Closing/replacing listeners does not synchronously terminate already accepted handlers, so resetting their process-wide count could let old handlers decrement a freshly reset counter and make accounting inaccurate. Active handlers continue to occupy capacity until their existing finally path releases it.
- Added JVM coverage for simultaneous admissions racing at the configured limit and for ordinary capacity exhaustion.
- This is a focused capacity/accounting correction. It does not yet own or forcibly close active client/upstream sockets on stop, nor does it establish accept-vs-stop atomic handoff. Those remain open; build, tests and Android device behavior are not verified.


### ShareBridge accepted-client ownership and teardown (2026-11-25)

- Each accept loop now captures the listener session. After accept, it checks the captured session, listener state and LAN intent under the same ShareBridge monitor used by stop/teardown; admission and registration in the active-client registry occur in that critical section. A stop that wins first causes the accepted socket to be rejected; a registration that wins first is visible to teardown.
- Accepted client sockets are registered before handler-thread handoff. Full bridge teardown closes all registered clients; LAN-only teardown closes LAN clients while preserving loopback clients. When a LAN interface disappears during watcher reconciliation, clients associated with that interface are also closed.
- Handler completion removes its socket from the registry and releases the existing connection counters. Socket close is expected to unblock TCP reads/relay; UDP association cleanup follows its existing control-socket termination path.
- Added a registry policy test for LAN-only removal while loopback ownership remains. This does not constitute a real ServerSocket accept/stop stress test, Android integration test, or proof that every relay worker has terminated. Upstream TCP sockets are still closed by their handler finally paths after client-side unblocking; there is no separate process-wide upstream-socket registry yet. Build, tests and device behavior remain unverified.


### ShareBridge upstream socket ownership (2026-11-25)

- Each accepted client now owns its upstream TCP socket legs in the same `ActiveClient` record. The ownership record is registered before the upstream connect/handshake begins, so teardown can interrupt even a still-connecting or greeting-blocked socket.
- All upstream creation paths pass the owning client: loopback SOCKS relay, LAN SOCKS CONNECT and UDP control, and HTTP CONNECT/plain HTTP via `socksOpen`. Registration is rejected if teardown has already removed the client record.
- Closing an active-client record closes all its upstream sockets and then its client socket. Cleanup is guarded by an atomic once flag, so competing teardown/handler completion paths do not repeat socket closure. UDP datagram sockets remain handler-local; closing the client control socket unblocks its control read, after which the handler closes both datagram sockets and the upstream control socket and joins its relay workers with bounded waits.
- Added a JVM test using tracking Socket fakes to verify client and upstream closure is idempotent. This tests the ownership record, not real TCP connect/accept behavior, UDP socket wakeup, or thread termination on Android. Build and test execution are not verified.


### ShareBridge UDP association teardown ownership (2026-11-25)

- The accepted client's ownership record now also tracks both UDP relay `DatagramSocket` endpoints: the client-facing share socket and the loopback upstream-facing socket. Each endpoint is registered immediately after creation and before the association is exposed to the client; if teardown has already removed the client owner, registration fails and the new datagram socket is closed immediately.
- Full teardown and LAN-only teardown therefore close UDP receive sockets directly through the same client ownership record, rather than relying solely on the handler to notice that its TCP control connection ended.
- UDP handler cleanup now closes both datagram endpoints and the upstream TCP control socket in `finally`, then performs bounded joins for each worker that was actually started. This covers early returns and exceptions after worker startup as well as the ordinary control-channel termination path. A worker that exceeds its one-second join window is logged; this is not a guarantee of thread termination.
- Added a JVM test using tracking DatagramSocket instances to verify that both UDP endpoints are closed exactly once by client ownership teardown. This is a resource-ownership test, not an Android UDP receive/unblock integration test. Build and test execution remain unverified.
