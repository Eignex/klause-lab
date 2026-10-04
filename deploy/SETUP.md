# klause lab setup

An experiment server for klause. An experiment solves a selection of problems under one or more configurations,
one problem per case. The server builds each commit the configurations name, runs the cases, one experiment at a
time, and compares the results. It serves the queue, live output and results over HTTP.

It runs on macOS or Linux. Both services log the koblas backend at startup: the engine, and whichever host BLAS
it finds. They refuse to start (exit 78) unless koblas runs its Vector API engine, which is what klause's Level 1
calls use, so no timing comes from the scalar fallback. Host BLAS serves only Level 2 and 3, which klause does not
call, so it is reported and not required. `klause-lab check` prints the report and does nothing else.

## Experiments

An experiment runs one selection of problems under every configuration it names, one problem per case. Each case
is one job command the lab writes itself; there is no other kind of job. `lab run <experiment.json>` queues one:

```json
{
  "name": "restarts-ab",
  "problems": { "suite": "xcsp3-cop,mzn-bench", "kind": "cop", "per-family": "2", "max": "400", "seed": "1" },
  "base": { "ref": "main", "engine": "cp", "timeout": "30000" },
  "grid": { "ref": ["main", "fix/x"], "param.restarts": ["luby", "geometric"] },
  "seeds": [1, 2, 3],
  "parallel": 4
}
```

- `problems` are `klause-bench select` filters: `suite` (required), `kind`, `category`, `tag`, `name`,
  `per-family`, `max`, `seed`, `balance`. A list of such selections pools their problems, each selection capped on
  its own, so `[{"suite": "hakank", "max": "60"}, {"suite": "xcsp3-cop", "max": "60"}]` takes 60 of each.
- Each entry of `configs` (default: one empty config) is merged over `base`, then crossed with every combination of
  `grid`. A configuration takes `ref` (default `main`), `label`, `timeout` (ms, default 10000), `backend`, `engine`,
  `processors`, `lp`, `presolve`, `fixed`, and `param.<name>` for `--param <name>=<value>`. An arm without a `label` is
  named by the values that set it apart.
- `seeds` sets the solver seed; each listed seed is its own case. Without it each case runs once on the bench's seed.
- `repeats` (default 1, at most 100) runs each (problem, configuration, seed) that many times, identically, which
  measures the machine's timing noise apart from the seed's.
- `parallel`, `priority` and `confirm` as below.

The runner sets an experiment up when it first takes it. It builds every commit the arms name once, each in its own
worktree. It then runs `klause-bench select` at the first arm's commit, which also fetches the corpus, and writes one
command per case: a `klause-bench solve-one` of one problem in one arm's worktree. Every arm of a problem runs back to
back, in an order rotated per problem, so drift and pauses spread evenly over the arms. A case always solves (the
bench result cache is off) and its record lands in the store. `lab cases <id>` lists them, as does `GET
/experiments/<id>/cases`.

An experiment's page (its job id) compares the arms:

- each arm's commit, cases done, solved, proven (optima and infeasibility), unsupported (models klause declines),
  errors (solver crashes and problems that did not compile or parse) and mean PAR-2 time (an unsolved run charged
  twice its budget);
- 95% intervals on solved, PAR-2 and the score, bootstrapped over problems, a problem's seeds and repeats averaged
  first so each problem counts once;
- each arm against the first: the geometric mean of its PAR-2 time over the first arm's on the problems either solved,
  with its interval and a Wilcoxon signed-rank p, and the problems it scored better and worse on, with a sign-test p;
- noisy problems: an arm's runs of one problem (seeds, repeats) that disagree on the verdict, or whose time to best
  spreads over 25% of its mean;
- a pairwise score by the MiniZinc Challenge rule `output/compare.sh` uses: per problem and seed, solved beats
  unsolved, proven beats unproven, then the better objective, and equal outcomes split the point by time;
