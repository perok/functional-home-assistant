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
        | `gh auth token`               | `$GH_TOKEN` (env, no mount) | `AGENTBOX_GH=1`    |

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
        - **`AGENTBOX_GH=1` puts a token in the container environment**, visible
          to `docker inspect` — i.e. to anything that can reach the docker
          daemon, which is already root-equivalent on the host.
        - **The forwarded gpg socket lets the box sign anything, for as long as
          it runs** — a commit you did not write, a mail, a release tarball.
          Accepted deliberately, and unlike the other rows it is on by default,
          because unsigned commits were judged the worse outcome. It is the
          *extra* socket precisely to bound it: use ends when the container
          does, because the key itself never went in. `AGENTBOX_GPG=off` opts
          out.

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
          if [ -n "''${GH_TOKEN:-}" ]; then MOUNTS+=(-e "GH_TOKEN=$GH_TOKEN"); fi

          # must exist as a file, or docker creates a directory in its place
          [ -f "$HOME/.claude.json" ] || echo '{}' > "$HOME/.claude.json"

          # Publishing is OPT-IN: AGENTBOX_PORT=8080 exposes the Datastar
          # dashboard, any other value remaps it. Nothing is published by
          # default, so a box never collides with a dashboard already running
          # on the host.
          PORT="''${AGENTBOX_PORT:-}"
          if [ -n "$PORT" ]; then
            # AGENTBOX_PORT may carry a host-interface prefix
            # (127.0.0.1:8080), which is not part of the URL.
            PORT_ARGS=(
              -p "''${PORT}:8080"
              -e "AGENTBOX_DASHBOARD_URL=http://localhost:''${PORT##*:}"
            )
          else
            PORT_ARGS=()
          fi

          # -t only with a real tty, so `agentbox claude -p …` still works in a
          # pipe or from CI ("the input device is not a TTY")
          if [ -t 0 ] && [ -t 1 ]; then TTY_ARGS=(-it); else TTY_ARGS=(-i); fi

          # --init so orphaned MCP/ripgrep children get reaped when the command
          # is `claude` (i.e. PID 1) rather than an interactive bash
          exec docker run --rm --init \
            "''${TTY_ARGS[@]}" \
            --user "$(id -u):$(id -g)" \
            --security-opt no-new-privileges \
            --cap-drop=ALL \
            "''${PORT_ARGS[@]}" \
            "''${MOUNTS[@]}" \
            -w /work \
            "$IMG" "$@"
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
