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
            # The two lines the box opens with. Also what tmux's `docs` window
            # ends on, because tmux takes the alternate screen and the copy
            # printed on the way in is not visible while attached — and the
            # dashboard URL is the one line here that is only known at runtime.
            greet)
              echo "agentbox — docs: devbox readme" >&2
              if [ -n "''${AGENTBOX_DASHBOARD_URL:-}" ]; then
                echo "dashboard:   ''${AGENTBOX_DASHBOARD_URL}  (sbt dashboardServe in /work)" >&2
              else
                echo "dashboard:   no port published — restart with AGENTBOX_PORT=8080" >&2
              fi
              ;;
            jcodemunch-setup)
              shift
              exec jcodemunch-mcp init --hooks "$@"
              ;;
            skills-update)
              shift
              exec npx --yes skills update "$@"
              ;;
            tools-update)
              # the bootstrap installs these once and then guards on the binary
              # existing, so this is the only way they ever move
              echo "==> cs update" >&2
              cs update
              echo "==> uv tool upgrade --all" >&2
              uv tool upgrade --all
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
              echo "       devbox skills-update [skills...]   # npx skills update" >&2
              echo "       devbox tools-update                # cs + uv" >&2
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
              devbox greet
            fi

            mkdir -p "${state}"/{bin,cache,state,uv,npm,npm-cache}

            # An app that ships a prebuilt binary (sbt, scala, cellar) is
            # installed as a two-line shim whose line 2 is
            #   exec "<abs path in the archive cache>" "$@"
            # That cache is XDG_CACHE_HOME-derived, so a shim written under a
            # different XDG_CACHE_HOME — or one whose entry got evicted — still
            # passes -x and fails only when you run it:
            #   /opt/agent/bin/scala: .../bin/scala: No such file or directory
            # so -x alone is not evidence the tool works.
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

            # --copy, not symlink: canonical store must survive restarts, and
            # the skills CLI's own agent paths move around between versions —
            # guard on the stable claude-code one
            if [ ! -e "$HOME/.claude/skills/cellar" ]; then
              echo "==> npx skills add VirtusLab/cellar (claude-code + opencode)" >&2
              npx --yes skills add VirtusLab/cellar --skill cellar --copy -g -a claude-code -a opencode -y
            fi

            # The wrapper hands the token in as a file (see its GH_TOKEN
            # comment). Exported here rather than in /etc/bashrc so
            # non-interactive commands like `agentbox claude -p …` get it too.
            if [ -r /run/gh-token ]; then
              GH_TOKEN="$(cat /run/gh-token)"
              export GH_TOKEN
            fi

            # A box entered with no command is exactly the session tmux exists
            # for, so it is the default rather than something to remember to
            # type. Skipped when a command WAS named (`agentbox claude -p …`)
            # and when there is no tty — the wrapper passes `-i` alone for a
            # pipe, and tmux there dies with "open terminal failed: not a
            # terminal". AGENTBOX_TMUX=0 opts out.
            #
            # NOT `exec tmux`: the client is then PID 1, so detaching (C-b d)
            # would end the container and take the sbt server with it. Falling
            # through to the shell makes detach mean detach — `tmux` returns,
            # `exit` leaves the box.
            if [ "''${AGENTBOX_TMUX:-1}" != 0 ] &&
               [ -t 0 ] && [ -t 1 ] &&
               { [ $# -eq 0 ] || [ "''${1##*/}" = "bash" ]; }; then
              tmux || true
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

      # README "Two windows" says why. nixpkgs builds tmux with
      # `--sysconfdir=/etc`, so this is the system config and needs nothing in
      # the throwaway home.
      #
      # The two commands are a pair, and neither works alone:
      #   * `new-session -d` runs when the SERVER starts, which is early enough
      #     to lay out windows but leaves the session detached.
      #   * `default-client-command` is what a bare `tmux` runs in place of its
      #     built-in `new-session`, so it lands in the session above instead of
      #     creating an unnamed second one next to it.
      tmuxConf = pkgs.writeTextDir "etc/tmux.conf" ''
        new-session -d -s box -n docs -c /work "devbox greet; exec bash"
        new-window -d -t box: -n work -c /work
        set -g default-client-command "new-session -A -s box"

        set -g mouse on
        set -g history-limit 100000
        # Claude Code reads a bare ESC as interrupt; tmux's 500ms default makes
        # it wait for an escape sequence that is not coming.
        set -sg escape-time 10
      '';

      # GitHub's published SSH host keys, pinned rather than
      # `StrictHostKeyChecking=accept-new` — README "Git & GitHub" says why and
      # how to refresh them. `/etc/ssh/ssh_known_hosts` is OpenSSH's own
      # `GlobalKnownHostsFile` default (`ssh -G github.com` confirms it), so
      # this needs no ssh_config and nothing in the throwaway home.
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

      # A script that streams the image into `docker load`, so no multi-GB
      # tarball lands in the store on every rebuild.
      image = pkgs.dockerTools.streamLayeredImage {
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
          tmux # run an agent and the project side by side — see /etc/tmux.conf
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
          nodejs_24 # the frontend build (NpmPlugin), the Playwright driver, `npx skills`
          coursier # installs sbt + the Scala tools into /opt/agent on first run
          jdk
          async-profiler # allocation-site sampling (issue #237); JFR is built-in
          uv
          python3 # runtime for jcodemunch-mcp
          # /lib64 loader for non-nix binaries (cs-installed native tools like
          # cellar expect /lib64/ld-linux-x86-64.so.2)
          glibc
          devbox
          readme
          bashrc
          tmuxConf
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
          # cs/uv-installed launchers use #!/usr/bin/env; the image only
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
            # nix glibc's loader only searches the store, and the cs-installed
            # native binaries (cellar, and the scala-cli behind `scala`) need
            # libz.so.1. Global rather than per tool: it is the same zlib the
            # nix binaries in this image already link.
            "LD_LIBRARY_PATH=${pkgs.zlib}/lib"
            "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
            "TERM=xterm-256color"
            "LANG=C.UTF-8"
          ];
        };
      };

      readme = pkgs.writeTextDir "share/doc/${name}/README.md" ''
        # agentbox

        One Nix flake: an OCI image with opencode, Claude Code, jCodeMunch and
        a Coursier-managed Scala toolchain on JDK 25, and the `agentbox`
        wrapper that runs it with your agent configs bind-mounted from the host.

        ## Use

            nix run .#                     # tmux in the container, cwd as /work
            nix run .# -- claude           # or opencode; naming a command skips tmux
            AGENTBOX_TMUX=0 nix run .#     # a plain shell
            AGENTBOX_PORT=8080 nix run .#  # publish the dashboard; 8081 remaps it
            # inside the box:
            devbox readme              # this document
            devbox mcp jcodemunch      # wire an MCP server into both agents
            devbox skills-update       # update installed agent skills
            devbox tools-update        # update the cs / uv tools

        The wrapper loads the image into Docker whenever the build changes.
        Unfree claude-code is allowed in the flake itself, so no `--impure`.
        Needs an x86_64-linux builder; elsewhere change `system` and check that
        `pkgs.opencode` / `pkgs.claude-code` build there.

        A published port reaches the container's eth0, so the server inside
        must bind `0.0.0.0` (`HOST=0.0.0.0` in `.env` for the dashboard) or
        `-p` looks silently broken.

        ### Two windows

        `nix run .#` lands in tmux: window `docs` (this README, then a shell)
        and window `work`, both in `/work`. `C-b d` detaches to a shell in the
        same box with sbt still up — `tmux` returns, `exit` leaves. Use a window
        rather than a second box: two boxes share `/opt/agent`, so two sbt
        servers would share one Coursier cache and one bash history. The layout
        is `/etc/tmux.conf`; `tmux new -s other` still gets a plain session.

        ### Telling the box from the host

        The repo's Claude Code status line (`.claude/statusline.sh`) opens with
        `▣ agentbox · ssh gh gpg :8080` in a box and a red
        `△ host  not contained` outside one. It keys off `AGENTBOX=1`, baked
        into the image, so a host shell that exported it cannot borrow the
        badge. Each chip is detected from inside (a socket, a mounted file);
        `unsigned` means no gpg socket arrived.

        ### Browser tests

        The image has no browser. `AGENTBOX_BROWSER=1` starts a Playwright
        sidecar (`mcr.microsoft.com/playwright:v<version>`, the version read
        from `build.sbt` so client and server cannot drift) for the smoke
        suites to connect to:

            AGENTBOX_BROWSER=1 nix run .#
            # inside: sbt 'fh-datastar-view/testFull'

        Without it, run `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`.
        The first start installs `playwright-core` into `~/.agentbox/playwright`
        and takes a minute. The sidecar owns the network namespace, so it is
        the container that publishes `AGENTBOX_PORT`.

        `ComponentVisualSuite`'s `entity-card-off` lands just over its 0.3%
        budget here, because font rasterization differs from CI's. **CI is
        authoritative for the visual baselines** — never regenerate them in
        the box.

        No `--ipc=host`, though Playwright recommends it: it would hand the box
        the host's IPC namespace. It guards against Chromium exhausting
        `/dev/shm`, which shows up as a browser crash mid-suite; if that ever
        happens, raise the sidecar's `--shm-size` first.

        ## Two halves

        The **host half** is the `agentbox` script: it loads the image, writes
        the passwd file, picks the mounts and the port, and calls `docker run`.
        Anything that must exist before the box starts (an egress proxy, a
        docker network, a seccomp profile) belongs there — which is also why
        there is no devShell. The **container half** is the image and its
        `bootstrap` entrypoint, which installs the Coursier/uv tools on first
        run and execs your command.

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

        Signing follows your git config instead (see Signed commits):

        | Host                          | Container                 | Enabled by         |
        |-------------------------------|---------------------------|--------------------|
        | gpg-agent's *extra* socket    | `~/.gnupg/S.gpg-agent`    | `commit.gpgsign`   |
        | `pubring.kbx`, `trustdb.gpg`  | `~/.gnupg` (copy, public half) | `commit.gpgsign` |

        Claude Code's credentials are `~/.claude/.credentials.json` on Linux, so
        the box starts logged in; on macOS they are in the Keychain, so export
        `ANTHROPIC_API_KEY` or log in once inside.

        `/work` includes `target/`, so box and host share build outputs, and
        alternating sbt between them can crash the compiler
        (`NoSuchFileException ...semanticdb`, *"clsFile is null"*).
        `sbt <module>/clean` fixes it.

        ## Permissions

        Nothing in the box runs as root: the wrapper passes
        `--user $(id -u):$(id -g)`, `--security-opt no-new-privileges` and
        `--cap-drop=ALL`, and mounts a generated `/etc/passwd` naming that uid
        `dev` (without an entry, Node's `os.userInfo()` throws). Files created
        in `/work` and the mounts are yours on the host.

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
          `npx` all resolve latest, and `npx skills add VirtusLab/cellar -y`
          writes an unpinned third-party skill into your real
          `~/.claude/skills` when it is missing there.
        - **`AGENTBOX_PORT` binds `0.0.0.0`**, so a published dashboard is on the
          LAN, not just localhost. That is deliberate (phone access); prefix the
          value with `127.0.0.1:` if you would rather it were not.
        - **`AGENTBOX_GH=1` puts a token inside the box.** GitHub has no agent
          to forward the way SSH and gpg do, so the token IS the credential. It
          goes in as a file, not `-e`, so it is in neither the host's
          `docker run` argv (readable by any local user via
          `/proc/<pid>/cmdline`) nor `docker inspect`; the file is made in tmpfs
          `/dev/shm` rather than on-disk `/tmp` and unlinked on exit. A
          fine-grained PAT (below) is the narrower way in.
        - **The forwarded gpg socket lets the box sign anything while it
          runs.** On by default, because an unsigned commit was judged the
          worse outcome; the key never goes in, so it ends with the container.
          `AGENTBOX_GPG=off` opts out.
        - **`AGENTBOX_BROWSER=1` runs a third-party image** in the box's network
          namespace, and a browser is a general fetcher. It gets no host mounts
          beyond its own `playwright-core` dir, and its automation port is on
          loopback. A Playwright server on the HOST is rejected: it has no ACL,
          so the box could drive a browser running as you, `file:///` reads of
          `~/.ssh` included.

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

        **github.com's host keys ship in the image**
        (`/etc/ssh/ssh_known_hosts`): under `agent` the box has no `~/.ssh`, and
        an agent cannot answer the first push's fingerprint prompt. They are
        pinned rather than `accept-new`, which trusts whatever answers first.
        Refresh them (GitHub rotated its RSA key in 2023) with:

            gh api meta --jq '.ssh_keys[] | "github.com \(.)"'

        `AGENTBOX_GH=1` passes a token rather than mounting `~/.config/gh`: a
        mounted config is the box's to rewrite, and on a home-manager host its
        `/nix/store` symlink dangles inside (*"failed to write config after
        migration"*). The token carries every scope your `gh` login has; for
        narrower access export a fine-grained PAT and leave `AGENTBOX_GH`
        unset:

            GH_TOKEN=github_pat_… nix run .#

        ### Signed commits

        **Not opt-in.** If `git config commit.gpgsign` is true the box signs, and
        if it cannot it refuses to start — an unsigned commit that looks fine in
        the log is worse than a startup error.

            nix run .#                      # signs, because your git config says so
            AGENTBOX_GPG=agent nix run .#   # force it on regardless of git config
            AGENTBOX_GPG=off   nix run .#   # deliberately unsigned; warns on the way in

        `AGENTBOX_SSH=agent` does not cover this even when gpg-agent is your ssh
        agent: `S.gpg-agent.ssh` speaks only the ssh-agent protocol. What gets
        forwarded is `S.gpg-agent.extra`, gnupg's restricted socket for a less
        trusted machine — it signs, but refuses `gpg --export-secret-keys`.
        Only the public keyring goes in, as a copy under `~/.agentbox/gnupg`
        because gpg needs a writable home.

        `gpg -K` may list nothing over that socket while signing works — test
        with `gpg --clearsign`.

        ## Agent skills

        The [skills CLI](https://skills.sh) manages agent skills for both
        claude-code and opencode; installs land in your real `~/.claude/skills`
        and `~/.config/opencode/skills`:

            npx skills add <owner/repo> -a claude-code -a opencode
            devbox skills-update       # = npx skills update

        The bootstrap adds Cellar's skill for both agents when it is missing, so
        they know when to reach for it.

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

        JFR is in the JDK, and async-profiler (nixpkgs names its `asprof`
        binary `async-profiler`) names allocation sites (pid from `jps`):

            async-profiler -d 30 -e alloc -f /tmp/alloc.html <pid>

        `alloc` and `wall` work under `--cap-drop=ALL`; `cpu` needs kernel perf
        access, so profile cpu on the Pi. In JMH:
        `-prof async:event=alloc,libPath=$(dirname $(readlink -f $(which
        async-profiler)))/../lib/libasyncProfiler.so`.

        ## Pinning

        **Pinned by the flake lock** — opencode, claude-code, the JDK, Node and
        the Coursier launcher itself. `nix flake update` is the only way they
        move. `/opt/agent/bin` comes first on `PATH`, so an `npm install -g` of
        one of these silently shadows the pinned copy — check `which claude`
        if a version looks wrong.

        **Installed once into `/opt/agent`, resolving latest** — `sbt`, `scala`,
        `scalac`, `scalafmt`, `metals-mcp`, `cellar` (Coursier);
        `jcodemunch-mcp` (uv). The bootstrap guards on the binary existing, so
        they never move on their own:

            devbox tools-update        # cs update + uv tool upgrade

        The `skills` CLI is not installed at all; `npx` fetches it on use.

        The `sbt` here is only the launcher; which sbt actually builds your
        project is `sbt.version` in `project/build.properties`, so the launcher
        drifting ahead is harmless.

        Pin one by hand if it matters:

            uv tool install jcodemunch-mcp==1.20.0
            npx skills@1.5.23 update

        ## Escape hatches

            nix build .#image && ./result | docker load   # just the image
            AGENTBOX_RELOAD=1 nix run .#   # re-load the image into Docker
      '';

      agentbox = pkgs.writeShellApplication {
        name = name;
        text = ''
          IMG="${name}:${tag}"
          # the store path identifies the exact build; tagging it lets a plain
          # inspect decide freshness without streaming the image
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
            ${image} | docker load >&2
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

          # Your host Claude Code executes settings.json hooks and injects
          # CLAUDE.md, so writable copies would be a container-to-host escape.
          # Read-only on top of the rw ~/.claude; nested binds resolve by depth,
          # so mount order does not matter.
          for f in settings.json CLAUDE.md; do
            [ -e "$HOME/.claude/$f" ] && MOUNTS+=(-v "$HOME/.claude/$f:/home/dev/.claude/$f:ro")
          done

          # .gitconfig carries no secret and authorship would be wrong without
          # it, so it is the one identity file mounted unconditionally.
          if [ -f "$HOME/.gitconfig" ]; then MOUNTS+=(-v "$HOME/.gitconfig:/home/dev/.gitconfig:ro"); fi

          # Credentials are opt-in; README "Git & GitHub" has the trade-offs of
          # each mode.
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

          # Signing is NOT opt-in, and uses gpg-agent's restricted EXTRA socket —
          # README "Signed commits" says why for both.
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
          # Also forwards a GH_TOKEN you exported yourself. A file, not `-e`,
          # and in /dev/shm rather than /tmp — README "What the box can reach"
          # says why. `bootstrap` exports it inside the box.
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

          # Two arrays because they can belong to different containers: the
          # publish flag follows whoever owns the network namespace (the sidecar,
          # under AGENTBOX_BROWSER=1), while the banner URL is always the box's.
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
            # so that pipeline blocks for the whole timeout on success, which
            # looks like a slow start rather than a bug.
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
            -e "AGENTBOX_TMUX=''${AGENTBOX_TMUX:-1}" \
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
