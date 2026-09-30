# klause lab setup

An experiment server for klause. A job names a git ref and a list of shell commands. The server checks the ref out
into a fresh worktree, builds `klause-cli`, and runs the commands one at a time, one job at a time. It serves the
queue and live output over HTTP.

It runs on macOS or Linux. Both services log the koblas backend at startup: the engine, and whichever host BLAS
it finds. They refuse to start (exit 78) unless koblas runs its Vector API engine, which is what klause's Level 1
calls use, so no timing comes from the scalar fallback. Host BLAS serves only Level 2 and 3, which klause does not
call, so it is reported and not required. `klause-lab check` prints the report and does nothing else.

## Crash safety

- `api` and `runner` are separate processes that share one SQLite database (WAL, `synchronous=FULL`). A solve
  that takes the runner down leaves the queue readable.
- Every state change is committed before the next step starts. A restarted runner resumes the job it was on:
  - finished commands keep their results;
  - an interrupted command is rerun from the start;
  - the interrupted command's process tree is killed first. It is found by its recorded pid and start time, so
    the rerun never runs alongside it.
- A command's `<n>.exit` file is written atomically once the command ends: an exit code, or `timeout`.
- The service manager restarts either process after a crash and starts both at boot: launchd daemons on macOS,
  systemd user units with lingering on Linux.

## What a command sees

Commands run with `bash -c`, with the job's worktree as the working directory, and with this environment:

| variable | value |
| --- | --- |
| `KLAUSE_CLI` | the built `klause-cli` start script |
| `KLAUSE_CLI_OPTS` | `LAB_SOLVE_JAVA_OPTS`, default `-Xmx4g -XX:ActiveProcessorCount=1 -XX:+UseSerialGC` |
| `KLAUSE_WORKTREE` | the checkout |
| `KLAUSE_CORPUS` | the bench corpus; the bench's own default `~/.cache/klause-bench/corpus` |
| `JOB_DIR` | the job's output directory; write result files here |
| `OPENBLAS_NUM_THREADS`, `VECLIB_MAXIMUM_THREADS`, `MKL_NUM_THREADS`, `OMP_NUM_THREADS` | `1` |

Stdout and stderr go to `JOB_DIR/<n>.out` and `<n>.err`. Anything else a command writes into `JOB_DIR` is kept
after the job ends. The worktree is deleted. A job fails if any of its commands fails, but the remaining commands
still run, so one bad instance does not stop a sweep.

## Setup

1. Install a JDK 25 and git on the server.
2. Give the server read access to the klause repository: a deploy key or its own SSH key on GitHub.
3. Clone this repository on the server and run `deploy/install.sh`. The first run writes
   `~/.config/klause-lab/lab.env` and stops. Fill that file in, then run the script again. It builds the lab, runs
   the host check, and registers both services. On macOS it asks for sudo to install the launchd daemons. Set
   `sudo pmset -a sleep 0 disksleep 0` so queued work is not suspended.
4. From the dev PC, copy the corpus over (51 GB on the first run, incremental after that):
   `LAB_HOST=<server> deploy/lab corpus`.

Logs go to `$LAB_DATA/logs/{api,runner}.log`.

## Client

`deploy/lab` wraps the API with curl, jq and rsync. Set `LAB_HOST` (default `lab.local`).

```sh
cat > sweep.txt <<'EOF'
./gradlew :klause-bench:bench --max-workers=1 --args="solve suite=mzn-bench per-family=1 max=50 seed=1"
cp -r klause-bench/output "$JOB_DIR/"
EOF
deploy/lab submit leaf-lp fix/leaf-lp-slice-pause sweep.txt 21600   # optional per-command timeout, seconds
deploy/lab ls
deploy/lab tail 7 0          # the last 8 kB of command 0's stdout; `err` for stderr
deploy/lab fetch 7           # rsync jobs/7/ to ./lab-jobs/7
deploy/lab cancel 7
```

The browser page at `http://<server>:8420/` refreshes every 10 s and shows the queue, the running command, and
links to its live output.

## API

| method | path | |
| --- | --- | --- |
| `POST` | `/jobs` | `{"name", "ref", "commands": [{"cmd", "timeoutSec"?}]}` → `{"id"}` |
| `GET` | `/jobs`, `/jobs/{id}` | job and command states |
| `POST` | `/jobs/{id}/cancel` | a queued job is dropped; a running one has its command tree killed |
| `GET` | `/jobs/{id}/files` | the files in the job directory |
| `GET` | `/jobs/{id}/files/{path}` | one file; `?tail=<bytes>` for the end of a growing log |
| `GET` | `/health` | queue counts and the koblas report |

The API has no authentication. Keep it on the LAN.