- how each arm did against the first, problem by problem;
- disagreements first: one arm proving infeasibility where another solves, different proven optima, or a solution
  better than a proven optimum;
- every problem across the arms, the best cell marked, optionally only the rows where arms differ. A cell reads
  the objective (starred when proven), `sat`, `infeasible`, `unknown`, `unsupported` or `load error` (the reason on
  hover), or the case's status when it left no record, such as `failed` for a run killed by its timeout.

`lab compare <id> <id>...` gives the address of the same view over several experiments, each arm named by its job,
problems paired by suite and name. A scheduled run's page links to the comparison with the run before it.

`lab csv <id>` prints every case as CSV; `lab csv <id> <arm> [seed]` prints one arm's results as the bench writes
`output/<config>.csv`, for `bench credit` and the `output/` scripts. `lab import <job>` turns the bench records an older
job collected (each directory directly under its `collected/klause-bench/output/`, records of the job's own commit
only) into a finished experiment with one arm per directory. Bench records carry no suite, so imported problems are
named by problem alone.

## Reference results

The lab keeps reference solvers' verdicts on problems in its own database, one row per (collection, problem,
solver), and an experiment's page compares each arm against them: problems both solved, solved by only one side,
proven optima reached, objectives that beat the reference's best, and the mean gap where the arm is worse. The
reference ran under its own budget, so this compares verdicts, not speed. Contradictions are listed as
disagreements, which makes the reference a soundness check: an arm proving infeasible what the reference solved, or
beating a proven reference optimum.

`lab references import [ref]` loads the bench's tables (`klause-bench/reference/*.csv` at `ref`, default `main`) from
the runner's mirror; a row already stored is replaced only by a stronger one (decided over undecided, proven over
unproven, then the better objective), so imports never lose a result. `lab references <text>` searches them. The
Reference tab shows what they cover and runs the import.

