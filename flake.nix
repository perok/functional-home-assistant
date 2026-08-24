{
  description = "agentbox: opencode + claude-code + opkg + jCodeMunch + Coursier/sbt on JDK 25";

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
            *)
              echo "usage: devbox readme" >&2
              echo "       devbox skills-update [skills...]   # skills CLI" >&2
              echo "       devbox tools-update                # cs + uv + npm" >&2
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

            # sbt itself comes from nixpkgs — the cs-installed one was a shim
            # into ~/.cache that died with the container; clear stale copies
            rm -f "${state}/bin/sbt"
            if [ ! -x "${state}/bin/scalafmt" ]; then
              echo "==> cs install scala scalac scalafmt" >&2
              cs install --quiet scala scalac scalafmt metals-mcp
            elif [ ! -x "${state}/bin/metals-mcp" ]; then
              echo "==> cs install metals-mcp" >&2
              cs install --quiet metals-mcp
            fi

            if [ ! -x "${state}/bin/cellar" ]; then
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

            if [ ! -x "${state}/bin/opkg" ]; then
              echo "==> npm install -g opkg" >&2
              npm install -g --silent opkg
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
          ripgrep
          fd
          jq
          gh
          opencode
          claude-code
          nodejs_24 # runtime for opkg + the skills CLI
          coursier
          sbt
          jdk
          uv
          python3 # runtime for jcodemunch-mcp
          # /lib64 loader for non-nix binaries (cs-installed native tools like
          # cellar expect /lib64/ld-linux-x86-64.so.2)
          glibc
          devbox
          readme
          dockerTools.fakeNss
          dockerTools.caCertificates
          context7-mcp
        ];

        extraCommands = ''
          mkdir -p tmp work opt/agent \
                   home/dev/.config/opencode \
                   home/dev/.local/share/opencode \
                   home/dev/.local/state \
                   home/dev/.openpackage \
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
            "PATH=${state}/bin:/bin:/usr/bin"
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
            # claude-code comes from the read-only store — never let it
            # self-update. Belt and braces: nixpkgs' own wrapper --sets this
            # too, so it stays right even if this line is lost.
            "DISABLE_AUTOUPDATER=1"
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
        OpenPackage (`opkg`), the jCodeMunch MCP server, and a Coursier-managed
        Scala toolchain on JDK 25. Agent configs and auth are bind-mounted from
        the host, so the container shares your existing setup.

        ## Use

            nix run .#                 # bash in the container, cwd as /work
            nix run .# -- opencode
            nix run .# -- claude
            # inside the box:
            devbox readme              # this document
            devbox skills-update       # update installed agent skills
            devbox tools-update        # update the cs / uv / npm tools

        Unfree packages (claude-code) are allowed in the flake itself — no env
        vars or `--impure` needed.

        Nothing is published to the host by default. `AGENTBOX_PORT=8080`
        exposes the Datastar dashboard, any other value remaps it:

            AGENTBOX_PORT=8081 nix run .#   # dashboard on localhost:8081

        The wrapper loads the image into Docker when the build changes. Inside
        the box, `devbox readme` shows this document. Requires an
        x86_64-linux builder; on macOS/aarch64 add a linux builder or change
        `system` and confirm `pkgs.opencode` / `pkgs.claude-code` build there.

        ## Shared with the host

        | Host                          | Container                 |
        |-------------------------------|---------------------------|
        | `~/.claude`                   | `~/.claude`               |
        | `~/.claude.json`              | `~/.claude.json`          |
        | `~/.config/opencode`          | `~/.config/opencode`      |
        | `~/.local/share/opencode`     | `~/.local/share/opencode` |
        | `~/.openpackage`              | `~/.openpackage`          |
        | `~/.agentbox`                 | `/opt/agent`              |
        | `$PWD`                        | `/work`                   |
        | `~/.gitconfig`                | `~/.gitconfig` (ro)       |

        Opt-in only (see Git & GitHub):

        | Host                          | Container                 | Enabled by         |
        |-------------------------------|---------------------------|--------------------|
        | `$SSH_AUTH_SOCK`              | `/run/ssh-agent.sock`     | `AGENTBOX_SSH=agent` |
        | `~/.ssh`                      | `~/.ssh` (ro)             | `AGENTBOX_SSH=keys`  |
        | `gh auth token`               | `$GH_TOKEN` (env, no mount) | `AGENTBOX_GH=1`    |

        Claude Code, opencode and opkg all live in the Nix store or `/opt/agent`
        and are pinned; only their state is shared. Sessions, project history
        and settings written inside the container land in your real `~/.claude`.

        On Linux the Claude Code credential file is `~/.claude/.credentials.json`
        and is therefore inside the container. On macOS credentials are in the
        Keychain, so the container starts logged out — export `ANTHROPIC_API_KEY`
        or run `claude` once inside and log in. Either way, auth tokens are
        readable by anything running in there. Trusted repos only; a container
        is not an exfiltration boundary.

        `scala` and `scalafmt` (Coursier) install into `/opt/agent` on first
        run — network needed once, then cached; `sbt`, the agents and all
        other tools come pinned from nixpkgs. Tool caches and XDG state/cache
        also live in `/opt/agent`, so they survive container restarts.

        ## Permissions

        Nothing in the box ever runs as root. The wrapper passes
        `--user $(id -u):$(id -g)` plus `--security-opt no-new-privileges`, and
        mounts a generated `/etc/passwd` naming that uid `dev` (docker's
        `--user` adds no NSS entry of its own, and a uid without one makes
        Node's `os.userInfo()` throw). Files created in `/work` and in the
        mounts are owned by you on the host — no root-owned strays.

        Claude Code's own allow/deny rules go in `.claude/settings.json` in the
        repo (not `settings.local.json`, which "don't ask again" rewrites).
        Rules evaluate deny → ask → allow, with `defaultMode` as fallback:

            {
              "permissions": {
                "defaultMode": "acceptEdits",
                "allow": ["Bash(sbt test:*)", "Bash(git diff:*)", "Bash(rg:*)"],
                "deny":  ["Read(**/.env)"]
              }
            }

        That layer is enforced by Claude Code. The image contents are the real
        boundary — nothing on the host is reachable except the mounts above.

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

        GPG signing is not wired up. With gpg-agent providing ssh (a common
        setup), `AGENTBOX_SSH=agent` already forwards that socket, so SSH
        commit signing works; `gpg.format = openpgp` does not.

        ## Agent skills

        The [skills CLI](https://skills.sh) manages agent skills for both
        claude-code and opencode; installs land in your real `~/.claude/skills`
        and `~/.config/opencode/skills`:

            skills add <owner/repo> -a claude-code -a opencode
            devbox skills-update       # = skills update

        Cellar's skill is preinstalled for both agents, so they know when to
        reach for it.

        ## Scala API lookups

        Two complements are installed:

        - **cellar** — type signatures, members and docs for any Maven
          artifact straight from the terminal (no server needed):

              cellar get-external org.typelevel:cats-core_3:latest cats.Monad

          Telemetry is opted out during bootstrap — its consent prompt would
          otherwise withhold output from piped/agent invocations.
        - **metals-mcp** — Metals' standalone MCP server. Enable per project:

              metals-mcp --workspace . --client claude   # writes .mcp.json

          or wire stdio into opencode's config:

              { "mcp": { "metals": { "type": "local",
                  "command": ["metals-mcp", "--transport", "stdio"],
                  "enabled": true } } }

        ## OpenPackage

        `opkg` manages rules, commands, agents, skills and MCP configs across
        platforms, including both agents in this image:

            opkg install essentials
            opkg install gh@anthropics/claude-code --plugins code-review
            opkg install <resource> --platforms claudecode opencode
            opkg list -f

        `-g` installs to `~/` instead of the workspace. Overrides live in
        `<cwd>/.openpackage/platforms.jsonc` and `~/.openpackage/platforms.jsonc`,
        deep-merged local > global > built-in.

        ## jCodeMunch

        For opencode, `~/.config/opencode/opencode.json`:

            { "mcp": { "jcodemunch": { "type": "local",
                "command": ["jcodemunch-mcp"], "enabled": true } } }

        For Claude Code, `.mcp.json` at the repo root:

            { "mcpServers": { "jcodemunch": { "command": "jcodemunch-mcp" } } }

        Or write it once as an `mcp.jsonc` in an opkg package and let `opkg`
        emit both. Index a repo once from `/work` and the agent retrieves by
        symbol rather than reading whole files. Free for personal use, paid for
        commercial.

        ## Pinning

        Two populations, two update paths.

        **Pinned by the flake lock** — opencode, claude-code, **sbt**, the JDK,
        Node and the Coursier launcher. `nix flake update` is the only way they
        move. (sbt comes from nixpkgs, currently matching the `sbt.version` in
        `project/build.properties`; the bootstrap deletes any `cs`-installed
        `sbt`, because that one is a shim into `~/.cache` that dies with the
        container.)

        **Installed once into `/opt/agent`, resolving latest** — `scala`,
        `scalac`, `scalafmt`, `metals-mcp`, `cellar` (Coursier);
        `jcodemunch-mcp` (uv); `opkg`, `skills` (npm). The bootstrap guards on
        the binary existing, so they never move on their own:

            devbox tools-update        # cs update + uv tool upgrade + npm update -g

        Pin one by hand if it matters:

            uv tool install jcodemunch-mcp==1.20.0
            npm install -g opkg@0.11.3

        ## Escape hatches

            nix build .#image       # just the tarball
            nix develop             # same toolchain on the host, no container
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
          OPKG="$HOME/.openpackage"
          STATE="$HOME/.agentbox"
          mkdir -p "$CFG" "$DATA" "$OPKG" "$STATE"/{home-cache,home-sbt,home-agents,nss} "$HOME/.claude"

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
            -v "$OPKG:/home/dev/.openpackage"
            -v "$STATE:/opt/agent"
            -v "$STATE/home-cache:/home/dev/.cache"
            -v "$STATE/home-sbt:/home/dev/.sbt"
            -v "$STATE/home-agents:/home/dev/.agents"
            -v "$PWD:/work"
          )
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
            PORT_ARGS=(-p "''${PORT}:8080" -e "AGENTBOX_DASHBOARD_URL=http://localhost:''${PORT}")
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

      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          opencode
          claude-code
          coursier
          sbt
          jdk
          uv
          nodejs_24
          python3
          git
        ];
      };
    };
}
