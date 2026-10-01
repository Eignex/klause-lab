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
  - the interrupted command's processes are killed first, so the rerun never runs alongside them. Each command
    leads its own process group, and the group is killed whole, including children whose shell already died. A
    recorded start time keeps a reused pid from being taken for the command.
- A command's `<n>.exit` file is written atomically once the command ends: an exit code, or `timeout`.
- A service shutdown, such as a reboot, kills the running command but does not record it, so it reruns after boot.
- The service manager restarts either process after a crash and starts both at boot without a login: launchd
  daemons on macOS, systemd user units with lingering on Linux. On macOS the runner also runs under
  `caffeinate -i`, which keeps the machine awake while it lives.

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
after the job ends. The worktree is deleted. A failing command does not stop the job: the rest still run, the job
ends DONE, and the failures are counted on it. FAILED means the job itself could not run, such as a failed checkout
or build.

`LAB_SHARED_PATHS` names worktree directories every job shares, by default `klause-bench/build/bench-cache`. Bench
keys a reference result by instance, solver and budget alone, so a later job replays it instead of rerunning the
solver; a klause result also keys on the CLI binary, which each job builds afresh, so it is never replayed into
another build.

## Docker

The containerised reference solvers (SCIP, clasp, the XCSP3 cp-sat image) need Docker. `install.sh` picks it up
by itself: on macOS it installs a boot-time colima daemon when colima is installed (`brew install colima docker`), on
Linux it uses the system Docker. The runner then waits for `docker info` to answer before it takes a job. Set
`LAB_DOCKER=none` in `lab.env` to opt out. Build the images once, as a job of their own:

```sh
printf '%s\n' \
  'docker build -t klause-scip klause-bench/scip' \
  'docker build -t klause-clasp klause-bench/clasp' \
  'docker build -t klause-xcsp3-cpsat klause-bench/xcsp3-cpsat' > images.txt
deploy/lab submit docker-images main images.txt 3600
```

## Setup

1. Install git and any JDK 17 or newer on the server; Gradle needs one to launch. The Gradle toolchain downloads
   JDK 25, and the services and the klause builds run on that.
2. For SSH access from the dev PC, enable Remote Login on macOS and install the PC's key with `ssh-copy-id`. The
   lab itself clones klause over HTTPS and needs no key.
3. Clone this repository on the server and run `deploy/install.sh`. It builds the lab, runs the host check, and
   registers the services. Settings live in `~/.config/klause-lab/lab.env`, which the first run creates with every
   setting commented out at its default; rerun the script after changing it. The services get a fixed PATH
   (Homebrew's and the system directories on macOS, `~/.local/bin` and the system ones on Linux); `LAB_PATH` puts
   more in front. `LAB_INSTALL_DRY_RUN=1 deploy/install.sh` writes the service files to a temporary directory and
   registers nothing; it reuses the existing build, since rebuilding under running services breaks them until
   they restart. On macOS it asks for sudo, to install the launchd daemons and to set
   `pmset -a sleep 0 disksleep 0`.
4. Bench downloads each corpus collection the first time a job uses it. To copy the dev PC's instead (51 GB on the
   first run, incremental after that), run `deploy/lab corpus` there; it needs the SSH access from step 2.
5. Prefer Ethernet to Wi-Fi: a Wi-Fi link that drops leaves the server unreachable while it keeps working.

Logs go to `$LAB_DATA/logs/{api,runner}.log`.

## Client

`deploy/lab` wraps the API with curl, jq and rsync. Set `LAB_HOST` to override the default server, `192.168.50.104`.

```sh
cat > sweep.txt <<'EOF'
./gradlew :klause-bench:bench --max-workers=1 --args="solve suite=mzn-bench per-family=1 max=50 seed=1"
cp -r klause-bench/output "$JOB_DIR/"
EOF
deploy/lab submit leaf-lp fix/leaf-lp-slice-pause sweep.txt 21600   # optional per-command timeout, seconds
deploy/lab ls
deploy/lab tail 7 0          # the last 8 kB of command 0's stdout; `err` for stderr
deploy/lab fetch 7           # download jobs/7/ to ./lab-jobs/7 over HTTP
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