The lab has three tabs: Queue (experiments, their pages and comparisons), Regression (a schedule's trend) and
Reference.

An experiment whose cases could take more than `LAB_MAX_EXPERIMENT_HOURS` (default 24), each using its whole budget,
fails at planning with the count and the estimate; resubmit with `"confirm": true` to run it. Priorities, pause,
`parallel` and cancel act between cases (see Client).

`lab schedule <name> <ref> <experiment.json> <interval-sec>` reruns an experiment on every new commit of `<ref>`. The
runner checks it every interval, also while a job runs: when `<ref>` resolves to a commit other than the last one it
queued, and that run has ended, it queues the experiment with every arm at the new commit, named `<name>@<sha>`, which
waits its turn by priority like any other. A scheduled experiment's configs name no `ref`. An unchanged ref queues
nothing. A check asks origin for a branch or tag's commit with `git ls-remote`, one small request, and fetches only when
it moved, so a short interval such as 120 is cheap; the minimum is 60. `lab check <id>` makes a schedule due at once,
and the runner checks it within 10 s, which is also what a push hook would call. `lab schedules` lists them with the
last commit and job; `lab unschedule <id>` removes one. A schedule from
before experiments queues nothing and shows as legacy.

## Crash safety

- `api` and `runner` are separate processes that share one SQLite database (WAL, `synchronous=FULL`). A solve
  that takes the runner down leaves the queue readable.
- Every state change is committed before the next step starts. A restarted runner resumes the job it was on:
  - finished cases keep their results;
  - an interrupted case is rerun from the start;
  - the interrupted case's processes are killed first, so the rerun never runs alongside them. Each case leads its
    own process group, and the group is killed whole, including children whose shell already died. A recorded
    start time keeps a reused pid from being taken for the case.
- A case's `<n>.exit` file is written atomically once it ends: an exit code, or `timeout`.
- A service shutdown, such as a reboot, kills the running cases but does not record them, so they rerun after boot.
- The service manager restarts either process after a crash and starts both at boot without a login: launchd
  daemons on macOS, systemd user units with lingering on Linux. On macOS the runner also runs under
  `caffeinate -i`, which keeps the machine awake while it lives.

## What a case runs in

A case runs `klause-bench solve-one` in its arm's worktree, with this environment:

| variable | value |
| --- | --- |
| `KLAUSE_CLI_OPTS` | `LAB_SOLVE_JAVA_OPTS`, default `-Xmx4g -XX:ActiveProcessorCount=1 -XX:+UseSerialGC` |
| `JOB_DIR` | the job's output directory; the case writes its record under `cases/<n>/` |
| `OPENBLAS_NUM_THREADS`, `VECLIB_MAXIMUM_THREADS`, `MKL_NUM_THREADS`, `OMP_NUM_THREADS` | `1` |

Stdout and stderr go to `JOB_DIR/<n>.out` and `<n>.err`; the raw solver output and the record go to
`JOB_DIR/cases/<n>/`. The worktrees are deleted when the job ends. A failing case does not stop the experiment: the
rest still run, the job ends DONE, and the failures are counted on it. FAILED means the experiment itself could not
run, such as a failed checkout, build or selection, or a plan over the size limit.

An experiment runs one case at a time unless its spec asks for more: `parallel` runs up to that many at once, in
order, capped by `LAB_MAX_PARALLEL` (default 4). `deploy/lab parallel <id> <n>` changes it while the experiment
runs: raising it starts more cases at once, lowering it starts no more until fewer than `n` run, and never stops a
running one. Setup always runs alone. Each solve holds its own heap (`-Xmx4g` by default), so keep `parallel` times
that within the machine's memory.

`LAB_SHARED_PATHS` names worktree directories every job shares, by default `klause-bench/build/bench-cache`. Cases
run with the bench result cache off, so it serves only the bench's own tooling.

## Docker

The containerised reference solvers (SCIP, clasp, the XCSP3 cp-sat image) need Docker. `install.sh` picks it up
by itself: on macOS it installs a boot-time colima daemon when colima is installed (`brew install colima docker`), on
Linux it uses the system Docker. The runner then waits for `docker info` to answer before it takes a job. Set
`LAB_DOCKER=none` in `lab.env` to opt out. Build the images once on the server, from a klause checkout:

```sh
docker build -t klause-scip klause-bench/scip
docker build -t klause-clasp klause-bench/clasp
docker build -t klause-xcsp3-cpsat klause-bench/xcsp3-cpsat
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
4. Bench downloads each corpus collection the first time an experiment selects from it. To copy the dev PC's instead (51 GB on the
   first run, incremental after that), run `deploy/lab corpus` there; it needs the SSH access from step 2.
5. Prefer Ethernet to Wi-Fi: a Wi-Fi link that drops leaves the server unreachable while it keeps working.

Logs go to `$LAB_DATA/logs/{api,runner}.log`.

## Updating

`deploy/update.sh` on the server pulls this repository, builds it, and restarts the services on the new build. It
needs no sudo. Each build is copied into its own `$LAB_DATA/releases/<sha>-<time>/`, the services run
`releases/current`, and the three newest releases are kept. A rebuild never rewrites jars a running service still
loads classes from, which would break it until it restarts. The cases running when the services restart are rerun. Rerun `install.sh` instead when `lab.env` or the service setup itself changed.

The runner does this on its own: between jobs, at most every `LAB_UPDATE_CHECK_SEC` (default 300), it fetches the
checkout `install.sh` ran from and, when its branch's upstream is ahead, runs `update.sh`. A failed build or host
check leaves the running release in place, with the output in `$LAB_DATA/logs/update.log`. `LAB_UPDATE_CHECK_SEC=0`
turns it off.

## Client

`deploy/lab` wraps the API with curl, jq and rsync. Set `LAB_HOST` to override the default server, `192.168.50.104`.

Experiments run highest priority first (`"priority"` in the spec, default 0; `lab priority <id> <n>` changes it
later), oldest first within one priority. A running experiment checks before each case whether it was paused (`lab
pause <id>`) or a higher-priority one is waiting; if so it starts no more, lets its running cases finish, and goes
back to the queue with its worktrees and finished cases kept, resuming at its next case when it is taken again. `lab
resume <id>` releases a paused one.

`lab wait <id>...` returns once every listed job has ended, printing each one's final status. The server holds each
request open until its job ends (`GET /jobs/<id>/wait`), so a waiting client makes no repeated requests; run it in the
background to be told when a job is done.

```sh
deploy/lab run restarts-ab.json
deploy/lab ls
deploy/lab cases 7            # each case: status, arm, problem, result
deploy/lab tail 7 0           # the last 8 kB of case 0's stdout; `err` for stderr
deploy/lab csv 7 > 7.csv
deploy/lab fetch 7            # download jobs/7/ to ./lab-jobs/7 over HTTP
deploy/lab cancel 7
```

The browser page at `http://<server>:8420/` shows the running and queued experiments, with each queued one's place
in line and the cases running now, then the schedules and the history. The history filters by text and by state, a
job's name links to all its runs (a schedule's runs included), and `older` pages back past the newest 200. An
id opens its page: settings, the setup and job logs, the comparison above, every case's command with its exit code
and output, and, while it is unfinished, pause/resume, priority, parallel and cancel. One that ran every case but
saw some fail reads `DONE · N failed`, which links to just the failed cases. Jobs from before experiments keep their
pages and files. The live parts refresh in place every 10 s, while a job is unfinished.

