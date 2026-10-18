# Distributed Counter Lab

A small Java MVP for explaining distributed-systems fundamentals. Two Java HTTP server processes share a file-backed counter: the file is the system of record, and both servers read and update the same state.

## Run it

Requires JDK 17 or newer. From the repository root, start the first server:

```sh
./run.sh
```

In a second terminal, start another process using the same state file:

```sh
PORT=8001 APP_SERVER_ID=server-2 ./run.sh
```

Open [http://localhost:8000](http://localhost:8000) and [http://localhost:8001](http://localhost:8001). Increment on one page; the other reflects the shared value within a second. The server ID shows which process served the most recent request.

The runner compiles the Java sources into `build/classes` automatically. Both processes use `distributed_state.txt` in the repository by default. To start fresh, stop both servers and remove that file and its `.lock` file. To use another shared location, set `STATE_PATH=/path/to/state.txt` on both processes. The state file and lock file must be on storage shared by both servers; this local file-lock demo is not a production multi-host database.

Run the included checks with `./run.sh test`.

## Why this is a distributed-systems demo

The application has multiple independently running server processes. Each process has its own HTTP listener, request threads, and server ID; a browser can send a request to either process. The processes do not share Java heap memory. Instead, they coordinate through a common state file and its lock file. That separation—independent workers needing to agree on and update shared state—is the small-scale distributed-systems problem this MVP makes visible.

### Follow one update

1. The browser sends `POST /api/increment` to one server. The response identifies which server handled it.
2. That server acquires the lock associated with the shared state file, reads the latest value and version, increments both, writes them, and releases the lock.
3. A read sent to either server acquires the same lock and reads the persisted state. The other browser tab polls once per second, so it displays the changed value without sharing memory with the first server.

The lock is the coordination mechanism: it prevents two well-behaved processes from modifying the record at the same time. The state file is the system of record; the HTTP server processes are replaceable workers, not authoritative copies.

### Concepts illustrated

| Concept | Where to observe it |
|---|---|
| Multiple nodes/processes | Start both commands above. The two server IDs and ports identify separate server processes. |
| Shared state | Increment at `localhost:8000`; the value shown at `localhost:8001` changes because both processes use the same `STATE_PATH`. |
| System of record | `distributed_state.txt` stores the authoritative value and version. There is no per-server counter cache to reconcile. |
| Coordination and consistency | `CounterStore` locks reads and writes across processes. A completed increment is visible to a subsequent read through either server. |
| Concurrency and race conditions | Safe increments perform the read-modify-write while holding the lock. The race demo deliberately reads stale values before its workers write, showing how updates can be lost without correct coordination. |
| Versioning | Each successful increment and reset increases the version, making state changes easy to track in the UI and API. |

### What this demo does not provide

This is a local, two-process teaching example—not a production distributed database or highly available cluster. Both processes run on one host and depend on the same filesystem. If the host or storage fails, both servers lose access to the record; there is no replication, failover, leader election, partition handling, or recovery protocol. The local file lock should not be assumed to coordinate reliably across arbitrary network filesystems or hosts. A production deployment would normally use a database or coordination service designed for its network and failure model.

For an interview, describe it as a **multi-process distributed-systems model** that demonstrates shared-state coordination and concurrent updates, while being explicit that it does not demonstrate multi-host replication or fault tolerance.

## Code layout

- `DistributedCounterServer` starts and configures the HTTP server.
- `CounterHttpHandler` maps HTTP routes to application operations.
- `CounterStore` owns persistence and cross-process locking.
- `RaceConditionDemo` coordinates the intentionally unsafe concurrent writes.
- `CounterState` represents the stored value and version; `JsonResponses` formats API responses.

## Interview talking points

- **System of record:** The state file is authoritative; HTTP servers do not keep their own counter copies.
- **Consistency:** Reads and committed updates acquire the same file lock. A successful update returns only after the value and version are written.
- **Shared state:** Both server processes coordinate through one state file rather than in-memory server-local counters.
- **Race conditions:** `Safe increment` serializes the read-update-write operation under a cross-process file lock. `Run race demo (unsafe)` deliberately makes eight workers read the same old value before writing it back, so updates are lost. That endpoint exists only to demonstrate a bug.
- **Multi-server behavior:** Run the app on two ports with the same state path; requests can reach either process and still update the same source of truth.

## API

- `GET /api/state` — current value, version, and serving server ID.
- `POST /api/increment` — atomic, serialized increment.
- `POST /api/reset` — set value to zero and increment the version.
- `POST /api/race-demo` — run the intentionally unsafe concurrent read-then-write demonstration.

## Tests

```sh
./run.sh test
```