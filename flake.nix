{
  description = "agentbox: opencode + claude-code + skills + jCodeMunch + Coursier/sbt on JDK 25";

  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      name = "agentbox";
      tag = "latest";
      home = "/home/dev";
      state = "/opt/agent";

      jdk = pkgs.jdk25_headless;

      devbox = pkgs.writeShellApplication {
        name = "devbox";
        text = ''
          case "''${1:-}" in
            readme)
              exec cat /share/doc/${name}/README.md
              ;;
            jcodemunch-setup)
              shift
              exec jcodemunch-mcp init --hooks "$@"
              ;;
            skills-update)
              shift
              exec skills update "$@"
              ;;
            tools-update)
              # the bootstrap installs these once and then guards on the binary
              # existing, so this is the only way they ever move. Everything
              # else in the box (sbt, the JDK, node, the agents) is pinned by
              # the flake and moves with `nix flake update`.
              echo "==> cs update" >&2
              cs update
              echo "==> uv tool upgrade --all" >&2
              uv tool upgrade --all
              echo "==> npm update -g" >&2
              npm update -g
              ;;
            mcp)
              shift
              if [ $# -eq 0 ]; then
                echo "usage: devbox mcp <server>...   jcodemunch | metals | context7" >&2
                exit 1
              fi
              for srv in "$@"; do
                case "$srv" in
                  jcodemunch) argv='["jcodemunch-mcp"]' ;;
                  context7)   argv='["context7-mcp"]' ;;
                  # stdio, not the --client mode: that starts a long-lived HTTP
                  # server on a random port and writes a URL the agent can only
                  # use while it is up. --workspace is required but must stay
                  # RELATIVE: these files get committed, and the same repo is
                  # /work in the box and something else on the host — an
                  # absolute path is only ever right on one side.
                  metals)     argv='["metals-mcp","--workspace",".","--transport","stdio"]' ;;
                  *) echo "devbox mcp: unknown server '$srv'" >&2; exit 1 ;;
                esac

                [ -f .mcp.json ] || echo '{}' > .mcp.json
                jq --arg n "$srv" --argjson a "$argv" \
                  '.mcpServers[$n] = {command: $a[0], args: $a[1:]}' \
                  .mcp.json > .mcp.json.new && mv .mcp.json.new .mcp.json

                [ -f opencode.json ] || echo '{}' > opencode.json
                jq --arg n "$srv" --argjson a "$argv" \
                  '.mcp[$n] = {type: "local", command: $a, enabled: true}' \
                  opencode.json > opencode.json.new && mv opencode.json.new opencode.json

                echo "wired $srv into ./.mcp.json and ./opencode.json" >&2
              done
              ;;
            *)
              echo "usage: devbox readme" >&2
              echo "       devbox mcp <server>...             # jcodemunch | metals | context7" >&2
              echo "       devbox skills-update [skills...]   # skills CLI" >&2
              echo "       devbox tools-update                # cs + uv + npm" >&2
              echo "       devbox jcodemunch-setup [args...]  # jcodemunch-mcp init --hooks" >&2
              exit 1
              ;;
          esac
        '';
      };

      bootstrap = pkgs.writeShellApplication {
        name = "bootstrap";
        text = ''
          run_as_user() {
            if [ $# -eq 0 ] || [ "''${1##*/}" = "bash" ]; then
              echo "agentbox — docs: devbox readme" >&2
              if [ -n "''${AGENTBOX_DASHBOARD_URL:-}" ]; then
                echo "dashboard:   ''${AGENTBOX_DASHBOARD_URL}  (sbt dashboardServe in /work)" >&2
              else
                echo "dashboard:   no port published — restart with AGENTBOX_PORT=8080" >&2
              fi
            fi

            mkdir -p "${state}"/{bin,cache,state,uv,npm,npm-cache}

            # An app that ships a prebuilt binary (sbt, scala, cellar) is
            # installed as a two-line shim whose line 2 is
            #   exec "<abs path in the archive cache>" "$@"
            # That cache is XDG_CACHE_HOME-derived, so a shim written under a
            # different XDG_CACHE_HOME — or one whose entry got evicted — still
            # passes -x and fails only when you run it:
            #   /opt/agent/bin/scala: .../bin/scala: No such file or directory
            # Both caches live in /opt/agent now, but check the target anyway:
            # -x alone is not evidence the tool works.
            # The JVM apps (scalac, scalafmt, metals-mcp) are full bootstrap
            # scripts instead, hence anchoring to line 2 and to a leading `/` —
            # matching `exec "$JAVA"` deeper in those would refetch every boot.
            needs_cs_install() {
              local bin target
              bin="${state}/bin/$1"
              [ -x "$bin" ] || return 0
              target=$(LC_ALL=C sed -n '2s|^exec "\(/[^"]*\)".*|\1|p' "$bin" 2>/dev/null)
              [ -n "$target" ] && [ ! -e "$target" ]
            }

            missing=()
            for app in sbt scala scalac scalafmt metals-mcp; do
              if needs_cs_install "$app"; then
                rm -f "${state}/bin/$app"
                missing+=("$app")
              fi
            done
            if [ ''${#missing[@]} -gt 0 ]; then
              echo "==> cs install ''${missing[*]}" >&2
              cs install --quiet "''${missing[@]}"
            fi

            if needs_cs_install cellar; then
              rm -f "${state}/bin/cellar"
              echo "==> cs install --contrib cellar" >&2
              cs install --quiet --contrib cellar
              # cellar withholds command output behind an unanswered telemetry
              # consent prompt whenever stdout is piped — i.e. under every
              # agent. Opt out before first use.
              cellar telemetry disable --global >/dev/null 2>&1 || true
            fi

            if [ ! -x "${state}/bin/jcodemunch-mcp" ]; then
              echo "==> uv tool install jcodemunch-mcp" >&2
              uv tool install jcodemunch-mcp
            fi

            if [ ! -x "${state}/bin/skills" ]; then
              echo "==> npm install -g skills" >&2
              npm install -g --silent skills
            fi

            # --copy, not symlink: canonical store must survive restarts, and
            # the skills CLI's own agent paths move around between versions —
            # guard on the stable claude-code one
            if [ ! -e "$HOME/.claude/skills/cellar" ]; then
              echo "==> skills add VirtusLab/cellar (claude-code + opencode)" >&2
              skills add VirtusLab/cellar --skill cellar --copy -g -a claude-code -a opencode -y
            fi

            # AGENTBOX_GH=1 hands the token in as a FILE, not `-e`, so it never
            # appears in the host's `docker run` argv (world-readable via
            # /proc/<pid>/cmdline unless the host sets hidepid) nor in the
            # container config that `docker inspect` prints. Exported here so
            # every command gets it, interactive or not — /etc/bashrc would miss
            # `agentbox claude -p …`.
            if [ -r /run/gh-token ]; then
              GH_TOKEN="$(cat /run/gh-token)"
              export GH_TOKEN
            fi

            exec "$@"
          }

          # The wrapper starts us with --user, so there is no root phase to drop
          # out of. A bare `docker run` would land here as root and litter the
          # mounts with root-owned files.
          if [ "$(id -u)" = "0" ]; then
            echo "error: would run as root — start via the agentbox wrapper" >&2
            echo "(it passes --user and the matching /etc/passwd entry)" >&2
            exit 1
          fi

          run_as_user "$@"
        '';
      };

      # nixpkgs builds bash with SYS_BASHRC, so every interactive shell reads
      # this. `histappend` is the load-bearing line: HISTFILE lives on the
      # shared /opt/agent volume, and without it the last box to exit
      # overwrites what any concurrently running box recorded.
      bashrc = pkgs.writeTextDir "etc/bashrc" ''
        shopt -s histappend
        HISTSIZE=100000
        HISTFILESIZE=200000
        HISTCONTROL=ignoredups
      '';

      # GitHub's SSH host keys, so the first `git push` out of a fresh box
      # authenticates the host instead of stopping to ask.
      #
      # The box has no `~/.ssh` under the default `AGENTBOX_SSH=agent` — that
      # mode forwards the AGENT SOCKET, which carries the ability to sign but
      # says nothing about who github.com is. So ssh had the credentials and
      # not the host identity, and every fresh box hit an interactive
      # fingerprint prompt on its first push. An agent cannot answer that
      # prompt, so an unattended one hangs or fails.
      #
      # `/etc/ssh/ssh_known_hosts` is OpenSSH's own `GlobalKnownHostsFile`
      # default (`ssh -G github.com` in the image confirms it), so this needs no
      # ssh_config, no `GIT_SSH_COMMAND`, and nothing in the throwaway home.
      #
      # PINNED, deliberately, rather than `StrictHostKeyChecking=accept-new`:
      # accept-new is trust-on-first-use — it silently accepts whatever answers
      # first, which is the check, not the prompt, being turned off. These are
      # GitHub's PUBLISHED keys, so an impostor fails even on a first contact.
      # Verified against `StrictHostKeyChecking=yes`, the strictest mode, which
      # refuses an unknown host outright.
      #
      # Refresh (GitHub rotated its RSA key once, in 2023) with:
      #     gh api meta --jq '.ssh_keys[] | "github.com \(.)"'
      knownHosts = pkgs.writeTextDir "etc/ssh/ssh_known_hosts" ''
        github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
        github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=
        github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
      '';

      # Runs INSIDE the Playwright sidecar (AGENTBOX_BROWSER=1), not in the box.
      #
      # `npx playwright run-server` would be the obvious thing, but a client
      # that connects cannot send launch arguments — the server owns browser
      # startup — so the flags that make rendering deterministic have to be
      # applied here. They mirror `SmokeSuite.browserArgs`; change both or
      # neither.
      #
      # Bound to loopback on purpose: the box shares this container's network
      # namespace, so loopback IS the channel, and a browser-automation
      # endpoint is never exposed further than that.
      browserPort = "39222";
      browserServer = pkgs.writeText "agentbox-browser-server.cjs" ''
        const { chromium } = require('/pw/node_modules/playwright-core');

        chromium
          .launchServer({
            headless: true,
            host: '127.0.0.1',
            port: ${browserPort},
            wsPath: '/agentbox',
            args: [
              '--disable-gpu',
              '--disable-font-subpixel-positioning',
              '--disable-lcd-text',
              '--disable-threaded-animation',
              '--disable-threaded-scrolling',
              '--disable-in-process-stack-traces',
              '--disable-checker-imaging',
              '--force-color-profile=srgb'
            ]
          })
          .then((s) => console.log('READY ' + s.wsEndpoint()))
          .catch((e) => {
            console.error(e);
            process.exit(1);
          });
      '';

      image = pkgs.dockerTools.buildLayeredImage {
        inherit name tag;

        contents = with pkgs; [
          bashInteractive
          coreutils
          gnugrep
          gnused
          gnutar
          gzip
          findutils
          which
          less
          curl
          git
          openssh
          knownHosts # github.com's host keys, so a first push does not prompt
          gnupg # gpg + gpgconf for signed commits (AGENTBOX_GPG=agent)
          ripgrep
          fd
          jq
          gh
          opencode
          claude-code
          nodejs_24 # runtime for the skills CLI
          coursier # installs sbt + the Scala tools into /opt/agent on first run
          jdk
          async-profiler # asprof: allocation-site sampling (issue #237); JFR is built-in
          uv
          python3 # runtime for jcodemunch-mcp
          # /lib64 loader for non-nix binaries (cs-installed native tools like
          # cellar expect /lib64/ld-linux-x86-64.so.2)
          glibc
          devbox
          readme
          bashrc
          dockerTools.fakeNss
          dockerTools.caCertificates
          context7-mcp
        ];

        extraCommands = ''
          mkdir -p tmp work opt/agent \
                   home/dev/.config/opencode \
                   home/dev/.local/share/opencode \
                   home/dev/.local/state \
                   home/dev/.claude
          touch home/dev/.claude.json
          chmod 1777 tmp
          chmod -R 0777 home/dev work opt/agent
          # cs/uv/npm-installed launchers use #!/usr/bin/env; the image only
          # populates /bin
          mkdir -p usr
          ln -s /bin usr/bin
        '';

        config = {
          Entrypoint = [ "${bootstrap}/bin/bootstrap" ];
          Cmd = [ "${pkgs.bashInteractive}/bin/bash" ];
          WorkingDir = "/work";
          Env = [
            "HOME=${home}"
            # What `.claude/statusline.sh` keys the containment badge off. In
            # the IMAGE rather than the wrapper's `-e` flags on purpose: it is
            # then a property of the filesystem you are executing on, so a host
            # shell that happens to have exported it cannot make a host session
            # claim to be contained.
            "AGENTBOX=1"
            # ${jdk}/bin explicitly: the jdk in `contents` does not land a
            # /bin/java, and sbt's launcher script looks for `java` on PATH
            # (cs's own launchers would settle for JAVA_HOME)
            "PATH=${state}/bin:${jdk}/bin:/bin:/usr/bin"
            "JAVA_HOME=${jdk}"
            "COURSIER_BIN_DIR=${state}/bin"
            "COURSIER_CACHE=${state}/cache"
            "UV_TOOL_DIR=${state}/uv"
            "UV_TOOL_BIN_DIR=${state}/bin"
            "NPM_CONFIG_PREFIX=${state}"
            "NPM_CONFIG_CACHE=${state}/npm-cache"
            # keep tool state/caches in the persistent /opt/agent volume, not
            # in the container's throwaway home
            "XDG_STATE_HOME=${state}/state"
            "XDG_CACHE_HOME=${state}/cache"
            # default is ~/.bash_history, and the container home is thrown away
            # on exit — see /etc/bashrc for the append behaviour this needs
            "HISTFILE=${state}/state/bash_history"
            # The Java Playwright driver ships its own node, which cannot
            # execute under nix; without this `Playwright.create()` dies with
            # "Failed to read message from driver, pipe closed" — which reads
            # like a missing browser and is not one. Unconditional: it costs
            # nothing when no browser tests run.
            "PLAYWRIGHT_NODEJS_PATH=${pkgs.nodejs_24}/bin/node"
            # claude-code comes from the read-only store — never let it
            # self-update. Belt and braces: nixpkgs' own wrapper --sets this
            # too, so it stays right even if this line is lost.
            "DISABLE_AUTOUPDATER=1"
            # nixpkgs' wrapper --set-defaults this to 1, which would have the
            # box fetch and install plugin updates into your HOST
            # ~/.claude/plugins on every start. Only 1/true/yes/on read as true,
            # so 0 turns it off and our value wins over a --set-default.
            "FORCE_AUTOUPDATE_PLUGINS=0"
            # NOT CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC / DISABLE_TELEMETRY /
            # DO_NOT_TRACK: any of the three turns off feature-flag evaluation,
            # which takes Remote Control and the /usage panel down with it
            # ("Failed to load usage data"). These two are the narrow knobs that
            # don't touch it.
            "DISABLE_ERROR_REPORTING=1"
            "DISABLE_BUG_COMMAND=1"
            # nix glibc's loader only searches the store by default; cs-installed
            # native binaries (cellar) need this to find their extra libs
            "LD_LIBRARY_PATH=${pkgs.zlib}/lib"
            "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
            "TERM=xterm-256color"
            "LANG=C.UTF-8"
          ];
        };
      };

      readme = pkgs.writeTextDir "share/doc/${name}/README.md" ''
        # agentbox

        Single Nix flake producing an OCI image with opencode, Claude Code,
        the jCodeMunch MCP server, and a Coursier-managed
        Scala toolchain on JDK 25. Agent configs and auth are bind-mounted from
        the host, so the container shares your existing setup.

        ## Use

            nix run .#                 # bash in the container, cwd as /work
            nix run .# -- opencode
            nix run .# -- claude
            # inside the box:
            devbox readme              # this document
            devbox mcp jcodemunch      # wire an MCP server into both agents
            devbox skills-update       # update installed agent skills
            devbox tools-update        # update the cs / uv / npm tools

        Unfree packages (claude-code) are allowed in the flake itself — no env
        vars or `--impure` needed.

        Nothing is published to the host by default. `AGENTBOX_PORT=8080`
        exposes the Datastar dashboard, any other value remaps it:

            AGENTBOX_PORT=8081 nix run .#   # dashboard on localhost:8081

        The server inside the box must bind `0.0.0.0`, not `127.0.0.1` —
        docker forwards a published port to the container's eth0, so a
        loopback-bound server is unreachable and `-p` looks silently broken.
        For this repo's dashboard that is `HOST=0.0.0.0` in `.env`.

        ### Telling the box from the host

        A box and a host terminal look identical once you are a few commands
        in, and the two are not the same thing to be wrong about — one has your
        real keys, your real gh account and your whole home directory. So the
        repo ships a Claude Code status line (`.claude/statusline.sh`, wired
        from `.claude/settings.json`) whose first row answers that question
        before anything else:

            ▣ agentbox · ssh gh gpg :8080 │ ⑂ my-branch ●3 ↑2 │ ⌂ a-worktree
            Opus │ ▓▓▓░░░░░░░ 31% │ $1.23 │ 14m

        against a red `△ host  not contained` when it is not in a box. The
        badge keys off `AGENTBOX=1`, which is baked into the image rather than
        passed by the wrapper — so it describes the filesystem the script is
        running on, and a host shell that happens to have exported the variable
        cannot borrow the badge.

        The chips after it are the point, not decoration: they are what was
        actually handed in, each detected from inside the box (a socket, a
        mounted file) rather than from the `AGENTBOX_*` variable that asked for
        it. `unsigned` appears when no gpg socket arrived, which is the one
        state worth noticing before you commit rather than after.

        ### Browser tests

        There is no browser in this image. `AGENTBOX_BROWSER=1` starts a
        Playwright sidecar next to the box instead, and the smoke suites
        connect to it:

            AGENTBOX_BROWSER=1 nix run .#
            # inside: sbt 'fh-datastar-view/testFull'

        Without it the box has no browser at all and those suites die in
        `beforeAll`, so run them as `sbt 'fh-datastar-view/testOnly * --
        --exclude-tags=Slow'`.

        The sidecar image is `mcr.microsoft.com/playwright:v<version>`, with the
        version read out of `build.sbt` — the Java client pin decides it, so the
        two halves cannot drift apart. The first start installs
        `playwright-core` into `~/.agentbox/playwright` and takes a minute;
        later starts reuse it.

        `ComponentVisualSuite` is expected to differ here: six of its seven
        snapshots match, and `entity-card-off` lands at 0.308% changed pixels
        against a 0.3% budget, because font rasterization is not identical to
        CI's runner. **CI stays authoritative for the visual baselines** — do
        not regenerate them from inside the box.

        The sidecar deliberately does **not** get `--ipc=host`, which
        Playwright's own docs recommend for Chromium. That flag hands the
        container the host's IPC namespace — host shared memory and SysV IPC
        objects, shared with every other process on the machine — which is a
        real weakening of the boundary this box exists to draw, and it would be
        the only place we punched a hole in it for convenience. The flag exists
        to stop Chromium exhausting `/dev/shm` under load; nothing here has hit
        that (28 tests, three suites in parallel). If it ever does, the symptom
        is a browser CRASH partway through a suite rather than a test failure —
        raise the sidecar's `--shm-size` first, which costs nothing in
        isolation, and treat `--ipc=host` as a last resort to argue for
        explicitly.

        `/work` is your working tree, `target/` included, so the box and the
        host share one set of build outputs. Alternating `sbt` between them can
        leave incremental state the other does not recognise, which surfaces as
        a compiler crash rather than anything that names the cause —
        `NoSuchFileException ...semanticdb` or *"Cannot invoke
        AbstractFile.jpath() because clsFile is null"*. `sbt <module>/clean`
        fixes it. Not specific to browser tests; it is just easiest to hit when
        you run the same suite both ways.

        Under `AGENTBOX_BROWSER=1` the sidecar owns the network namespace and
        the box joins it, so `AGENTBOX_PORT` is published by the sidecar. The
        two compose normally; it is only worth knowing if you go looking for the
        published port on the wrong container.

        The wrapper loads the image into Docker when the build changes. Inside
        the box, `devbox readme` shows this document. Requires an
        x86_64-linux builder; on macOS/aarch64 add a linux builder or change
        `system` and confirm `pkgs.opencode` / `pkgs.claude-code` build there.

        ## Two halves

        The **host half** is one shell script, `agentbox` — it loads the image
        when the build changed, writes the passwd file, decides the mounts and
        the port, and calls `docker run`. That script is the only thing that
        runs outside the container, so anything that must be arranged *before*
        the box starts belongs there: an egress proxy to CONNECT through, a
        docker network to attach to, a seccomp profile.

        The **container half** is the image plus its `bootstrap` entrypoint,
        which installs the Coursier/uv/npm tools into `/opt/agent` on first run
        and then execs your command.

        There is deliberately no devShell. This flake builds a box to work in;
        it does not put a toolchain on your host, and a shell you enter first
        could not do the host-side setup above anyway — it only adds binaries
        to your PATH.

        ## Shared with the host

        | Host                          | Container                 |
        |-------------------------------|---------------------------|
        | `~/.claude`                   | `~/.claude`               |
        | `~/.claude.json`              | `~/.claude.json`          |
        | `~/.config/opencode`          | `~/.config/opencode`      |
        | `~/.local/share/opencode`     | `~/.local/share/opencode` |
        | `~/.agentbox`                 | `/opt/agent`              |
        | `~/.agentbox/home-cache`      | `~/.cache`                |
        | `~/.agentbox/home-sbt`        | `~/.sbt`                  |
        | `~/.agentbox/home-agents`     | `~/.agents`               |
        | `$PWD`                        | `/work`                   |
        | `~/.gitconfig`                | `~/.gitconfig` (ro)       |

        Opt-in only (see Git & GitHub):

        | Host                          | Container                 | Enabled by         |
        |-------------------------------|---------------------------|--------------------|
        | `$SSH_AUTH_SOCK`              | `/run/ssh-agent.sock`     | `AGENTBOX_SSH=agent` |
        | `~/.ssh`                      | `~/.ssh` (ro)             | `AGENTBOX_SSH=keys`  |
        | `gh auth token`               | `/run/gh-token` (ro, tmpfs) | `AGENTBOX_GH=1`    |
        | `~/.agentbox/playwright`      | `/pw` (in the sidecar, not the box) | `AGENTBOX_BROWSER=1` |

        Signing is the exception to opt-in — if your git config asks for it, the
        gpg mounts are on by default and the box will not start without them:

        | Host                          | Container                 | Enabled by         |
        |-------------------------------|---------------------------|--------------------|
        | gpg-agent's *extra* socket    | `~/.gnupg/S.gpg-agent`    | `commit.gpgsign`   |
        | `pubring.kbx`, `trustdb.gpg`  | `~/.gnupg` (copy, public half) | `commit.gpgsign` |

        Claude Code and opencode both live in the Nix store or `/opt/agent`
        and are pinned; only their state is shared. Sessions, project history
        and settings written inside the container land in your real `~/.claude`.

        On Linux the Claude Code credential file is `~/.claude/.credentials.json`
        and is therefore inside the container. On macOS credentials are in the
        Keychain, so the container starts logged out — export `ANTHROPIC_API_KEY`
        or run `claude` once inside and log in. Either way, auth tokens are
        readable by anything running in there. Trusted repos only; a container
        is not an exfiltration boundary.

        The Scala toolchain — `sbt`, `scala`, `scalac`, `scalafmt` — is
        Coursier-installed into `/opt/agent` on first run; network needed once,
        then cached. The agents, the JDK and everything else come pinned from
        nixpkgs. Tool caches and XDG state/cache also live in `/opt/agent`, so
        they survive container restarts.

        ## Permissions

        Nothing in the box ever runs as root. The wrapper passes
        `--user $(id -u):$(id -g)`, `--security-opt no-new-privileges` and
        `--cap-drop=ALL`, and mounts a generated `/etc/passwd` naming that uid
        `dev` (docker's `--user` adds no NSS entry of its own, and a uid without
        one makes Node's `os.userInfo()` throw). Files created in `/work` and in
        the mounts are owned by you on the host — no root-owned strays.

        Claude Code's own allow/deny rules are a separate, softer layer that it
        enforces itself; put them in the repo's `.claude/settings.json`, not
        `settings.local.json`, which "don't ask again" rewrites. The mount list
        above is the real boundary — nothing else on the host is reachable.

        ## What the box can reach, and what we accept

        Verified from inside: uid is yours and non-root, `CapEff` and `CapBnd`
        are both empty, `NoNewPrivs` is 1, there is no docker socket, the PID
        namespace is its own, and `/nix/store` and `/bin` are not writable. The
        container cannot see any host path that is not in the mount list.

        Read-only on purpose, because your host Claude Code EXECUTES or injects
        them on its next run and a writable copy would be a container-to-host
        escape: `~/.claude/settings.json` (hooks are shell commands) and
        `~/.claude/CLAUDE.md` (global instructions for every project). Plugin
        auto-update is off for the same reason — otherwise the box would install
        plugin updates into your host `~/.claude/plugins` on every start.

        Accepted, in rough order of how much they would cost an attacker:

        - **Anything in the mounts can be read and sent anywhere.** Egress is
          unrestricted: the public internet and your LAN, including the Home
          Assistant instance, are both reachable. That covers the repo's
          gitignored `.env`, your Claude OAuth token in
          `~/.claude/.credentials.json`, and every past session transcript under
          `~/.claude/projects`. A container is not an exfiltration boundary.
        - **`~/.claude.json` and `~/.claude/skills` stay writable.** The first
          can define MCP servers, the second is instructions your host agent
          loads. They cannot be locked: Claude Code rewrites `~/.claude.json`
          constantly, and `skills add -g` targets the skills dir by design.
        - **`/work` is writable, including `.claude/settings.json` and
          `.mcp.json` in the repo.** Project hooks and MCP servers land in your
          diff, so review before running the repo's tooling on the host.
        - **The bootstrap installs unpinned code on first run.** `cs`, `uv` and
          `npm` all resolve latest, and `skills add VirtusLab/cellar -y` writes
          an unpinned third-party skill into your real `~/.claude/skills`.
        - **`AGENTBOX_PORT` binds `0.0.0.0`**, so a published dashboard is on the
          LAN, not just localhost. That is deliberate (phone access); prefix the
          value with `127.0.0.1:` if you would rather it were not.
        - **`AGENTBOX_GH=1` puts a token inside the box**, where anything
          running there can read it. Unlike SSH and gpg there is no narrower
          option: those forward an AGENT, so the box can ask for an operation
          without holding the key, and GitHub has no such thing — a token IS the
          credential.

          It is handed over as a bind-mounted file rather than `-e`, so it is in
          neither the host's `docker run` argv (readable by ANY local user via
          /proc/<pid>/cmdline, unless the host sets `hidepid` — wider than root)
          nor the container config `docker inspect` prints. The file is made
          with `mktemp` in `/dev/shm`, which is tmpfs, and unlinked when the box
          exits; `/tmp` is ordinary disk on a default install, so it is not the
          place for it. Swap means tmpfs is not an absolute guarantee.

          The narrower way in remains a fine-grained PAT (below): it limits what
          the token can DO, which beats limiting who can see one that carries
          your full scopes.
        - **The forwarded gpg socket lets the box sign anything, for as long as
          it runs** — a commit you did not write, a mail, a release tarball.
          Accepted deliberately, and unlike the other rows it is on by default,
          because unsigned commits were judged the worse outcome. It is the
          *extra* socket precisely to bound it: use ends when the container
          does, because the key itself never went in. `AGENTBOX_GPG=off` opts
          out.
        - **`AGENTBOX_BROWSER=1` runs a third-party image alongside the box**
          (`mcr.microsoft.com/playwright`), sharing its network namespace — so
          it reaches whatever the box reaches, and a browser is a general
          fetcher. It gets no host mounts beyond its own `playwright-core`
          directory, and its automation port is bound to loopback inside that
          shared namespace, so nothing outside the pair can drive it. Off by
          default.

          The obvious simplification — point the box at a Playwright server
          running on the HOST, which already has the browsers — is rejected on
          purpose. A Playwright server has no ACL or restricted mode, so it
          would let the box drive a browser running as you, `file:///` reads of
          `~/.ssh` included, i.e. everything the box deliberately has no mount
          for. Putting the browser in a container keeps it inside the same
          boundary as the box; that is the whole point of the sidecar.

        The box is a boundary against *accidents and reach*, not against a
        determined attacker who already has code running inside it. Trusted
        repos only.

        ## Git & GitHub

        `git` and `gh` are in the image and `.gitconfig` is mounted read-only,
        so commits authored inside carry your identity. **Credentials are not
        mounted unless you ask for them** — by default the box can commit but
        cannot push, clone a private repo, or touch the GitHub API.

            AGENTBOX_SSH=agent nix run .#    # forward the host ssh-agent socket
            AGENTBOX_SSH=keys  nix run .#    # bind-mount ~/.ssh read-only
            AGENTBOX_GH=1      nix run .#    # pass `gh auth token` as GH_TOKEN

        Prefer `agent`. The socket lets the box ASK your agent to sign, so the
        private keys themselves never enter it — a compromised box can use the
        key while it runs, but cannot keep it afterwards. `keys` puts the key
        material inside, and is only needed for a passphrase-less key with no
        agent running, or when something reads `~/.ssh/config`.

        **github.com's host keys ship in the image** (`/etc/ssh/ssh_known_hosts`,
        which is OpenSSH's own global default). `agent` forwards the ability to
        SIGN but says nothing about who github.com is, and the box has no
        `~/.ssh` in that mode — so without this the first push out of every
        fresh box stopped on an interactive fingerprint prompt, which an agent
        cannot answer.

        They are GitHub's published keys, PINNED — not
        `StrictHostKeyChecking=accept-new`, which would silence the prompt by
        trusting whatever answers first. Refresh them (GitHub rotated its RSA
        key once, in 2023) with:

            gh api meta --jq '.ssh_keys[] | "github.com \(.)"'

        `AGENTBOX_GH=1` passes a token rather than mounting `~/.config/gh`, for
        two reasons: a mounted config dir is the box's to rewrite (it can change
        which account your *host* `gh` authenticates as), and on a home-manager
        host `config.yml` is a `/nix/store` symlink that dangles inside the
        container, so `gh` fails outright with *"failed to write config after
        migration"*. The token still carries every scope your `gh` login has —
        for narrow, separately revocable access, export `GH_TOKEN` yourself from
        a fine-grained PAT and leave `AGENTBOX_GH` unset:

            GH_TOKEN=github_pat_… nix run .#

        ### Signed commits

        **This one is not opt-in.** If `git config commit.gpgsign` is true, the
        box signs — and if it cannot, it refuses to start. The alternative was a
        box that quietly produces unsigned commits, and an unsigned commit that
        looks fine in the log is a worse outcome than a startup error.

            nix run .#                      # signs, because your git config says so
            AGENTBOX_GPG=agent nix run .#   # force it on regardless of git config
            AGENTBOX_GPG=off   nix run .#   # deliberately unsigned; warns on the way in

        Signing is a different subsystem from ssh auth, and the difference bites
        if gpg-agent is also your ssh agent: `AGENTBOX_SSH=agent` forwards
        `S.gpg-agent.ssh`, which speaks only the ssh-agent protocol. It
        authenticates `git push`; it cannot produce an OpenPGP signature.

        What gets forwarded is `S.gpg-agent.extra` — the socket gnupg provides
        for handing an agent to a machine you trust less. It serves a restricted
        command set, so the box can ask for a signature but
        `gpg --export-secret-keys` is refused by the agent. Only the public half
        of your keyring (`pubring.kbx`, `trustdb.gpg`) goes in, as a *copy* under
        `~/.agentbox/gnupg`, so the box cannot alter your real `~/.gnupg` either.
        A copy rather than a read-only mount because gpg needs a writable home
        for its lockfiles and random seed.

        One surprise worth knowing: `gpg -K` may list nothing over a restricted
        socket while signing works fine. The agent refuses the key *listing*,
        not the signing — so test with `gpg --clearsign`, not `gpg -K`.

        ## Agent skills

        The [skills CLI](https://skills.sh) manages agent skills for both
        claude-code and opencode; installs land in your real `~/.claude/skills`
        and `~/.config/opencode/skills`:

            skills add <owner/repo> -a claude-code -a opencode
            devbox skills-update       # = skills update

        Cellar's skill is preinstalled for both agents, so they know when to
        reach for it.

        ## MCP servers

        `devbox mcp` writes both agents' config in the current workspace —
        `.mcp.json` for Claude Code, `opencode.json` for opencode — merging into
        whatever is already there:

            devbox mcp jcodemunch metals context7

        - **jcodemunch** — index a repo once, then the agent retrieves by symbol
          instead of reading whole files. Free for personal use, paid for
          commercial.
        - **metals** — Metals' standalone MCP server, wired over stdio so it
          starts on demand.
        - **context7** — up-to-date library documentation.

        ## Scala API lookups

        **cellar** gives type signatures, members and docs for any Maven
        artifact from the terminal, with no server and no MCP wiring:

            cellar get-external org.typelevel:cats-core_3:latest cats.Monad

        Telemetry is opted out during bootstrap — its consent prompt would
        otherwise withhold output from piped/agent invocations.

        ## Profiling

        JFR is already in the JDK — zero install for issue #237's metric:
        `-prof jfr` in JMH, or `-XX:StartFlightRecording` / `jcmd` against the
        running server. `-prof gc` (JMH) gives the per-op allocation TOTAL but
        not the sites; to NAME the allocation sites use async-profiler, which
        ships in the image as `asprof`, attachable to the running add-on:

            jps   # find the server pid
            asprof -d 30 -e alloc -f /tmp/alloc.html <pid>   # where the MB go
            asprof -d 30 -e cpu   -f /tmp/cpu.jfr   <pid>    # wall/cpu view

        `alloc` and `wall` need no kernel perf access (fine under this
        container's `--cap-drop=ALL`); `cpu` does, so run cpu profiles on the
        Pi, whose unprivileged perf I denied nothing. In JMH the equivalent is
        `-prof async:event=alloc,libPath=<async-profiler store>/lib/libasyncProfiler.so` —
        find it with `find $(dirname $(readlink -f $(which asprof))) -name
        'libasyncProfiler.so'`.

        ## Pinning

        Two populations, two update paths.

        **Pinned by the flake lock** — opencode, claude-code, the JDK, Node and
        the Coursier launcher itself. `nix flake update` is the only way they
        move.

        **Installed once into `/opt/agent`, resolving latest** — `sbt`, `scala`,
        `scalac`, `scalafmt`, `metals-mcp`, `cellar` (Coursier);
        `jcodemunch-mcp` (uv); `skills` (npm). The bootstrap guards on
        the binary existing, so they never move on their own:

            devbox tools-update        # cs update + uv tool upgrade + npm update -g

        The `sbt` here is only the launcher; which sbt actually builds your
        project is `sbt.version` in `project/build.properties`, so the launcher
        drifting ahead is harmless.

        Pin one by hand if it matters:

            uv tool install jcodemunch-mcp==1.20.0
            npm install -g skills@1.5.23

        ## Escape hatches

            nix build .#image       # just the tarball
            AGENTBOX_RELOAD=1 nix run .#   # re-load the image into Docker

        If sbt/zinc throws `NoSuchFileException` on `.semanticdb` files, the
        shared `target/` holds partial state from an interrupted run —
        `rm -rf target` and recompile.
      '';

      agentbox = pkgs.writeShellApplication {
        name = name;
        text = ''
          IMG="${name}:${tag}"
          # the store path identifies the exact build; tagging it lets a plain
          # inspect decide freshness without touching the 1.5G tarball
          BUILD_TAG="${name}:build-$(basename "${image}")"

          command -v docker >/dev/null || { echo "docker not found on PATH" >&2; exit 1; }

          # ONE trap for everything that must not outlive this script — a second
          # `trap ... EXIT` would silently replace the first, so the sidecar and
          # the credential file cannot each own one. Both are declared here so
          # the handler is safe under `set -u` even if it fires before either is
          # set.
          CLEANUP_FILES=()
          BROWSER_NAME=""
          # shellcheck disable=SC2329  # invoked by the trap below, not directly
          cleanup() {
            if [ -n "$BROWSER_NAME" ]; then
              docker rm -f "$BROWSER_NAME" >/dev/null 2>&1 || true
            fi
            if [ ''${#CLEANUP_FILES[@]} -gt 0 ]; then
              rm -f "''${CLEANUP_FILES[@]}"
            fi
          }
          trap cleanup EXIT INT TERM

          if [ "''${AGENTBOX_RELOAD:-0}" = "1" ] || ! docker image inspect "$BUILD_TAG" >/dev/null 2>&1; then
            echo "==> loading $IMG" >&2
            docker load --input ${image} >&2
            docker tag "$IMG" "$BUILD_TAG"
          fi

          CFG="''${XDG_CONFIG_HOME:-$HOME/.config}/opencode"
          DATA="''${XDG_DATA_HOME:-$HOME/.local/share}/opencode"
          STATE="$HOME/.agentbox"
          mkdir -p "$CFG" "$DATA" "$STATE"/{home-cache,home-sbt,home-agents,nss,gnupg} "$HOME/.claude"

          # Everything runs as your host uid via --user, which docker maps
          # without adding an NSS entry for it — and a uid with no passwd entry
          # makes Node's os.userInfo() throw ENOENT. Supply the entry from here
          # (superset of the image's fakeNss files) rather than having an
          # entrypoint mutate /etc/passwd as root.
          {
            echo "root:x:0:0:root user:/var/empty:/bin/sh"
            echo "nobody:x:65534:65534:nobody:/var/empty:/bin/sh"
            echo "dev:x:$(id -u):$(id -g)::${home}:/bin/bash"
          } > "$STATE/nss/passwd"
          {
            echo "root:x:0:"
            echo "nobody:x:65534:"
            echo "dev:x:$(id -g):"
          } > "$STATE/nss/group"

          MOUNTS=(
            -v "$STATE/nss/passwd:/etc/passwd:ro"
            -v "$STATE/nss/group:/etc/group:ro"
            -v "$HOME/.claude:/home/dev/.claude"
            -v "$HOME/.claude.json:/home/dev/.claude.json"
            -v "$CFG:/home/dev/.config/opencode"
            -v "$DATA:/home/dev/.local/share/opencode"
            -v "$STATE:/opt/agent"
            -v "$STATE/home-cache:/home/dev/.cache"
            -v "$STATE/home-sbt:/home/dev/.sbt"
            -v "$STATE/home-agents:/home/dev/.agents"
            -v "$PWD:/work"
          )

          # ~/.claude has to be writable — sessions, transcripts and the OAuth
          # refresh all land in it. But two files in there are EXECUTED or
          # injected by your host Claude Code on its next run: settings.json
          # defines hooks (shell commands), and CLAUDE.md is the global
          # instruction file for every project. Writable, they are a
          # container-to-host escape: the box edits one, you run claude on the
          # host, the host runs what the box wrote. Neither is written during
          # normal use, so both go back read-only on top of the rw mount.
          # (Nested binds resolve by depth, so mount order does not matter.)
          for f in settings.json CLAUDE.md; do
            [ -e "$HOME/.claude/$f" ] && MOUNTS+=(-v "$HOME/.claude/$f:/home/dev/.claude/$f:ro")
          done

          # .gitconfig carries no secret and authorship would be wrong without
          # it, so it is the one identity file mounted unconditionally.
          if [ -f "$HOME/.gitconfig" ]; then MOUNTS+=(-v "$HOME/.gitconfig:/home/dev/.gitconfig:ro"); fi

          # Git/GitHub credentials are OPT-IN — the box is a containment
          # boundary, and anything running in it can read every mount.
          #   AGENTBOX_SSH=agent  forward the host ssh-agent socket. Preferred:
          #                       the box can ASK the agent to sign, but the
          #                       private keys never enter it.
          #   AGENTBOX_SSH=keys   bind-mount ~/.ssh read-only (keys ARE in the
          #                       box; only needed for a passphrase-less key
          #                       with no agent, or for ~/.ssh/config).
          #   AGENTBOX_GH=1       pass `gh auth token` in as GH_TOKEN. Not a
          #                       mount: ~/.config/gh is the box's to REWRITE if
          #                       mounted, and on a home-manager host its
          #                       config.yml is a /nix/store symlink that dangles
          #                       inside the container ("failed to write config
          #                       after migration"). The token still carries your
          #                       full scopes — see the README for the narrower
          #                       fine-grained-PAT route.
          #   AGENTBOX_GPG=agent  forward gpg-agent's EXTRA socket for signed
          #                       commits. Same trade as SSH=agent: the box can
          #                       ask for a signature, never holds the key.
          case "''${AGENTBOX_SSH:-}" in
            agent)
              if [ -z "''${SSH_AUTH_SOCK:-}" ] || [ ! -S "$SSH_AUTH_SOCK" ]; then
                echo "AGENTBOX_SSH=agent but no usable SSH_AUTH_SOCK on the host" >&2
                exit 1
              fi
              MOUNTS+=(-v "$SSH_AUTH_SOCK:/run/ssh-agent.sock" -e SSH_AUTH_SOCK=/run/ssh-agent.sock)
              ;;
            keys)
              if [ ! -d "$HOME/.ssh" ]; then echo "AGENTBOX_SSH=keys but ~/.ssh does not exist" >&2; exit 1; fi
              MOUNTS+=(-v "$HOME/.ssh:/home/dev/.ssh:ro")
              ;;
            "") ;;
            *) echo "AGENTBOX_SSH must be 'agent', 'keys' or unset" >&2; exit 1 ;;
          esac

          # Commit SIGNING is a different subsystem from ssh auth: with
          # gpg.format unset git shells out to gpg, which needs an agent socket
          # — AGENTBOX_SSH=agent forwards S.gpg-agent.ssh, which speaks only the
          # ssh-agent protocol and cannot produce an OpenPGP signature.
          # The EXTRA socket is the one gnupg documents for handing an agent to
          # a less-trusted machine: restricted command set, cannot export secret
          # keys. That is the whole reason it is this socket and not S.gpg-agent.
          # NOT opt-in, unlike the credential mounts above. A box that cannot
          # sign is not a usable box here: `commit.gpgsign` is on, so the choice
          # is between signing and a silent downgrade to unsigned commits, and
          # an unsigned commit that LOOKS fine is the worse failure. So the
          # default follows your git config, and a box that is asked to sign but
          # cannot refuses to start rather than discovering it at commit time.
          #   unset / auto  sign iff `git config commit.gpgsign` is true
          #   agent         always forward; error if the socket is unusable
          #   off           explicit, loud opt-out — unsigned commits
          GPG_MODE="''${AGENTBOX_GPG:-auto}"
          if [ "$GPG_MODE" = "auto" ]; then
            if [ "$(git config --get commit.gpgsign 2>/dev/null || true)" = "true" ]; then
              GPG_MODE=agent
            else
              GPG_MODE=off
            fi
          fi

          case "$GPG_MODE" in
            agent)
              GPG_SOCK="$(gpgconf --list-dirs agent-extra-socket 2>/dev/null || true)"
              if [ -z "$GPG_SOCK" ] || [ ! -S "$GPG_SOCK" ]; then
                echo "agentbox: commits must be signed, but the host gpg-agent extra socket is not available." >&2
                echo "  expected: ''${GPG_SOCK:-<gpgconf returned nothing>}" >&2
                echo "  fix:      gpgconf --launch gpg-agent" >&2
                echo "  or:       AGENTBOX_GPG=off nix run .#   (unsigned commits, deliberately)" >&2
                exit 1
              fi
              if [ ! -f "$HOME/.gnupg/pubring.kbx" ]; then
                echo "agentbox: commits must be signed, but ~/.gnupg/pubring.kbx does not exist —" >&2
                echo "there is no public keyring to give the box. Check 'gpg --list-keys'." >&2
                exit 1
              fi
              # The public half is COPIED, not mounted read-only: gpg wants a
              # writable GNUPGHOME for its lockfiles and random seed, and the
              # host dir is not up for that — it holds private-keys-v1.d.
              # Refreshed every start so a newly created key shows up.
              for f in pubring.kbx trustdb.gpg; do
                if [ -f "$HOME/.gnupg/$f" ]; then
                  install -m 600 "$HOME/.gnupg/$f" "$STATE/gnupg/$f"
                fi
              done
              chmod 700 "$STATE/gnupg"
              # With the socket mounted gpg just connects. Without it, gpg would
              # START a local agent that has no keys and no pinentry and then
              # hang; no-autostart turns that into an immediate, readable error.
              echo "no-autostart" > "$STATE/gnupg/gpg.conf"
              # gpg picks /run/user/$UID/gnupg for its socket dir only when that
              # exists; it does not in the container, so the socket goes at
              # $GNUPGHOME/S.gpg-agent and no /run/user needs creating.
              MOUNTS+=(
                -v "$STATE/gnupg:/home/dev/.gnupg"
                -v "$GPG_SOCK:/home/dev/.gnupg/S.gpg-agent"
                -e GNUPGHOME=/home/dev/.gnupg
              )
              # A restricted connection cannot tell the agent which tty to
              # prompt on, so a pinentry would surface wherever the agent last
              # learned. Point it at this terminal while we still have one.
              gpg-connect-agent updatestartuptty /bye >/dev/null 2>&1 || true
              ;;
            off)
              # Only reachable by asking for it, or by a git config that does
              # not want signing in the first place. Say so on the way in: an
              # unsigned commit is not something to discover later from the log.
              if [ -n "''${AGENTBOX_GPG:-}" ]; then
                echo "agentbox: AGENTBOX_GPG=off — commits from this box will be UNSIGNED" >&2
              fi
              # git's config ENVIRONMENT outranks every config file, so this
              # settles signing without the box being able to rewrite the
              # read-only ~/.gitconfig mount. Claims GIT_CONFIG_COUNT wholesale:
              # a GIT_CONFIG_* you exported yourself is overridden here.
              MOUNTS+=(
                -e GIT_CONFIG_COUNT=1
                -e GIT_CONFIG_KEY_0=commit.gpgsign
                -e GIT_CONFIG_VALUE_0=false
              )
              ;;
            *) echo "AGENTBOX_GPG must be 'agent', 'off', 'auto' or unset" >&2; exit 1 ;;
          esac

          if [ "''${AGENTBOX_GH:-0}" = "1" ]; then
            if ! GH_TOKEN="$(gh auth token 2>/dev/null)" || [ -z "$GH_TOKEN" ]; then
              echo "AGENTBOX_GH=1 but 'gh auth token' returned nothing — run 'gh auth login' first" >&2
              exit 1
            fi
          fi
          # also forwards a GH_TOKEN you exported yourself (e.g. a fine-grained
          # PAT), which is the narrower way in
          #
          # Handed over as a FILE rather than `-e`: an `-e` lands in the host's
          # `docker run` argv, which any local user can read out of
          # /proc/<pid>/cmdline, and in the container config `docker inspect`
          # prints. A bind-mounted file is in neither. `bootstrap` exports it
          # inside the box.
          #
          # /dev/shm because it is tmpfs: unlike /tmp, which is ordinary disk on
          # a default install, the token never lands in the filesystem. Not an
          # absolute guarantee — tmpfs pages can be swapped — but a swapped page
          # is not a file anyone can open, and this one is unlinked on exit.
          if [ -n "''${GH_TOKEN:-}" ]; then
            if [ -d /dev/shm ] && [ -w /dev/shm ]; then
              GH_TOKEN_FILE="$(mktemp --tmpdir=/dev/shm agentbox-gh.XXXXXXXX)"
            else
              GH_TOKEN_FILE="$(mktemp)"
            fi
            CLEANUP_FILES+=("$GH_TOKEN_FILE")
            # Written after creation, which mktemp already made 0600, so the
            # token is never briefly world-readable.
            printf '%s' "$GH_TOKEN" > "$GH_TOKEN_FILE"
            MOUNTS+=(-v "$GH_TOKEN_FILE:/run/gh-token:ro")
          fi

          # must exist as a file, or docker creates a directory in its place
          [ -f "$HOME/.claude.json" ] || echo '{}' > "$HOME/.claude.json"

          # Publishing is OPT-IN: AGENTBOX_PORT=8080 exposes the Datastar
          # dashboard, any other value remaps it. Nothing is published by
          # default, so a box never collides with a dashboard already running
          # on the host.
          # Kept as TWO arrays because they belong to different containers when
          # a browser sidecar is running: the publish flag follows whoever owns
          # the network namespace, while the banner URL is always the box's.
          # Bundling them meant clearing the publish flag also silently took the
          # URL away, and the box then announced "no port published" about a
          # port that was published and working.
          PORT="''${AGENTBOX_PORT:-}"
          if [ -n "$PORT" ]; then
            PUBLISH_ARGS=(-p "''${PORT}:8080")
            # AGENTBOX_PORT may carry a host-interface prefix
            # (127.0.0.1:8080), which is not part of the URL.
            DASHBOARD_ARGS=(-e "AGENTBOX_DASHBOARD_URL=http://localhost:''${PORT##*:}")
          else
            PUBLISH_ARGS=()
            DASHBOARD_ARGS=()
          fi

          # -t only with a real tty, so `agentbox claude -p …` still works in a
          # pipe or from CI ("the input device is not a TTY")
          if [ -t 0 ] && [ -t 1 ]; then TTY_ARGS=(-it); else TTY_ARGS=(-i); fi

          # AGENTBOX_BROWSER=1 starts a Playwright sidecar so the browser smoke
          # tests can run in the box, which otherwise has no browser at all.
          #
          # The sidecar OWNS the network namespace and the box joins it, rather
          # than the other way round, for two reasons. Docker refuses to publish
          # a port on a container using another's namespace ("conflicting
          # options: port publishing and the container type network mode"), so
          # whoever owns the namespace must carry AGENTBOX_PORT. And the box is
          # started last because it is the foreground process — there is no
          # moment after it starts at which this script could bring anything up.
          #
          # Sharing the namespace is the whole trick: the test server binds
          # 127.0.0.1 on an OS-assigned port, so a browser anywhere else has no
          # route to the page under test and no port to publish.
          NET_ARGS=()
          if [ "''${AGENTBOX_BROWSER:-0}" = "1" ]; then
            # One pin for both halves: the Java client in build.sbt decides the
            # image tag, so they cannot drift into a protocol mismatch.
            PW_VER="$(sed -n 's/.*"com\.microsoft\.playwright" % "playwright" % "\([0-9.]*\)".*/\1/p' "$PWD/build.sbt" 2>/dev/null | head -1)"
            if [ -z "$PW_VER" ]; then
              echo "agentbox: AGENTBOX_BROWSER=1 needs the Playwright version, but no" >&2
              echo "  com.microsoft.playwright dependency was found in $PWD/build.sbt." >&2
              echo "  Run this from the repo root." >&2
              exit 1
            fi

            mkdir -p "$STATE/playwright"

            # The trap below covers every ordinary exit, Ctrl-C included, but a
            # SIGKILL or a host crash skips it — and the orphan keeps holding
            # the published port. So reap first.
            #
            # Only genuine orphans: the name carries the wrapper's pid, so a
            # sidecar whose pid is gone is abandoned, while one whose pid is
            # alive belongs to a CONCURRENT box and must not be touched.
            for stale in $(docker ps -a --filter "name=^agentbox-browser-" --format '{{.Names}}'); do
              if ! kill -0 "''${stale##*-}" 2>/dev/null; then
                docker rm -f "$stale" >/dev/null 2>&1 || true
              fi
            done

            # Naming it ARMS the shared trap above: the sidecar is detached, so
            # it is not in the terminal's process group and never sees your
            # Ctrl-C, and `--rm` only fires once a container stops. Without that
            # it would outlive the box.
            BROWSER_NAME="agentbox-browser-$$"

            echo "==> starting browser sidecar (playwright $PW_VER)" >&2
            docker run -d --rm --name "$BROWSER_NAME" \
              --user "$(id -u):$(id -g)" \
              --security-opt no-new-privileges \
              --cap-drop=ALL \
              "''${PUBLISH_ARGS[@]}" \
              -v "$STATE/playwright:/pw" \
              -v "${browserServer}:/srv/server.cjs:ro" \
              -e HOME=/pw \
              "mcr.microsoft.com/playwright:v$PW_VER" \
              bash -c '
                set -e
                if [ ! -d /pw/node_modules/playwright-core ]; then
                  npm i --prefix /pw --no-save --no-audit --no-fund \
                    "playwright-core@'"$PW_VER"'" >/dev/null
                fi
                exec node /srv/server.cjs
              ' >/dev/null

            # Wait for the server to actually accept, rather than sleeping: the
            # first start installs playwright-core and takes far longer than any
            # fixed delay would allow for.
            #
            # Polled, NOT `docker logs -f | grep -m1`: grep leaves at the first
            # match, but `docker logs -f` only learns the pipe is gone when it
            # next writes, and a server that has said READY says nothing more —
            # so that pipeline blocks for the whole timeout on success. It looks
            # like a slow start rather than a bug, which is how it survived a
            # first round of testing here.
            READY=""
            for _ in $(seq 1 300); do
              if docker logs "$BROWSER_NAME" 2>&1 | grep -q '^READY '; then
                READY=1
                break
              fi
              # Stop waiting on a sidecar that has already died, so the error
              # below reports its output instead of the full timeout.
              [ "$(docker inspect -f '{{.State.Running}}' "$BROWSER_NAME" 2>/dev/null)" = "true" ] || break
              sleep 1
            done
            if [ -z "$READY" ]; then
              echo "agentbox: the browser sidecar never became ready. Its output:" >&2
              docker logs "$BROWSER_NAME" 2>&1 | tail -20 >&2
              exit 1
            fi

            NET_ARGS=(
              --network "container:$BROWSER_NAME"
              -e "FH_PLAYWRIGHT_WS=ws://127.0.0.1:${browserPort}/agentbox"
            )
            # The sidecar published it; the box must not ask for it again.
            # DASHBOARD_ARGS deliberately survives — the URL is still correct,
            # and it is what the box prints on the way in.
            PUBLISH_ARGS=()
          fi

          # --init so orphaned MCP/ripgrep children get reaped when the command
          # is `claude` (i.e. PID 1) rather than an interactive bash
          #
          # Not `exec`: with a sidecar running, this script has to outlive the
          # box to tear it down. The status is forwarded so `agentbox claude -p`
          # still reports the command's own exit code.
          docker run --rm --init \
            "''${TTY_ARGS[@]}" \
            --user "$(id -u):$(id -g)" \
            --security-opt no-new-privileges \
            --cap-drop=ALL \
            "''${PUBLISH_ARGS[@]}" \
            "''${DASHBOARD_ARGS[@]}" \
            "''${NET_ARGS[@]}" \
            "''${MOUNTS[@]}" \
            -e CLAUDE_CODE_SUBAGENT_MODEL=claude-sonnet-5 \
            -w /work \
            "$IMG" "$@"
          exit $?
        '';
      };
    in
    {
      packages.${system} = {
        default = pkgs.symlinkJoin {
          name = "agentbox-1.0";
          paths = [
            agentbox
            readme
          ];
          meta.mainProgram = name;
        };
        image = image;
      };

      apps.${system}.default = {
        type = "app";
        program = "${self.packages.${system}.default}/bin/${name}";
      };
    };
}