## API

| method | path | |
| --- | --- | --- |
| `GET` | `/jobs`, `/jobs/{id}` | job and case-command states; `/jobs` takes `?limit`, `?before=<id>` and `?name` |
| `POST` | `/jobs/{id}/cancel` | a queued job is dropped; a running one has its cases' process trees killed |
| `GET` | `/jobs/{id}/files` | the files in the job directory |
| `GET` | `/jobs/{id}/files/{path}` | one file; `?tail=<bytes>` for the end of a growing log |
| `POST` | `/experiments` | an experiment spec (above) → `{"id"}`; a branch or tag origin lacks is refused; the job it queues plans its cases when it starts |
| `GET` | `/experiments/{id}/arms` | each arm's configuration and the commit it built |
| `GET` | `/experiments/{id}/cases` | each case's problem, arm, seed, status and result record |
| `GET` | `/experiments/{id}/cases.csv` | the same as CSV |
| `GET` | `/experiments/{id}/stats` | the intervals, paired tests and noisy problems as JSON |
| `GET` | `/experiments/{id}/bench.csv?arm=<label>[&seed=<n>]` | one arm's results in the bench's result-table format |
| `GET` | `/compare?jobs=<id>,<id>…` | the comparison page over several experiments |
| `POST` | `/schedules` | `{"name", "ref", "intervalSec", "experiment": <spec>}` → `{"id"}` |
| `GET` | `/schedules`; `POST` `/schedules/{id}/delete` | list or remove schedules |
| `POST` | `/schedules/{id}/check` | check the schedule's ref now instead of at its next interval |
| `POST` | `/jobs/{id}/import` | an ended job's collected bench records as a new, finished experiment → `{"id"}` |
| `GET` | `/trend[?name=<schedule>]` | a schedule's runs over its commits; the first schedule without a name |
| `GET` | `/references[?q=<text>]` | reference coverage, or the results matching text |
| `POST` | `/references/import[?ref=<ref>]` | load the bench's reference tables at ref → `{"sha", "read", "changed"}` |
| `GET` | `/experiments/{id}/reference` | each arm against the reference, with disagreements |
| `GET` | `/health` | queue counts and the koblas report |

A browser (`Accept: text/html`) gets a page for `/jobs/{id}` and `/jobs/{id}/files`; `?json` gets the data instead.

The API has no authentication. Keep it on the LAN.
